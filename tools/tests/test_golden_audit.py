"""Tests for tools/golden/audit.py: a wrong golden case must FAIL the audit.
Run: python3 -I -m unittest discover -s tools/tests"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
AUDIT = os.path.join(REPO, "tools", "golden", "audit.py")
GOLDEN = os.path.join("core", "src", "test", "resources", "fixtures", "rules", "golden-v1.json")


class GoldenAuditTest(unittest.TestCase):
    def audit(self, mutate=None):
        """Runs the audit on a copy of the repository's audit inputs, after mutate(cases)."""
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "rules", "golden"))
            shutil.copy(os.path.join(REPO, "rules", "base-v1.md"), os.path.join(d, "rules", "base-v1.md"))
            shutil.copytree(os.path.join(REPO, "rules", "sources", "text"), os.path.join(d, "rules", "sources", "text"))
            with open(os.path.join(REPO, GOLDEN), encoding="utf-8") as fh:
                cases = json.load(fh)
            if mutate:
                mutate({c["id"]: c for c in cases})
            os.makedirs(os.path.dirname(os.path.join(d, GOLDEN)))
            with open(os.path.join(d, GOLDEN), "w", encoding="utf-8") as fh:
                json.dump(cases, fh)
            r = subprocess.run([sys.executable, "-I", AUDIT, d], capture_output=True, text=True, timeout=300)
            return r.returncode, r.stdout + r.stderr

    def assertFails(self, mutate, case_id, words):
        status, out = self.audit(mutate)
        self.assertEqual(status, 1, out)
        line = next((x for x in out.splitlines() if x.strip().startswith(case_id + " |")), "")
        self.assertIn(words, line, out)

    def test_the_repository_cases_pass(self):
        status, out = self.audit()
        self.assertEqual(status, 0, out)
        self.assertIn("FAIL 0", out)

    def test_exclusive_counts_readings_under_every_declaration(self):
        # Audit #78: KK+66+wildcard also reads as 666+KK when the wildcard is declared a 6.
        def m(cases):
            cases["WC05"]["expected"]["exclusive"] = True
        self.assertFails(m, "WC05", "exclusive but the cards have 2 readings")

    def test_profile_case_acceptance_is_checked(self):
        # Audit #79: reversing PR01 to accepted passed, because only R8.1's wording was checked.
        def m(cases):
            cases["PR01"]["expected"] = {"accepted": True, "rejected_reason": None}
        self.assertFails(m, "PR01", "unregistered keys: ['allowWrapAround']")

    def test_a_registered_option_key_must_be_accepted(self):
        def m(cases):
            cases["PR01"]["setup"]["options"] = {"sequence.wrapAroundA": True}
        self.assertFails(m, "PR01", "unregistered keys: none")

    def test_a_generator_case_without_an_evaluator_fails(self):
        # Audit #80: an unknown generator id fell through to "evaluator agrees".
        def m(cases):
            cases["GE01"]["id"] = "GE04"
            cases["GE01"]["expected"] = {"nonsense": True}
        self.assertFails(m, "GE04", "no audit evaluator for generator case GE04")


    def test_a_declaration_without_a_wildcard_fails(self):
        # Round 3 #64: a declaration for an absent H9 passed as "evaluator agrees"; the classifier rejects it.
        def m(cases):
            cases["T01"]["setup"]["play"]["declaration"] = [{"card": "H9", "as_rank": "5"}]
        self.assertFails(m, "T01", "declaration_mismatch")

if __name__ == "__main__":
    unittest.main()
