"""Tests for run_e2e.py's report writing. Run: python3 -I -m unittest discover -s tools/e2e"""

import json
import os
import socket
import sys
import tempfile
import threading
import time
import unittest

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


if __name__ == "__main__":
    unittest.main()
