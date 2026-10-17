#!/usr/bin/env bash
# Headless emulators for the end-to-end run (tools/e2e/README.md). Local machine only.
#
#   emulators.sh create NAME...          writable AVDs from the pixel8 system image (once)
#   emulators.sh start NAME:PORT...      boot headless, wait until each has booted
#   emulators.sh restart NAME:PORT       cold-boot one again (same AVD, same serial emulator-PORT)
#   emulators.sh stop SERIAL...          shut down (adb emu kill) and wait until gone
#   emulators.sh stop-all                shut down every emulator this adb server sees
#
# Writable AVDs (not -read-only) so a restarted player keeps its app data and rejoin ticket, as a
# phone that rebooted would. Ports must be even, 5554-5682.
set -euo pipefail

sdk="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
image="${WPGD_E2E_IMAGE:-system-images;android-36.1;google_apis;arm64-v8a}"
ram="${WPGD_E2E_RAM_MB:-2048}"
adb="$sdk/platform-tools/adb"
emulator="$sdk/emulator/emulator"
avdmanager="$sdk/cmdline-tools/latest/bin/avdmanager"
logdir="${WPGD_E2E_LOGDIR:-${TMPDIR:-/tmp}/wpgd-e2e-emulators}"
mkdir -p "$logdir"

die() { echo "emulators.sh: $*" >&2; exit 1; }

boot_timeout="${WPGD_E2E_BOOT_TIMEOUT_S:-240}"
stop_timeout="${WPGD_E2E_STOP_TIMEOUT_S:-60}"
# Longest any single adb call may take; a call is also cut off at its caller's deadline.
call_limit="${WPGD_E2E_ADB_CALL_S:-30}"

# Runs adb for at most $1 seconds (macOS has no `timeout`), printing its stdout. A call still running
# at the limit is killed and fails (exit 124): a wedged device or adbd can never hang the script.
adb_within() {
  local limit="$1" out pid ticks=0 rc=0; shift
  [ "$limit" -ge 1 ] || limit=1
  [ "$limit" -le "$call_limit" ] || limit="$call_limit"
  out=$(mktemp "$logdir/adb.XXXXXX")
  "$adb" "$@" > "$out" 2>/dev/null &
  pid=$!
  while kill -0 "$pid" 2>/dev/null; do
    if [ "$ticks" -ge $((limit * 10)) ]; then
      kill -9 "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
      rm -f "$out"
      return 124
    fi
    sleep 0.1
    ticks=$((ticks + 1))
  done
  wait "$pid" || rc=$?
  cat "$out"
  rm -f "$out"
  return "$rc"
}

wait_boot() {
  local serial="$1" deadline=$((SECONDS + boot_timeout))
  # No `adb wait-for-device`: it has no timeout. Every call is bounded by the time left, so the
  # device must be listed and booted within the deadline, whatever adb does.
  until [ "$(adb_within $((deadline - SECONDS)) -s "$serial" get-state || true)" = "device" ] \
    && [ "$(adb_within $((deadline - SECONDS)) -s "$serial" shell getprop sys.boot_completed | tr -d '\r' || true)" = "1" ]; do
    [ $SECONDS -lt $deadline ] || die "$serial did not boot within $boot_timeout s (log: $logdir)"
    sleep 2
  done
  # Keep the screen on: the app stays foreground for the whole match. Animation settings stay as
  # shipped (the app treats animator scale 0 as reduced motion, which on-device tests check).
  adb_within "$call_limit" -s "$serial" shell svc power stayon true >/dev/null || die "$serial: could not keep the screen on"
  echo "$serial booted"
}

boot() {
  local name="${1%%:*}" port="${1##*:}"
  [ "$name" != "$port" ] || die "expected NAME:PORT, got $1"
  [ -d "$HOME/.android/avd/$name.avd" ] || die "no AVD $name (create it first)"
  adb_within "$call_limit" -s "emulator-$port" get-state >/dev/null && die "emulator-$port is already running"
  nohup "$emulator" -avd "$name" -port "$port" -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect -memory "$ram" -cores 2 -netdelay none -netspeed full \
    > "$logdir/$name.log" 2>&1 &
  echo "started $name as emulator-$port (pid $!, log $logdir/$name.log)"
}

stop_one() {
  local serial="$1" deadline=$((SECONDS + stop_timeout)) listed
  adb_within $((deadline - SECONDS)) -s "$serial" emu kill >/dev/null || true
  while true; do
    # A device list that does not come back in time counts as "still listed": the deadline decides.
    listed=$(adb_within $((deadline - SECONDS)) devices) || listed="$serial	unknown"
    printf '%s\n' "$listed" | grep -q "^$serial[[:space:]]" || break
    [ $SECONDS -lt $deadline ] || die "$serial did not shut down within $stop_timeout s"
    sleep 1
  done
  echo "$serial stopped"
}

cmd="${1:-}"; shift || true
case "$cmd" in
  create)
    [ $# -gt 0 ] || die "name at least one AVD"
    for name in "$@"; do
      if [ -d "$HOME/.android/avd/$name.avd" ]; then echo "$name exists"; continue; fi
      echo no | "$avdmanager" create avd -n "$name" -k "$image" -d pixel_8 >/dev/null
      cfg="$HOME/.android/avd/$name.avd/config.ini"
      sed -i '' -e "s/^hw.ramSize=.*/hw.ramSize=${ram}M/" -e 's/^hw.cpu.ncore=.*/hw.cpu.ncore=2/' "$cfg"
      grep -q '^hw.ramSize=' "$cfg" || echo "hw.ramSize=${ram}M" >> "$cfg"
      echo "created $name"
    done ;;
  start)
    [ $# -gt 0 ] || die "name at least one NAME:PORT"
    for spec in "$@"; do boot "$spec"; done
    for spec in "$@"; do wait_boot "emulator-${spec##*:}"; done ;;
  restart)
    [ $# -eq 1 ] || die "restart takes one NAME:PORT"
    boot "$1"; wait_boot "emulator-${1##*:}" ;;
  stop)
    for serial in "$@"; do stop_one "$serial"; done ;;
  stop-all)
    listed=$(adb_within "$call_limit" devices) || die "adb devices did not answer within $call_limit s"
    for serial in $(printf '%s\n' "$listed" | awk '/^emulator-[0-9]+[[:space:]]/ {print $1}'); do stop_one "$serial"; done
    listed=$(adb_within "$call_limit" devices) || die "adb devices did not answer within $call_limit s"
    echo "no emulator left: $(printf '%s\n' "$listed" | grep -c '^emulator-' || true) listed" ;;
  *) die "usage: emulators.sh create|start|restart|stop|stop-all ..." ;;
esac
