#!/usr/bin/env python3
"""Multi-device end-to-end LAN run: 1 host + 3 players + up to 2 watchers (stands in for the
software part of HP-2 on emulators; it is NOT an HP-2 pass). See tools/e2e/README.md.

Each device runs the debug app plus the on-device driver (E2eAgentTest, androidTestDebug) started
with `adb shell am instrument`. This script installs both, wires the network (the host's port is
exposed with `adb forward`; joiners reach it through `adb reverse`, the emulator host alias
10.0.2.2, or a relay on this machine's LAN address), compares the six-digit codes the joiner and the
host show and only then tells the host to approve, starts the match, kills and restarts one player
mid-match and checks it rejoins its seat by its pinned ticket (no approval), checks the watchers
never see a hidden card, checks every device ends with the same result, and writes a JSON result
and a Markdown report.

Device spec: SERIAL (this machine's adb server) or SERIAL@PORT (an adb server on 127.0.0.1:PORT,
for example a remote Mac mini's server reached through `ssh -L PORT:127.0.0.1:5037 mini`). A
device on another machine's adb server cannot use `adb reverse` to reach this machine's forwarded
port: give --host-address (this Mac's LAN address) and such devices dial the relay there.

Python standard library only. Run it with `python3 -I`.
"""

import argparse
import datetime as dt
import json
import os
import platform
import re
import shlex
import socket
import subprocess
import sys
import threading
import time

PKG = "cn.wpgd.app"
RUNNER = "cn.wpgd.app.test/androidx.test.runner.AndroidJUnitRunner"
AGENT = "cn.wpgd.app.e2e.E2eAgentTest#agent"
REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _find_dev_docs(start):
    """Locate the sibling dev-docs repository: one level above the primary checkout, two above a
    linked worktree in worktrees/<name>/, so a fixed parent is wrong there. Probe upward and fail
    loudly rather than writing evidence into a directory that does not exist."""
    d = start
    while True:
        candidate = os.path.join(d, "dev-docs")
        if os.path.isfile(os.path.join(candidate, "implementation-plan.md")):
            return candidate
        parent = os.path.dirname(d)
        if parent == d:
            raise SystemExit("dev-docs/ repository not found at or above " + start)
        d = parent


DEV_DOCS = _find_dev_docs(REPO)
SDK = os.environ.get("ANDROID_SDK_ROOT", "/opt/homebrew/share/android-commandlinetools")
ADB = os.path.join(SDK, "platform-tools", "adb")


REPORT_TEMPLATE = """---
title: "Emulator end-to-end LAN run {{STAMP}} (software part of HP-2)"
created: {{STARTED}}
status: "EMULATOR EVIDENCE. Not an HP-2 pass: no physical phone, no real Wi-Fi radio, no NSD between devices, no traffic capture."
---

# Emulator end-to-end LAN run {{STAMP}}

Written by `tools/e2e/run_e2e.py`; machine-readable record: [{{JSON}}]({{JSON}}); driver logs
under `{{LOGS}}` (not committed). What this run does and does not stand for:
[HP-2-lan-four-phones.md](HP-2-lan-four-phones.md), section "Emulator run".

**Run outcome: {{OUTCOME}}** ({{REASON}})

| Field | Value |
|---|---|
| Commit | {{COMMIT}} |
| Machine | {{MACHINE}} |
| Deals played | {{DEALS}} |
| Final result (host's view) | `{{FINAL}}` |
| Final result identical on every device | {{IDENTICAL}} |
| Every observed deal result identical | {{DEAL_AGREE}} |
| Mid-match kill and rejoin | `{{REJOIN}}` |
| Watchers never saw a hidden card (state and screen semantics) | `{{PRIVACY}}` |
| Driver violations per device | `{{VIOLATIONS}}` |

## Devices

Discovery (NSD) does not cross emulator NATs, so every joiner used join by address (按地址加入)
at the address in the "Dialled" column.

| Role | Serial | Model | Android | Kind | adb | Dialled |
|---|---|---|---|---|---|---|
{{DEVICES}}

## Code comparison before each approval

The harness read the six-digit code from the joiner's screen state and from the host's pending
list, and told the host to approve only when both were equal.

| Joiner | Joiner shows | Host shows | Equal |
|---|---|---|---|
{{CODES}}

## Timings (seconds from start)

| Step | Seconds |
|---|---|
{{TIMINGS}}

## Notes

{{NOTES}}

## Not covered by this run

- Real phones, real Wi-Fi radios, OEM power management, Wi-Fi power save.
- NSD/mDNS discovery between devices (emulators each sit behind their own NAT).
- A capture showing no packet leaves the local network (P9).
- Two simultaneous hosts told apart in the room list (GDN-009).
- Private follow (consent, deny, revoke, second request) and the Wi-Fi interruption of HP-2 step 8.

Outcome: EMULATOR RUN {{OUTCOME}}, HP-2 NOT RUN
"""


class Failure(Exception):
    pass


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


class Device:
    def __init__(self, spec, role, nick, seat=None):
        self.serial, _, port = spec.partition("@")
        self.adb_port = int(port) if port else None
        self.role, self.nick, self.seat = role, nick, seat
        self.proc = None
        self.log_path = None
        self.dial = None  # (host, port) this device dials to reach the host
        self.info = {}

    @property
    def remote(self):
        return self.adb_port is not None

    @property
    def endpoint(self):
        """The serial and, for another adb server, its port: emulator-5554 on two servers is two devices."""
        return f"{self.serial}@{self.adb_port}" if self.adb_port else self.serial

    @property
    def name(self):
        return f"{self.role}{'-' + self.seat if self.seat else ''}({self.endpoint})"

    def log_file(self, logdir, tag):
        return os.path.join(logdir, f"{self.role}-{self.seat or self.endpoint}-{tag}.log")

    def adb_cmd(self, *args):
        cmd = [ADB]
        if self.adb_port:
            cmd += ["-P", str(self.adb_port)]
        return cmd + ["-s", self.serial, *args]

    def adb(self, *args, check=True, timeout=120):
        r = subprocess.run(self.adb_cmd(*args), capture_output=True, text=True, timeout=timeout)
        if check and r.returncode != 0:
            raise Failure(f"{self.name}: adb {' '.join(args)} failed ({r.returncode}): {r.stderr.strip() or r.stdout.strip()}")
        return r.stdout

    def shell(self, command, check=True, timeout=60):
        return self.adb("shell", command, check=check, timeout=timeout)

    def read(self, name):
        """A file from the driver's private directory (files/e2e/), or None."""
        r = subprocess.run(self.adb_cmd("exec-out", "run-as", PKG, "cat", f"files/e2e/{name}"), capture_output=True, timeout=60)
        # `adb exec-out` does not pass the remote exit code on: a missing file shows up as cat's error text.
        if r.returncode != 0 or not r.stdout or r.stdout.startswith(b"cat: ") or r.stdout.startswith(b"run-as: "):
            return None
        return r.stdout.decode("utf-8", "replace")

    def read_json(self, name):
        text = self.read(name)
        if not text:
            return None
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return None  # mid-rename; next poll

    def command(self, name):
        # The inbox is the driver's private directory; run-as makes the file as the app's user.
        self.shell(f"run-as {PKG} touch files/e2e/inbox/{shlex.quote(name)}")

    def start_agent(self, extra, logdir, tag):
        if self not in WATCHED:
            WATCHED.append(self)
        args = ["shell", "am", "instrument", "-w", "-r", "-e", "class", AGENT, "-e", "e2e.role", self.role, "-e", "e2e.nick", self.nick]
        for k, v in extra.items():
            args += ["-e", k, str(v)]
        args.append(RUNNER)
        self.log_path = self.log_file(logdir, tag)
        out = open(self.log_path, "w")
        self.proc = subprocess.Popen(self.adb_cmd(*args), stdout=out, stderr=subprocess.STDOUT)

    def agent_exit(self):
        return None if self.proc is None else self.proc.poll()


class Relay:
    """TCP relay on this machine's LAN address to the adb-forwarded host port (cross-machine runs)."""

    CONNECT_TIMEOUT = 5.0

    def __init__(self, bind, port, target_port, allow):
        self.server = socket.create_server((bind, port))
        self.port = self.server.getsockname()[1]
        self.target = ("127.0.0.1", target_port)
        # Refused sources are counted apart: zero connections must say whether nothing arrived or the allow-list refused it.
        self.allow, self.connections, self.rejected, self.target_errors = set(allow), 0, 0, 0
        threading.Thread(target=self.loop, daemon=True).start()

    def loop(self):
        while True:
            try:
                c, addr = self.server.accept()
            except OSError:
                return
            if self.allow and addr[0] not in self.allow:
                self.rejected += 1
                c.close()
                continue
            self.connections += 1
            try:
                t = socket.create_connection(self.target, timeout=self.CONNECT_TIMEOUT)
                t.settimeout(None)  # the timeout is for connecting only: a quiet table must not drop the link
            except OSError:
                self.target_errors += 1
                c.close()
                continue
            for a, b in ((c, t), (t, c)):
                threading.Thread(target=self.pipe, args=(a, b), daemon=True).start()

    @staticmethod
    def pipe(a, b):
        try:
            while data := a.recv(65536):
                b.sendall(data)
        except OSError:
            pass
        finally:
            for s in (a, b):
                try:
                    s.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                # Close too: shutdown alone leaks the descriptor. The other direction's thread then
                # fails its recv with OSError and ends; closing twice is harmless.
                s.close()

    def close(self):
        self.server.close()


WATCHED = []  # drivers whose early exit fails any wait at once


def wait(what, timeout, poll, interval=1.0):
    end = time.time() + timeout
    while time.time() < end:
        check_agents_alive(WATCHED)
        v = poll()
        if v:
            return v
        time.sleep(interval)
    raise Failure(f"timed out after {timeout:.0f} s waiting for {what}")


def device_info(d):
    props = {}
    for p in ("ro.product.model", "ro.build.version.release", "ro.build.version.sdk", "ro.kernel.qemu"):
        props[p] = d.shell(f"getprop {p}", check=False).strip()
    return props


def check_agents_alive(devices):
    for d in devices:
        code = d.agent_exit()
        if code is not None:
            tail = open(d.log_path).read()[-3000:] if d.log_path else ""
            raise Failure(f"{d.name}: driver exited early ({code}):\n{tail}")


def parse_args(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", required=True, help="device spec of the host")
    ap.add_argument("--player", action="append", required=True, help="device spec; three of them (EAST, NORTH, WEST)")
    ap.add_argument("--watcher", action="append", default=[], help="device spec; zero to two")
    ap.add_argument("--apk", default=os.path.join(REPO, "app/build/outputs/apk/debug/app-debug.apk"))
    ap.add_argument("--test-apk", default=os.path.join(REPO, "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
    ap.add_argument("--local-net", choices=["reverse", "emu-host"], default="reverse",
                    help="how devices on this machine's adb server reach the host: adb reverse to 127.0.0.1, or the emulator alias 10.0.2.2")
    ap.add_argument("--host-address", help="this machine's LAN address; devices on other adb servers dial a relay there")
    ap.add_argument("--relay-port", type=int, default=47000)
    ap.add_argument("--relay-allow", action="append", default=[], help="source addresses the relay accepts (default: any)")
    ap.add_argument("--deals", type=int, default=0, help="stop after this many deals; 0 plays the whole match")
    # A misspelt seat must not silently skip the restart check: only the three seats or empty.
    ap.add_argument("--restart-seat", default="EAST", choices=["EAST", "NORTH", "WEST", ""],
                    help="player seat to kill and restart mid-match; empty for none")
    ap.add_argument("--restart-mode", choices=["emulator", "process"], default="process")
    ap.add_argument("--restart-avd", help="emulator restart: the AVD name of that player (local emulators only)")
    ap.add_argument("--restart-after-deal", type=int, default=1, help="restart once this deal has ended and the next is being played")
    ap.add_argument("--timeout-min", type=int, default=90)
    ap.add_argument("--out", default=os.path.join(DEV_DOCS, "evidence"))
    ap.add_argument("--note", action="append", default=[], help="a line for the report (for example why fewer watchers ran)")
    ap.add_argument("--skip-install", action="store_true")
    return ap.parse_args(argv)


def main():
    a = parse_args()
    if len(a.player) != 3:
        raise SystemExit("exactly three --player devices (EAST, NORTH, WEST)")
    if len(a.watcher) > 2:
        raise SystemExit("at most two --watcher devices")
    # Nicknames may not read as a seat or a role (Nickname.RESERVED: "HOST", "EAST", 房主 …). ASCII
    # keeps them independent of how `am instrument -e` passes non-ASCII text.
    host = Device(a.host, "host", "Amei")
    seats = ["EAST", "NORTH", "WEST"]
    players = [Device(s, "player", ["Zhou", "Chen", "Lily"][i], seats[i]) for i, s in enumerate(a.player)]
    watchers = [Device(s, "watcher", f"Kan{i + 1}") for i, s in enumerate(a.watcher)]
    joiners = players + watchers
    devices = [host] + joiners
    if len({d.endpoint for d in devices}) != len(devices):
        raise SystemExit("each device spec must name a different device (serial, and @PORT for another adb server)")
    if any(d.remote for d in joiners) and not a.host_address:
        raise SystemExit("devices on another adb server need --host-address (this machine's LAN address) for the relay")
    if host.remote:
        raise SystemExit("run the script on the machine whose adb server has the host device")
    restart = next((p for p in players if p.seat == a.restart_seat), None) if a.restart_seat else None
    if restart and a.restart_mode == "emulator" and (restart.remote or not a.restart_avd or not restart.serial.startswith("emulator-")):
        raise SystemExit("--restart-mode emulator needs a local emulator and --restart-avd")

    stamp = dt.datetime.now().strftime("%Y-%m-%d-%H%M%S")
    logdir = os.path.join(REPO, "build", "e2e", stamp)
    os.makedirs(logdir, exist_ok=True)
    t0 = time.time()
    timings = {}
    record = {
        "schema": 1,
        "kind": "emulator end-to-end run (software part of HP-2); NOT an HP-2 pass",
        "started": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
        "commit": subprocess.run(["git", "-C", REPO, "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip(),
        "workingTreeDirty": bool(subprocess.run(["git", "-C", REPO, "status", "--porcelain"], capture_output=True, text=True).stdout.strip()),
        "machine": {"os": f"macOS {platform.mac_ver()[0]}", "arch": platform.machine()},
        "options": {k: v for k, v in vars(a).items() if k not in ("apk", "test_apk", "out")},
        "notes": a.note,
        "checks": {},
        "devices": {},
    }
    relay = None
    forwards = []
    outcome = "FAIL"
    reason = None
    try:
        # 1. Install, clear, describe.
        for d in devices:
            d.adb("wait-for-device", timeout=300)
            if not a.skip_install:
                d.adb("install", "-r", "-t", a.apk, timeout=300)
                d.adb("install", "-r", "-t", a.test_apk, timeout=300)
            d.shell(f"pm clear {PKG}")
            d.shell("svc power stayon true", check=False)
            d.info = device_info(d)
        timings["installed_s"] = round(time.time() - t0, 1)
        log(f"installed on {len(devices)} devices")

        common = {"e2e.timeoutMin": a.timeout_min, "e2e.deals": a.deals}
        # 2. Host.
        host.start_agent(common, logdir, "run")
        st = wait("the host's room and port", 120, lambda: (s := host.read_json("status.json")) and s["hook"].get("listenPort") and s)
        port = st["hook"]["listenPort"]
        fwd = host.adb("forward", "tcp:0", f"tcp:{port}").strip()
        local_port = int(fwd)
        forwards.append((host, f"tcp:{local_port}"))
        log(f"host listens on {port}; forwarded to 127.0.0.1:{local_port}")
        if a.host_address and any(d.remote for d in joiners):
            relay = Relay(a.host_address, a.relay_port, local_port, a.relay_allow)
            log(f"relay on {a.host_address}:{a.relay_port}")

        def wire(d):
            if d.remote:
                d.dial = (a.host_address, a.relay_port)
            elif a.local_net == "emu-host":
                d.dial = ("10.0.2.2", local_port)
            else:
                d.adb("reverse", f"tcp:{local_port}", f"tcp:{local_port}")
                d.dial = ("127.0.0.1", local_port)

        # 3. Joiners: look the room up by address, compare codes, approve.
        codes = []
        for d in joiners:
            wire(d)
            extra = dict(common, **{"e2e.host": d.dial[0], "e2e.port": d.dial[1]})
            if d.seat:
                extra["e2e.seat"] = d.seat
            d.start_agent(extra, logdir, "run")
            s = wait(f"{d.name} waiting for approval", 120, lambda: (x := d.read_json("status.json")) and x["hook"].get("joinCode") and x["hook"].get("joinPhase") == "AWAITING_APPROVAL" and x)
            code = s["hook"]["joinCode"]
            pending = wait(f"the host to show {d.name}'s request", 60,
                           lambda: next((p for p in (host.read_json("status.json") or {"hook": {"pending": []}})["hook"]["pending"] if p["code"] == code), None))
            same = pending["code"] == code and pending["nickname"] == d.nick
            codes.append({"device": d.name, "joinerCode": code, "hostShows": pending["code"], "nickname": pending["nickname"], "match": same})
            if not same:
                raise Failure(f"codes differ for {d.name}: joiner {code}, host {pending}")
            host.command(f"approve-{code}")
            wait(f"{d.name} admitted", 60, lambda: (x := d.read_json("status.json")) and x["hook"].get("joinPhase") == "ADMITTED")
            check_agents_alive(devices)
            log(f"{d.name} admitted after code {code} matched on both screens")
        record["checks"]["codeComparisons"] = codes
        timings["all_admitted_s"] = round(time.time() - t0, 1)

        # 4. Start.
        host.command("start")
        wait("deal 1 on every player", 120, lambda: all(((x := p.read_json("status.json")) or {}).get("deal", 0) >= 1 for p in [host] + players))
        timings["match_started_s"] = round(time.time() - t0, 1)
        log("match started")

        # 5. Play; restart one player mid-match.
        restarted = False
        restart_record = None
        deadline = time.time() + a.timeout_min * 60
        while True:
            if time.time() > deadline:
                raise Failure("the match did not finish within the time limit")
            check_agents_alive(devices)
            hs = host.read_json("status.json") or {}
            if restart and not restarted and hs.get("deal", 0) > a.restart_after_deal and hs.get("phase") == "PLAYING":
                restart_record = do_restart(a, restart, host, common, logdir, t0)
                restarted = True
            statuses = [d.read_json("status.json") or {} for d in devices]
            if all(s.get("done") for s in statuses):
                break
            time.sleep(2)
        timings["match_finished_s"] = round(time.time() - t0, 1)
        log("every device finished playing")

        # 6. Collect, then end the table.
        results = {}
        samples = {}
        for d in devices:
            results[d.name] = wait(f"{d.name} result", 60, lambda: d.read_json("result.json"))
            samples[d.name] = [json.loads(line) for line in (d.read("samples.jsonl") or "").splitlines() if line.strip()]
        for d in joiners:
            d.command("finish")
        host.command("finish")
        record["agentsExit"], record["agentsTimedOut"] = finish_agents(devices, 90)

        # 7. Checks.
        checks = record["checks"]
        finals = {n: r["final"] for n, r in results.items()}
        reference = finals[host.name]
        checks["finalResultIdentical"] = all(f == reference for f in finals.values())
        checks["finalResult"] = reference
        deal_maps = {n: {x["deal"]: x for x in r["deals"]} for n, r in results.items()}
        disagreements = []
        all_deals = sorted({k for m in deal_maps.values() for k in m})
        for k in all_deals:
            seen = {n: m[k] for n, m in deal_maps.items() if k in m}
            if len({json.dumps(v, sort_keys=True) for v in seen.values()}) > 1:
                disagreements.append({"deal": k, "seen": seen})
        checks["dealsPlayed"] = len(all_deals)
        checks["dealResultsAgree"] = not disagreements
        checks["dealDisagreements"] = disagreements
        checks["violations"] = {n: r["violations"] for n, r in results.items()}
        checks["rejoin"] = restart_record
        if restart_record is not None:
            report = results[restart.name].get("rejoin")
            restart_record["driverReport"] = report
            # The driver's own account must agree: pinned ticket matched, never asked to wait for approval, same seat.
            after = results[restart.name]
            # Deal results the returning player's new driver saw: the first is the result already on the
            # table when it came back, so two or more means at least one deal settled after the rejoin.
            restart_record["dealsSettledAfterRejoin"] = len(after["deals"])
            restart_record["actionsAfterRejoin"] = after.get("actions")
            restart_record["ok"] = bool(
                restart_record.get("ok") and report and report.get("ticketPinnedKeyMatched")
                and not report.get("sawApprovalPrompt") and report.get("seat") == restart.seat
                and len(after["deals"]) >= 2 and (after.get("actions") or 0) > 0
            )
        checks["spectatorPrivacy"] = privacy(players + [host], watchers, samples, results)
        record["samples"] = {n: len(s) for n, s in samples.items()}
        record["results"] = results

        failures = []
        if not checks["finalResultIdentical"]:
            failures.append("final results differ between devices")
        if not checks["dealResultsAgree"]:
            failures.append("deal results differ between devices")
        if any(checks["violations"].values()):
            failures.append("a driver recorded a violation")
        if reference.get("phase") != "OVER" and a.deals == 0:
            failures.append("the match did not reach its end")
        if restart and not (restart_record and restart_record.get("ok")):
            failures.append("the restarted player did not rejoin by ticket")
        failures += privacy_failures(checks["spectatorPrivacy"])
        if record["agentsTimedOut"]:
            failures.append(f"a driver did not finish in time and was killed ({', '.join(record['agentsTimedOut'])})")
        if any(code != 0 for code in record["agentsExit"].values()):
            failures.append("a driver did not exit cleanly")
        record["agentResults"] = instrumentation_problems(devices)
        if record["agentResults"]:
            failures.append("a driver's test did not pass (" + "; ".join(f"{n}: {p}" for n, p in record["agentResults"].items()) + ")")
        outcome = "PASS" if not failures else "FAIL"
        reason = "; ".join(failures) or None
    except Failure as e:
        reason = str(e)
        log(f"FAILED: {e}")
    except (subprocess.SubprocessError, OSError) as e:
        # An adb timeout or a socket error is an operational failure: record it rather than escape with reason null.
        reason = f"{type(e).__name__}: {e}"
        log(f"FAILED: {reason}")
    finally:
        cleanup_errors = []
        try:
            cleanup_errors = cleanup(forwards, joiners, devices)
            if relay:
                record["relayConnections"] = relay.connections
                record["relayRejected"] = relay.rejected
                record["relayTargetErrors"] = relay.target_errors
                relay.close()
        finally:
            # The report is written whatever cleanup did: losing it would lose the failure it records.
            if cleanup_errors:
                record["cleanupErrors"] = cleanup_errors
            timings["total_s"] = round(time.time() - t0, 1)
            record["timings"] = timings
            record["outcome"] = outcome
            record["reason"] = reason
            record["devices"] = {d.name: {"serial": d.serial, "remoteAdb": d.remote, "dial": d.dial, **d.info} for d in devices}
            record["logs"] = os.path.relpath(logdir, REPO)
            write_outputs(a.out, stamp, record, lan_placeholders(a.host_address, a.relay_allow))
    return 0 if outcome == "PASS" else 1


def cleanup(forwards, joiners, devices):
    """Removes port forwards and reverses and kills drivers. Each step is tried on its own: one adb
    timeout must not skip the rest, nor the report. Returns the errors met."""
    steps = [(f"{d.name}: forward --remove {spec}", lambda d=d, spec=spec: d.adb("forward", "--remove", spec, check=False))
             for d, spec in forwards]
    steps += [(f"{d.name}: reverse --remove tcp:{d.dial[1]}", lambda d=d: d.adb("reverse", "--remove", f"tcp:{d.dial[1]}", check=False))
              for d in joiners if d.dial and d.dial[0] == "127.0.0.1"]
    steps += [(f"{d.name}: kill driver", lambda d=d: d.proc.kill()) for d in devices if d.proc and d.proc.poll() is None]
    errors = []
    for what, step in steps:
        try:
            step()
        except (subprocess.SubprocessError, OSError, Failure) as e:
            errors.append(f"{what}: {e}")
            log(f"cleanup: {what} failed: {e}")
    return errors


def do_restart(a, d, host, common, logdir, t0):
    """Kills one player mid-match, restarts it, and has its driver rejoin by ticket."""
    rec = {"device": d.name, "mode": a.restart_mode}
    before = d.read_json("status.json") or {}
    rec["atDeal"] = before.get("deal")
    log(f"killing {d.name} ({a.restart_mode}) at deal {before.get('deal')}")
    killed = time.time()
    if a.restart_mode == "emulator":
        subprocess.run([os.path.join(REPO, "tools/e2e/emulators.sh"), "stop", d.serial], check=True)
        port = d.serial.split("-")[1]
        subprocess.run([os.path.join(REPO, "tools/e2e/emulators.sh"), "restart", f"{a.restart_avd}:{port}"], check=True)
        if d.dial and d.dial[0] == "127.0.0.1":
            d.adb("reverse", f"tcp:{d.dial[1]}", f"tcp:{d.dial[1]}")
    else:
        d.shell(f"am force-stop {PKG}")
    if d.proc:
        try:
            d.proc.wait(timeout=60)
        except subprocess.TimeoutExpired:
            d.proc.kill()
    rec["downSeconds"] = round(time.time() - killed, 1)
    if a.restart_mode == "emulator":
        rec["settleSeconds"] = settle(d)
    # The status written before the kill still says ADMITTED: remove it, so only the new driver's counts.
    d.shell(f"run-as {PKG} rm -f files/e2e/status.json")
    if d.read("status.json") is not None:
        raise Failure(f"{d.name}: could not remove the stale status before rejoining")
    # While it is away, the host must never be asked to approve it again.
    extra = dict(common, **{"e2e.host": d.dial[0], "e2e.port": d.dial[1], "e2e.seat": d.seat, "e2e.mode": "rejoin"})
    d.shell("logcat -c", check=False)
    d.start_agent(extra, logdir, "rejoin")
    approval_seen = []
    rec["startupAnrRetries"] = 0

    def back():
        hs = host.read_json("status.json") or {"hook": {"pending": []}}
        approval_seen.extend(p for p in hs["hook"]["pending"] if p["nickname"] == d.nick)
        s = d.read_json("status.json") or {}
        return s.get("mode") == "rejoin" and s.get("hook", {}).get("joinPhase") == "ADMITTED" and s
    try:
        try:
            wait(f"{d.name} back at its seat", 180, back)
        except Failure:
            # Only one failure is retried, once: Android killing the app because its process did not
            # finish starting (an ANR on a device still busy after a cold boot). Anything else fails.
            if not startup_anr(d):
                raise
            rec["startupAnrRetries"] = 1
            log(f"{d.name}: the app was killed for a startup ANR after the cold boot; starting the driver once more")
            settle(d)
            d.shell("logcat -c", check=False)
            d.start_agent(extra, logdir, "rejoin-retry")
            wait(f"{d.name} back at its seat", 180, back)
        rec["ok"] = not approval_seen
    except Failure as e:
        rec["ok"] = False
        rec["error"] = str(e)
    rec["hostWasAskedToApprove"] = bool(approval_seen)
    rec["secondsToRejoin"] = round(time.time() - killed, 1)
    rec["atSeconds"] = round(killed - t0, 1)
    log(f"{d.name} rejoined: {rec}")
    return rec


def settle(d, limit=60):
    """Waits (at most [limit] s) until a just-booted device's 1-minute load average is below twice its core count."""
    start = time.time()
    cores = int(d.shell("nproc", check=False).strip() or "2")
    while time.time() - start < limit:
        load = float((d.shell("cat /proc/loadavg", check=False).split() or ["99"])[0])
        if load < 2 * cores:
            break
        time.sleep(5)
    return round(time.time() - start, 1)


def startup_anr(d):
    text = d.adb("logcat", "-d", "-s", "ActivityManager:E", check=False, timeout=60)
    return f"ANR in {PKG}" in text and "failed to complete startup" in text


def privacy(players, watchers, samples, results):
    """For every revision a watcher saw, no card it saw (state or screen) was in a player's hand then.
    A revision is compared only when every player's hand at it was sampled: with one hand missing, a
    card leaked from that hand would go unseen. Such revisions are counted as unverified."""
    hands = {}
    sampled = {}
    for p in players:
        for s in samples.get(p.name, []):
            key = (s["deal"], s["rev"])
            hands.setdefault(key, set()).update(s["hand"])
            sampled.setdefault(key, set()).add(p.name)
    everyone = {p.name for p in players}
    leaks = []
    per = {}
    for w in watchers:
        pairs = 0
        unverified = 0
        for s in samples.get(w.name, []):
            key = (s["deal"], s["rev"])
            if sampled.get(key) != everyone:
                unverified += 1
            else:
                pairs += 1
                seen = set(s["visible"]) | set(s["screen"])
                leaked = seen & hands[key]
                if leaked:
                    leaks.append({"watcher": w.name, "deal": key[0], "rev": key[1], "cards": sorted(leaked)})
        per[w.name] = {
            "samples": len(samples.get(w.name, [])),
            "pairsCompared": pairs,
            "unverified": unverified,
            "semanticsChecks": results[w.name].get("semanticsChecks", 0),
        }
    return {
        "watchers": len(watchers),
        "watcherSamples": sum(x["samples"] for x in per.values()),
        "pairsCompared": sum(x["pairsCompared"] for x in per.values()),
        "unverified": sum(x["unverified"] for x in per.values()),
        "semanticsChecks": sum(x["semanticsChecks"] for x in per.values()),
        "perWatcher": per,
        "leaks": leaks,
    }


def privacy_failures(sp):
    """Failures of the spectator privacy check: any leak, or any watcher that was not actually checked.
    Totals alone could pass with one watcher fully checked and another never sampled."""
    out = ["a watcher saw a card in a player's hand"] if sp["leaks"] else []
    for name, x in sp["perWatcher"].items():
        missing = [k for k in ("samples", "pairsCompared", "semanticsChecks") if not x[k]]
        if missing:
            out.append(f"spectator privacy compared nothing for {name} (zero {', '.join(missing)})")
    return out


def instrumentation_problem(text):
    """Why a driver's `am instrument -w -r` output is not a passing run, or None. adb exits 0 even when
    the test failed or the process crashed, so the exit code alone proves nothing."""
    lines = text.splitlines()
    for line in lines:
        if line.startswith("INSTRUMENTATION_RESULT: shortMsg="):
            return line.split("=", 1)[1].strip() or "instrumentation aborted"
        if line.startswith("INSTRUMENTATION_STATUS_CODE: -"):
            return f"a test did not pass ({line.strip()})"
    if not any(re.fullmatch(r"OK \(\d+ tests?\)", line.strip()) for line in lines):
        return "no OK summary"
    if "INSTRUMENTATION_CODE: -1" not in (line.strip() for line in lines):
        return "no successful INSTRUMENTATION_CODE"
    return None


def instrumentation_problems(devices):
    """{name: problem} for every device whose current driver log does not record a passing run."""
    out = {}
    for d in devices:
        try:
            with open(d.log_path, encoding="utf-8", errors="replace") as f:
                text = f.read()
        except (OSError, TypeError) as e:
            out[d.name] = f"driver log unreadable: {e}"
            continue
        problem = instrumentation_problem(text)
        if problem:
            out[d.name] = problem
    return out


def finish_agents(devices, timeout):
    """Waits for every driver to exit; kills and reaps one that does not, so its exit is never None.
    Returns ({name: exit code}, [names that timed out])."""
    timed_out = []
    for d in devices:
        try:
            d.proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out.append(d.name)
            d.proc.kill()
            d.proc.wait()
    return {d.name: d.agent_exit() for d in devices}, timed_out


def lan_placeholders(host_address, relay_allow):
    """Placeholders for the LAN addresses of a cross-machine run: evidence files are committed, private addresses are not."""
    mapping = {}
    if host_address:
        mapping[host_address] = "<host-mac-lan-address>"
    for i, addr in enumerate(x for x in relay_allow if x not in mapping):
        mapping[addr] = f"<remote-mac-lan-address-{i + 1}>"
    return mapping


def redact(text, mapping):
    # Whole addresses only: 192.0.2.15 must not match the start of 192.0.2.150.
    for addr, placeholder in mapping.items():
        text = re.sub(r"(?<![\w.])" + re.escape(addr) + r"(?![\w])", placeholder, text)
    return text


def write_outputs(out, stamp, record, redactions):
    os.makedirs(out, exist_ok=True)
    base = os.path.join(out, f"HP-2-emulator-run-{stamp}")
    if redactions:
        record = json.loads(redact(json.dumps(record, ensure_ascii=False), redactions))
        record["redacted"] = sorted(redactions.values())
    with open(base + ".json", "w") as f:
        json.dump(record, f, ensure_ascii=False, indent=2)
        f.write("\n")
    template = REPORT_TEMPLATE
    c = record["checks"]
    sp = c.get("spectatorPrivacy") or {}
    rj = c.get("rejoin") or {}
    devices = "\n".join(
        f"| {n} | {v['serial']} | {v.get('ro.product.model', '')} | {v.get('ro.build.version.release', '')} (SDK {v.get('ro.build.version.sdk', '')}) | "
        f"{'emulator' if v.get('ro.kernel.qemu') == '1' else 'device'} | {'remote adb' if v['remoteAdb'] else 'local adb'} | {':'.join(map(str, v['dial'])) if v['dial'] else '-'} |"
        for n, v in record["devices"].items()
    )
    final = c.get("finalResult") or {}
    values = {
        "STAMP": stamp,
        "STARTED": record["started"],
        "COMMIT": record["commit"] + (" (working tree had uncommitted changes)" if record["workingTreeDirty"] else ""),
        "MACHINE": f"{record['machine']['os']} {record['machine']['arch']}",
        "OUTCOME": record["outcome"],
        "REASON": record["reason"] or "-",
        "DEVICES": devices,
        "CODES": "\n".join(f"| {x['device']} | {x['joinerCode']} | {x['hostShows']} | {'yes' if x['match'] else 'NO'} |" for x in c.get("codeComparisons", [])) or "| - | - | - | - |",
        "DEALS": str(c.get("dealsPlayed", "-")),
        "FINAL": json.dumps(final, ensure_ascii=False),
        "IDENTICAL": str(c.get("finalResultIdentical", "-")),
        "DEAL_AGREE": str(c.get("dealResultsAgree", "-")),
        "REJOIN": json.dumps(rj, ensure_ascii=False) if rj else "not run",
        "PRIVACY": json.dumps({k: v for k, v in sp.items() if k != "leaks"}, ensure_ascii=False) + f", leaks: {len(sp.get('leaks', []))}" if sp else "-",
        "VIOLATIONS": json.dumps(c.get("violations", {}), ensure_ascii=False),
        "TIMINGS": "\n".join(f"| {k} | {v} |" for k, v in record["timings"].items()),
        "NOTES": "\n".join(f"- {n}" for n in record["notes"]) or "- none",
        "JSON": os.path.basename(base + ".json"),
        "LOGS": record["logs"],
    }
    for k, v in values.items():
        template = template.replace("{{" + k + "}}", v)
    with open(base + ".md", "w") as f:
        f.write(template)
    log(f"wrote {base}.json and .md: {record['outcome']}")


if __name__ == "__main__":
    sys.exit(main())
