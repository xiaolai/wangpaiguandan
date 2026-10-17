# Multi-device end-to-end LAN run

Stands in for the **software part** of HP-2 (four players and two watchers over the LAN) on
emulators. It is not an HP-2 pass: no physical phone, no real Wi-Fi radio, no NSD between devices,
no traffic capture. Results go to `dev-docs/evidence/HP-2-emulator-run-<stamp>.{json,md}`.

What one run does:

1. Installs the debug app and its test APK on every device, clears app data.
2. Starts the on-device driver (`E2eAgentTest`, `app/src/androidTestDebug/`, debug builds only) on
   each device with `adb shell am instrument`. The driver runs the real app (production graph, LAN
   sockets, storage, identity) with MainActivity on screen.
3. The host opens a room. The script reads its port through the debug-only automation hook
   (`dev-docs/automation-hook.md`) and exposes it (below).
4. Each joiner finds the room by **join by address** (按地址加入, the production lookup) and asks
   to join. The script compares the six-digit code on the joiner with the one the host shows, and
   only then tells the host to approve.
5. The host fills no seat with AI (all four seats are human; AI stands in only while a player is
   away), everyone is ready, the host starts. Human seats are played by a seeded legal autoplayer
   (`ScriptedHuman`, through the table's view model). A full match is played unless `--deals N`.
6. Mid-match (after deal 1 by default) one player is killed and restarted: `--restart-mode
   emulator` shuts that emulator down and cold-boots it; `process` force-stops the app. Its driver
   then looks the room up again and must be re-admitted to the same seat by its stored ticket,
   pinned to the host key, without the host being asked to approve.
7. Watchers record every card their state and their screen (Compose semantics of the watch
   screen) show; afterwards each sample is compared with every player's hand at the same deal and
   revision. Any overlap is a leak.
8. Every device's final match result and every deal result it saw must be identical.

## Requirements

- Android SDK at `$ANDROID_SDK_ROOT` (default `/opt/homebrew/share/android-commandlinetools`),
  JDK 21 for Gradle, Python 3 (standard library only).
- `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`
- Memory: each headless emulator takes about 2.5–3 GB of host memory (2 GB guest). Six fit on a
  32 GB Mac only with heavy swapping; check `memory_pressure` first.

## Emulators on this machine

```sh
tools/e2e/emulators.sh create wpgd-a wpgd-b wpgd-c wpgd-d wpgd-e wpgd-f   # once; writable AVDs
tools/e2e/emulators.sh start wpgd-a:5560 wpgd-b:5562 wpgd-c:5564 wpgd-d:5566 wpgd-e:5568 wpgd-f:5570
python3 -I tools/e2e/run_e2e.py --host emulator-5560 \
  --player emulator-5562 --player emulator-5564 --player emulator-5566 \
  --watcher emulator-5568 --watcher emulator-5570 \
  --restart-seat EAST --restart-mode emulator --restart-avd wpgd-b
tools/e2e/emulators.sh stop-all     # always, also after a failed run
```

Writable AVDs, not `-read-only`: a restarted emulator must keep the app's data (its rejoin ticket),
as a phone that rebooted would.

## How the joiners reach the host

Each emulator sits behind its own NAT (guest 10.0.2.15), so neither NSD nor a direct connection
works between emulators. The script does this, and the same commands work by hand:

| Step | Command | Effect |
|---|---|---|
| Expose the host's room port on this Mac | `adb -s <host> forward tcp:0 tcp:<room port>` | prints a local port L; 127.0.0.1:L on this Mac reaches the room |
| Same machine, any device (default `--local-net reverse`) | `adb -s <joiner> reverse tcp:L tcp:L` | the joiner types address `127.0.0.1`, port `L` |
| Same machine, emulators only (`--local-net emu-host`) | none | the joiner types `10.0.2.2` (the emulator's alias of the host loopback), port `L` |
| Alternative to `adb forward` for an emulator host | `adb -s <host> emu redir add tcp:L:<room port>` | same as the forward, through the emulator console |
| Another machine (`--host-address`) | the script listens on `<this Mac's LAN address>:<relay port>` and relays to 127.0.0.1:L | the remote emulator types this Mac's LAN address and the relay port |

The room port is the one the host's lobby shows (or `listenPort` in the driver's status); it
changes every time a room is opened.

## Devices on other Macs

Device specs are `SERIAL` (this machine's adb server) or `SERIAL@PORT` (an adb server reachable on
127.0.0.1:PORT). `adb reverse` on a remote server would point at the remote Mac, not this one, so
remote devices always dial the relay.

**A. Drive everything from the host Mac (recommended).** The host emulator runs here; each remote
Mac's adb server is reached through an SSH tunnel:

```sh
ssh -N -L 5038:127.0.0.1:5037 <players-mac> &     # remote adb server as 127.0.0.1:5038
ssh -N -L 5039:127.0.0.1:5037 <watchers-mac> &
python3 -I tools/e2e/run_e2e.py --host emulator-5560 \
  --player emulator-5554@5038 --player emulator-5556@5038 --player emulator-5558@5038 \
  --watcher emulator-5554@5039 --watcher emulator-5556@5039 \
  --host-address <this Mac's LAN address> --relay-port 47000 \
  --relay-allow <players-mac LAN address> --relay-allow <watchers-mac LAN address> \
  --restart-seat EAST --restart-mode process
```

The emulators on the remote Macs reach `<this Mac's LAN address>:47000` through their own NAT; the
relay accepts only the listed source addresses. Allow incoming TCP on that port in this Mac's
firewall for the duration of the run. `--restart-mode emulator` works only for local emulators;
use `process` for remote ones (or restart the remote emulator by hand on that Mac).

**Start remote emulators from an SSH session that stays open for the whole run.** On macOS 27 a
process started over SSH can open connections to other machines on the LAN only while that SSH
session lives; once the session ends, every LAN address except the gateway answers `No route to
host` (most likely macOS Local Network privacy, with no one at the screen to allow it). Emulators
started with `emulators.sh start` from a session that then closed could not reach the relay; the
same emulators started from a session kept open could. Keep it open, for example:

```sh
ssh <watchers-mac> 'source ~/wpgd-env.sh; cd <repo>; tools/e2e/emulators.sh start wpgd-e:5554 wpgd-f:5556 </dev/null; while sleep 60; do :; done' &
```

The JSON record counts `relayConnections`, `relayRejected` (source not in `--relay-allow`) and
`relayTargetErrors` (the forwarded host port did not answer): zero connections and zero rejections
means nothing reached the relay at all.

**Host and players on a remote Mac too.** The host must be on the adb server of the machine running
the script, so with every device on two remote Macs (host and players on one, watchers on the other)
run the script on the players' Mac (copy the repository there, method B) and give it the watchers'
adb server. When that Mac cannot SSH to the other, chain the tunnel through the Mac you are at:

```sh
ssh -f -N -L 127.0.0.1:5039:127.0.0.1:5037 <watchers-mac>     # watchers' adb server here as 5039
ssh -f -N -R 127.0.0.1:5039:127.0.0.1:5039 <players-mac>      # ... and on the players' Mac as 5039
ssh <players-mac> 'cd wangpaiguandan && python3 -I tools/e2e/run_e2e.py --host emulator-5560 \
  --player emulator-5562 --player emulator-5564 --player emulator-5566 \
  --watcher emulator-5554@5039 --watcher emulator-5556@5039 \
  --host-address <players-mac LAN address> --relay-allow <watchers-mac LAN address> \
  --restart-seat EAST --restart-mode emulator --restart-avd wpgd-b'
```

LAN addresses given to `--host-address` and `--relay-allow` are written to the evidence files as
`<host-mac-lan-address>` and `<remote-mac-lan-address-N>`; the console log keeps the real ones.

**B. Run everything on one remote Mac.** Copy the repository and run the same-machine commands
there:

```sh
# tar, not macOS rsync (an earlier copy with it failed without saying so); NUL-separated names keep non-ASCII paths
git ls-files -z --cached --others --exclude-standard | COPYFILE_DISABLE=1 tar -c --null -T - -f - .git | ssh <mac> 'mkdir -p wangpaiguandan && tar -xf - -C wangpaiguandan'
ssh <mac> 'cd wangpaiguandan && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest && python3 -I tools/e2e/run_e2e.py ...'
```

## Output

`dev-docs/evidence/HP-2-emulator-run-<stamp>.json` (every check, timing, device and result) and
`.md` (the readable report). Driver logs stay under `build/e2e/<stamp>/`. The script exits 0 only
when every check passed. `--out <dir>` writes elsewhere (for trial runs).
