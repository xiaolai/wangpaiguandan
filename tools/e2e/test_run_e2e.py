"""Tests for run_e2e.py's report writing. Run: python3 -I -m unittest discover -s tools/e2e"""

import io
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_e2e  # noqa: E402


def record(host_address, remote):
    return {
        "started": "2026-10-16T00:00:00+08:00",
        "commit": "abc1234",
        "workingTreeDirty": False,
        "machine": {"os": "macOS 27", "arch": "arm64"},
        "options": {"host_address": host_address, "relay_allow": [remote]},
        "notes": [f"watchers dialled {host_address}:47000"],
        "checks": {},
        "timings": {"total_s": 1.0},
        "outcome": "FAIL",
        "reason": f"connection from {remote} refused",
        "devices": {
            "watcher(emulator-5554)": {"serial": "emulator-5554", "remoteAdb": True, "dial": [host_address, 47000]},
        },
        "logs": "build/e2e/x",
    }


class RedactionTest(unittest.TestCase):
    def test_lan_addresses_never_reach_the_evidence_files(self):
        host, remote = "192.0.2.15", "192.0.2.150"
        with tempfile.TemporaryDirectory() as out:
            run_e2e.write_outputs(out, "stamp", record(host, remote), run_e2e.lan_placeholders(host, [remote]))
            for ext in ("json", "md"):
                with open(os.path.join(out, f"HP-2-emulator-run-stamp.{ext}")) as f:
                    text = f.read()
                self.assertNotIn(host, text, ext)
                self.assertNotIn(remote, text, ext)
                self.assertIn("<host-mac-lan-address>", text, ext)
                self.assertIn("<remote-mac-lan-address-1>", text, ext)
            with open(os.path.join(out, "HP-2-emulator-run-stamp.json")) as f:
                rec = json.load(f)
            self.assertEqual(rec["devices"]["watcher(emulator-5554)"]["dial"], ["<host-mac-lan-address>", 47000])

    def test_an_address_is_not_replaced_inside_a_longer_one(self):
        # 192.0.2.15 is a prefix of 192.0.2.150: the longer address gets its own placeholder.
        mapping = run_e2e.lan_placeholders("192.0.2.15", ["192.0.2.150"])
        self.assertEqual(run_e2e.redact("192.0.2.150 and 192.0.2.15:47000", mapping),
                         "<remote-mac-lan-address-1> and <host-mac-lan-address>:47000")

    def test_same_machine_runs_redact_nothing(self):
        self.assertEqual(run_e2e.lan_placeholders(None, []), {})
        self.assertEqual(run_e2e.redact("127.0.0.1:58591", {}), "127.0.0.1:58591")


class RelayTest(unittest.TestCase):
    def echo_server(self):
        srv = socket.create_server(("127.0.0.1", 0))
        self.addCleanup(srv.close)

        def serve():
            while True:
                try:
                    c, _ = srv.accept()
                except OSError:
                    return
                with c:
                    while data := c.recv(4096):
                        c.sendall(data)
        threading.Thread(target=serve, daemon=True).start()
        return srv.getsockname()[1]

    def wait_for(self, poll):
        end = time.time() + 5
        while time.time() < end and not poll():
            time.sleep(0.02)
        return poll()

    def test_relays_to_the_target_port(self):
        relay = run_e2e.Relay("127.0.0.1", 0, self.echo_server(), [])
        self.addCleanup(relay.close)
        with socket.create_connection(("127.0.0.1", relay.port), timeout=5) as c:
            c.sendall(b"ping")
            self.assertEqual(c.recv(4), b"ping")
        self.assertEqual((relay.connections, relay.rejected), (1, 0))

    def test_a_link_quiet_for_longer_than_the_connect_timeout_stays_up(self):
        relay = run_e2e.Relay("127.0.0.1", 0, self.echo_server(), [])
        relay.CONNECT_TIMEOUT = 0.2
        self.addCleanup(relay.close)
        with socket.create_connection(("127.0.0.1", relay.port), timeout=5) as c:
            c.sendall(b"a")
            self.assertEqual(c.recv(1), b"a")
            time.sleep(0.6)
            c.sendall(b"b")
            self.assertEqual(c.recv(1), b"b")

    def test_a_source_outside_the_allow_list_is_refused_and_counted(self):
        relay = run_e2e.Relay("127.0.0.1", 0, self.echo_server(), ["192.0.2.1"])
        self.addCleanup(relay.close)
        with socket.create_connection(("127.0.0.1", relay.port), timeout=5) as c:
            c.settimeout(5)
            self.assertEqual(c.recv(4), b"")  # closed without relaying
        self.assertTrue(self.wait_for(lambda: relay.rejected == 1))
        self.assertEqual(relay.connections, 0)


class DeviceIdentityTest(unittest.TestCase):
    def test_the_same_serial_on_two_adb_servers_is_two_devices(self):
        # Audit #72: watchers emulator-5554 here and emulator-5554 on another server shared a result key and log file.
        local = run_e2e.Device("emulator-5554", "watcher", "Kan1")
        remote = run_e2e.Device("emulator-5554@5038", "watcher", "Kan2")
        self.assertNotEqual(local.name, remote.name)
        self.assertEqual(remote.name, "watcher(emulator-5554@5038)")
        self.assertNotEqual(local.log_file("/logs", "run"), remote.log_file("/logs", "run"))


class ArgumentTest(unittest.TestCase):
    BASE = ["--host", "emulator-5554", "--player", "a", "--player", "b", "--player", "c"]

    def test_a_misspelt_restart_seat_is_refused(self):
        # Audit #75: "east" matched no seat and silently turned the restart check off.
        with mock.patch("sys.stderr", io.StringIO()), self.assertRaises(SystemExit):
            run_e2e.parse_args(self.BASE + ["--restart-seat", "east"])

    def test_the_seats_and_empty_are_accepted(self):
        for seat in ("EAST", "NORTH", "WEST", ""):
            self.assertEqual(run_e2e.parse_args(self.BASE + ["--restart-seat", seat]).restart_seat, seat)


class PrivacyCoverageTest(unittest.TestCase):
    def test_every_watcher_must_be_checked(self):
        # Audit #73: totals passed with one watcher checked and the other never sampled.
        host = run_e2e.Device("emulator-5554", "host", "Amei")
        w1 = run_e2e.Device("emulator-5556", "watcher", "Kan1")
        w2 = run_e2e.Device("emulator-5558", "watcher", "Kan2")
        samples = {
            host.name: [{"deal": 1, "rev": 3, "hand": ["S3"]}],
            w1.name: [{"deal": 1, "rev": 3, "visible": ["H4"], "screen": []}],
            w2.name: [],
        }
        results = {w1.name: {"semanticsChecks": 4}, w2.name: {"semanticsChecks": 0}}
        sp = run_e2e.privacy([host], [w1, w2], samples, results)
        self.assertEqual((sp["pairsCompared"], sp["leaks"]), (1, []))
        failures = run_e2e.privacy_failures(sp)
        self.assertEqual(len(failures), 1, failures)
        self.assertIn(w2.name, failures[0])
        self.assertIn("samples, pairsCompared, semanticsChecks", failures[0])

    def test_two_checked_watchers_pass(self):
        host = run_e2e.Device("emulator-5554", "host", "Amei")
        ws = [run_e2e.Device(f"emulator-555{6 + 2 * i}", "watcher", f"Kan{i + 1}") for i in range(2)]
        samples = {host.name: [{"deal": 1, "rev": 3, "hand": ["S3"]}]}
        samples |= {w.name: [{"deal": 1, "rev": 3, "visible": [], "screen": ["H4"]}] for w in ws}
        sp = run_e2e.privacy([host], ws, samples, {w.name: {"semanticsChecks": 1} for w in ws})
        self.assertEqual(run_e2e.privacy_failures(sp), [])


class PrivacyCompletenessTest(unittest.TestCase):
    def test_a_revision_with_a_player_unsampled_is_unverified_not_compared(self):
        # Round 3 #61: only the host's hand was known at (1, 3); a card in EAST's unsampled hand could leak unseen.
        host = run_e2e.Device("emulator-5554", "host", "Amei")
        east = run_e2e.Device("emulator-5556", "player", "Zhou", "EAST")
        w = run_e2e.Device("emulator-5558", "watcher", "Kan1")
        samples = {
            host.name: [{"deal": 1, "rev": 3, "hand": ["S3"]}, {"deal": 1, "rev": 4, "hand": ["S3"]}],
            east.name: [{"deal": 1, "rev": 4, "hand": ["H5"]}],
            w.name: [{"deal": 1, "rev": 3, "visible": ["H5"], "screen": []}, {"deal": 1, "rev": 4, "visible": [], "screen": []}],
        }
        sp = run_e2e.privacy([host, east], [w], samples, {w.name: {"semanticsChecks": 1}})
        self.assertEqual(sp["pairsCompared"], 1)
        self.assertEqual(sp["unverified"], 1)
        self.assertEqual(sp["perWatcher"][w.name]["unverified"], 1)

    def test_only_incomplete_revisions_fail_as_nothing_compared(self):
        host = run_e2e.Device("emulator-5554", "host", "Amei")
        east = run_e2e.Device("emulator-5556", "player", "Zhou", "EAST")
        w = run_e2e.Device("emulator-5558", "watcher", "Kan1")
        samples = {
            host.name: [{"deal": 1, "rev": 3, "hand": ["S3"]}],
            w.name: [{"deal": 1, "rev": 3, "visible": ["H5"], "screen": []}],
        }
        sp = run_e2e.privacy([host, east], [w], samples, {w.name: {"semanticsChecks": 1}})
        failures = run_e2e.privacy_failures(sp)
        self.assertEqual(len(failures), 1, failures)
        self.assertIn("pairsCompared", failures[0])


OK_LOG = """INSTRUMENTATION_STATUS: test=agent
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: test=agent
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_RESULT: stream=

Time: 812.4

OK (1 test)


INSTRUMENTATION_CODE: -1
"""


class InstrumentationResultTest(unittest.TestCase):
    # Round 3 #62: `am instrument -w` exits 0 when the test failed or the process crashed.
    def test_a_passing_run_has_no_problem(self):
        self.assertIsNone(run_e2e.instrumentation_problem(OK_LOG))

    def test_a_crash_is_a_problem(self):
        text = "INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n"
        self.assertIn("Process crashed", run_e2e.instrumentation_problem(text))

    def test_a_failed_test_is_a_problem(self):
        text = OK_LOG.replace("STATUS_CODE: 0", "STATUS_CODE: -2").replace("OK (1 test)", "FAILURES!!!\nTests run: 1,  Failures: 1")
        self.assertIsNotNone(run_e2e.instrumentation_problem(text))

    def test_a_truncated_log_is_a_problem(self):
        self.assertIsNotNone(run_e2e.instrumentation_problem(OK_LOG.split("OK (1 test)")[0]))
        self.assertIsNotNone(run_e2e.instrumentation_problem(""))

    def test_each_driver_log_is_checked(self):
        with tempfile.TemporaryDirectory() as logs:
            good, bad = run_e2e.Device("emulator-5554", "host", "Amei"), run_e2e.Device("emulator-5556", "watcher", "Kan1")
            for d, text in ((good, OK_LOG), (bad, "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n")):
                d.log_path = os.path.join(logs, d.name + ".log")
                with open(d.log_path, "w") as f:
                    f.write(text)
            problems = run_e2e.instrumentation_problems([good, bad])
            self.assertEqual(list(problems), [bad.name])


class OperationalErrorTest(unittest.TestCase):
    def test_an_adb_timeout_is_reported_with_its_reason(self):
        # Round 3 #63: TimeoutExpired escaped main(); the report said FAIL with reason null.
        # --out is passed explicitly: this test replaces REPO with a temporary directory, and the
        # default evidence directory is resolved from REPO only when a report is written.
        args = run_e2e.parse_args(ArgumentTest.BASE + ["--skip-install", "--restart-seat", "", "--out", "unused"])
        written = {}
        with tempfile.TemporaryDirectory() as repo, \
                mock.patch.object(run_e2e, "REPO", repo), \
                mock.patch.object(run_e2e, "parse_args", return_value=args), \
                mock.patch.object(run_e2e.Device, "adb", side_effect=subprocess.TimeoutExpired("adb", 300)), \
                mock.patch.object(run_e2e, "write_outputs", lambda out, stamp, rec, red: written.update(rec)), \
                mock.patch("sys.stdout", io.StringIO()):
            self.assertEqual(run_e2e.main(), 1)
        self.assertEqual(written["outcome"], "FAIL")
        self.assertIn("TimeoutExpired", written["reason"])


class FinishAgentsTest(unittest.TestCase):
    def test_a_timed_out_driver_is_reaped_and_reported(self):
        # Audit #76: a killed but unreaped driver polled None, which counted as a clean exit.
        d = run_e2e.Device("emulator-5554", "host", "Amei")
        d.proc = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        self.addCleanup(lambda: d.proc.poll() is None and d.proc.kill())
        exits, timed_out = run_e2e.finish_agents([d], 0.2)
        self.assertEqual(timed_out, [d.name])
        self.assertIsNotNone(exits[d.name])
        self.assertNotEqual(exits[d.name], 0)


class CleanupTest(unittest.TestCase):
    def test_one_failing_step_does_not_skip_the_others(self):
        # Audit #77: a forward-removal timeout raised out of `finally` before the report was written.
        calls = []

        class Fake(run_e2e.Device):
            def adb(self, *args, check=True, timeout=120):
                calls.append(args)
                if args[0] == "forward":
                    raise subprocess.TimeoutExpired("adb", timeout)
                return ""

        host, joiner = Fake("emulator-5554", "host", "Amei"), Fake("emulator-5556", "player", "Zhou", "EAST")
        joiner.dial = ("127.0.0.1", 40000)
        with mock.patch("sys.stdout", io.StringIO()):
            errors = run_e2e.cleanup([(host, "tcp:40000")], [joiner], [host, joiner])
        self.assertEqual(len(errors), 1, errors)
        self.assertIn("forward --remove", errors[0])
        self.assertIn(("reverse", "--remove", "tcp:40000"), calls)


if __name__ == "__main__":
    unittest.main()
