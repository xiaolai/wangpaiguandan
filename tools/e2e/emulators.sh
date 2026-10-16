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

wait_boot() {
  local serial="$1" deadline=$((SECONDS + 240))
  "$adb" -s "$serial" wait-for-device
  until [ "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
    [ $SECONDS -lt $deadline ] || die "$serial did not boot within 240 s (log: $logdir)"
    sleep 2
  done
  # Keep the screen on: the app stays foreground for the whole match. Animation settings stay as
  # shipped (the app treats animator scale 0 as reduced motion, which on-device tests check).
  "$adb" -s "$serial" shell svc power stayon true >/dev/null
  echo "$serial booted"
}

boot() {
  local name="${1%%:*}" port="${1##*:}"
  [ "$name" != "$port" ] || die "expected NAME:PORT, got $1"
  [ -d "$HOME/.android/avd/$name.avd" ] || die "no AVD $name (create it first)"
  "$adb" -s "emulator-$port" get-state >/dev/null 2>&1 && die "emulator-$port is already running"
  nohup "$emulator" -avd "$name" -port "$port" -no-window -no-audio -no-boot-anim -no-snapshot \
    -gpu swiftshader_indirect -memory "$ram" -cores 2 -netdelay none -netspeed full \
    > "$logdir/$name.log" 2>&1 &
  echo "started $name as emulator-$port (pid $!, log $logdir/$name.log)"
}

stop_one() {
  local serial="$1" deadline=$((SECONDS + 60))
  "$adb" -s "$serial" emu kill >/dev/null 2>&1 || true
  while "$adb" devices | grep -q "^$serial[[:space:]]"; do
    [ $SECONDS -lt $deadline ] || die "$serial did not shut down within 60 s"
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
    for serial in $("$adb" devices | awk '/^emulator-[0-9]+[[:space:]]/ {print $1}'); do stop_one "$serial"; done
    echo "no emulator left: $("$adb" devices | grep -c '^emulator-' || true) listed" ;;
  *) die "usage: emulators.sh create|start|restart|stop|stop-all ..." ;;
esac
