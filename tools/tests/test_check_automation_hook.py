"""Tests for tools/check_automation_hook.py. Run: python3 -I -m unittest discover -s tools/tests"""

import io
import os
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import check_automation_hook as hook  # noqa: E402

ALL = b"Lcn/wpgd/app/automation/E2eStatusHook; wpgd-e2e-hook-v1"


def build(root, class_bytes):
    """A debug APK holding every needle, and a classes dir holding the hook package with class_bytes."""
    apk = os.path.join(root, "debug.apk")
    with zipfile.ZipFile(apk, "w") as z:
        z.writestr("classes.dex", b"dex\n035\0" + ALL)
    classes = os.path.join(root, "classes")
    pkg = os.path.join(classes, hook.CLASS_DIR)
    os.makedirs(pkg)
    with open(os.path.join(pkg, "E2eStatusHook.class"), "wb") as fh:
        fh.write(class_bytes)
    return apk, classes


def run(*argv):
    out, err = io.StringIO(), io.StringIO()
    with mock.patch.object(sys, "argv", ["check_automation_hook.py", *argv]), redirect_stdout(out), redirect_stderr(err):
        status = hook.main()
    return status, err.getvalue()


class PresentSelfTest(unittest.TestCase):
    def test_passes_when_classes_hold_every_needle(self):
        with tempfile.TemporaryDirectory() as d:
            apk, classes = build(d, b"\xca\xfe\xba\xbe cn/wpgd/app/automation/E2eStatusHook wpgd-e2e-hook-v1")
            self.assertEqual(run("present", "--apk", apk, "--classes", classes)[0], 0)

    def test_fails_when_the_class_scanner_finds_no_identifier(self):
        # Audit #69: only the package directory existed, so a class-content scanner that found
        # nothing still passed the self-test.
        with tempfile.TemporaryDirectory() as d:
            apk, classes = build(d, b"\xca\xfe\xba\xbe nothing to see")
            status, err = run("present", "--apk", apk, "--classes", classes)
            self.assertEqual(status, 1)
            self.assertIn("missing", err)

    def test_fails_when_one_class_needle_is_missing(self):
        with tempfile.TemporaryDirectory() as d:
            apk, classes = build(d, b"\xca\xfe\xba\xbe cn/wpgd/app/automation/E2eStatusHook")
            status, err = run("present", "--apk", apk, "--classes", classes)
            self.assertEqual(status, 1)
            self.assertIn("marker string", err)


    def test_the_diagnostic_names_only_the_missing_needle(self):
        # Round 3 #66: operator precedence listed every needle as missing when only the marker was.
        with tempfile.TemporaryDirectory() as d:
            apk, classes = build(d, b"\xca\xfe\xba\xbe cn/wpgd/app/automation/E2eStatusHook")
            status, err = run("present", "--apk", apk, "--classes", classes)
            self.assertEqual(status, 1)
            line = next(x for x in err.splitlines() if classes in x and "missing" in x)
            self.assertTrue(line.endswith("missing ['marker string']"), line)

if __name__ == "__main__":
    unittest.main()
