"""Tests for emulators.sh with a fake SDK (no emulator runs). Run: python3 -I -m unittest discover -s tools/e2e"""

import os
import stat
import subprocess
import tempfile
import time
import unittest

SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "emulators.sh")

# A device that never appears: `adb wait-for-device` would block (here: 20 s), get-state fails at once.
FAKE_ADB = """#!/bin/sh
for arg in "$@"; do
  case "$arg" in
    wait-for-device) sleep 20; exit 0 ;;
    get-state) echo "error: device 'emulator-5554' not found" >&2; exit 1 ;;
  esac
done
exit 1
"""
FAKE_EMULATOR = "#!/bin/sh\nexit 0\n"

# A device that is listed once launched but whose shell stalls (a wedged adbd): `shell` blocks for 30 s.
STALLED_ADB = """#!/bin/sh
for arg in "$@"; do
  case "$arg" in
    get-state) [ -f "$HOME/launched" ] || exit 1; echo device; exit 0 ;;
    shell) sleep 30; exit 0 ;;
    devices) printf 'List of devices attached\\nemulator-5554\\tdevice\\n'; exit 0 ;;
    kill) sleep 30; exit 0 ;;
  esac
done
exit 1
"""


def executable(path, text):
    with open(path, "w") as fh:
        fh.write(text)
    os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)


LAUNCHING_EMULATOR = '#!/bin/sh\ntouch "$HOME/launched"\n'


def fake_sdk(d, adb, emulator=FAKE_EMULATOR):
    for sub in ("platform-tools", "emulator", "home/.android/avd/pixel8.avd", "logs"):
        os.makedirs(os.path.join(d, sub))
    executable(os.path.join(d, "platform-tools", "adb"), adb)
    executable(os.path.join(d, "emulator", "emulator"), emulator)
    return dict(os.environ, ANDROID_SDK_ROOT=d, HOME=os.path.join(d, "home"),
                WPGD_E2E_LOGDIR=os.path.join(d, "logs"), WPGD_E2E_BOOT_TIMEOUT_S="2")


class BootDeadlineTest(unittest.TestCase):
    def test_a_stalled_adb_call_cannot_outlast_the_boot_deadline(self):
        # Audit #74, round 2: each adb call was unbounded, so a getprop that never answers hung past the deadline.
        with tempfile.TemporaryDirectory() as d:
            env = fake_sdk(d, STALLED_ADB, LAUNCHING_EMULATOR)
            start = time.time()
            r = subprocess.run(["bash", SCRIPT, "start", "pixel8:5556"], env=env, capture_output=True, text=True, timeout=25)
            self.assertNotEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn("did not boot within 2 s", r.stderr)
            self.assertLess(time.time() - start, 10)

    def test_a_stalled_kill_cannot_hang_stop(self):
        with tempfile.TemporaryDirectory() as d:
            env = dict(fake_sdk(d, STALLED_ADB, LAUNCHING_EMULATOR), WPGD_E2E_STOP_TIMEOUT_S="2")
            start = time.time()
            r = subprocess.run(["bash", SCRIPT, "stop", "emulator-5554"], env=env, capture_output=True, text=True, timeout=25)
            self.assertNotEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn("did not shut down within 2 s", r.stderr)
            self.assertLess(time.time() - start, 10)

    def test_a_launch_that_never_shows_a_device_fails_at_the_deadline(self):
        # Audit #74: `adb wait-for-device` had no timeout, so this hung before the deadline was checked.
        with tempfile.TemporaryDirectory() as d:
            for sub in ("platform-tools", "emulator", "home/.android/avd/pixel8.avd", "logs"):
                os.makedirs(os.path.join(d, sub))
            executable(os.path.join(d, "platform-tools", "adb"), FAKE_ADB)
            executable(os.path.join(d, "emulator", "emulator"), FAKE_EMULATOR)
            env = dict(os.environ, ANDROID_SDK_ROOT=d, HOME=os.path.join(d, "home"),
                       WPGD_E2E_LOGDIR=os.path.join(d, "logs"), WPGD_E2E_BOOT_TIMEOUT_S="2")
            start = time.time()
            r = subprocess.run(["bash", SCRIPT, "restart", "pixel8:5554"], env=env, capture_output=True, text=True, timeout=15)
            self.assertNotEqual(r.returncode, 0, r.stdout + r.stderr)
            self.assertIn("did not boot within 2 s", r.stderr)
            self.assertLess(time.time() - start, 10)


if __name__ == "__main__":
    unittest.main()
