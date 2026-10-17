"""Tests for tools/golden/build.py --check: CI must detect a committed golden file that differs from the generator.
Run: python3 -I -m unittest discover -s tools/tests"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
BUILD = os.path.join(REPO, "tools", "golden", "build.py")
GOLDEN = os.path.join("core", "src", "test", "resources", "fixtures", "rules", "golden-v1.json")


class GoldenBuildCheckTest(unittest.TestCase):
    def check(self, mutate=None):
        """Runs build.py --check on a copy of the committed golden file after mutate(cases); returns (status, output, file text)."""
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, GOLDEN)
            os.makedirs(os.path.dirname(path))
            shutil.copy(os.path.join(REPO, GOLDEN), path)
            if mutate:
                with open(path, encoding="utf-8") as fh:
                    cases = json.load(fh)
                mutate(cases)
                with open(path, "w", encoding="utf-8") as fh:
                    fh.write(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
            with open(path, encoding="utf-8") as fh:
                before = fh.read()
            r = subprocess.run([sys.executable, "-I", BUILD, "--check", d], capture_output=True, text=True, timeout=120, cwd=d)
            with open(path, encoding="utf-8") as fh:
                after = fh.read()
            self.assertEqual(before, after, "--check must never rewrite the golden file")
            return r.returncode, r.stdout + r.stderr

    def test_the_committed_file_matches_the_generator(self):
        status, out = self.check()
        self.assertEqual(status, 0, out)

    def test_a_hand_edited_case_is_drift(self):
        # Round 3 #65: CI regenerated the file before auditing it, silently repairing a stale or edited fixture.
        def m(cases):
            cases[0]["why"] += " (edited by hand)"
        status, out = self.check(m)
        self.assertEqual(status, 1, out)
        self.assertIn("differs from tools/golden/build.py", out)

    def test_a_missing_file_is_drift(self):
        with tempfile.TemporaryDirectory() as d:
            r = subprocess.run([sys.executable, "-I", BUILD, "--check", d], capture_output=True, text=True, timeout=120, cwd=d)
            self.assertEqual(r.returncode, 1, r.stdout + r.stderr)
            self.assertFalse(os.path.exists(os.path.join(d, GOLDEN)))


if __name__ == "__main__":
    unittest.main()
