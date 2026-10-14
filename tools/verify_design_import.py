#!/usr/bin/env python3
"""Check source fidelity and restored local assets without executing imported scripts."""
from pathlib import Path
import hashlib
import json
import re
from generate_design_tokens import rendered, DEST

ROOT = Path(__file__).resolve().parents[1]
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()

source = json.loads((ROOT / "design/import-manifest.json").read_text())
for entry in source["files"]:
    path = ROOT / "design/original" / entry["path"]
    assert path.is_file(), f"Missing imported file: {path}"
    assert digest(path) == entry["sha256"], f"Imported file changed: {path}"
assets = json.loads((ROOT / "design/baseline-assets-manifest.json").read_text())
for entry in assets:
    assert digest(ROOT / "design/original" / entry["path"]) == entry["sha256"]
spec = (ROOT / "design/original/Guandan Redesign Spec.dc.html").read_text()
for relative in re.findall(r"img:'(uploads/[^']+)'", spec):
    assert (ROOT / "design/original" / relative).is_file(), relative
inventory = json.loads((ROOT / "design/screen-inventory.json").read_text())
assert len(inventory) == 50
assert len({item["id"] for item in inventory}) == len(inventory)
table_states = json.loads((ROOT / "design/table-state-inventory.json").read_text())
assert len(table_states) == 23
assert len({item["id"] for item in table_states}) == len(table_states)
assert DEST.read_text() == rendered(), "Native tokens drifted from JSON"
print(f"Verified {len(source['files'])} unchanged design files, {len(assets)} baseline assets, 50 screen records, 23 table states and native token parity.")
