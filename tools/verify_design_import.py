#!/usr/bin/env python3
"""Check the derived design records and native token parity.

The supplied design package is not part of this repository. This checks what the
build actually uses: the screen and table-state inventories, and parity between
design/tokens.json and the generated DesignTokens.kt. The two manifests record
what was supplied and its hashes; they are provenance records, and the files they
name are not expected to be present.
"""
from pathlib import Path
import json
from generate_design_tokens import rendered, DEST

ROOT = Path(__file__).resolve().parents[1]

inventory = json.loads((ROOT / "design/screen-inventory.json").read_text())
assert len(inventory) == 50
assert len({item["id"] for item in inventory}) == len(inventory)
table_states = json.loads((ROOT / "design/table-state-inventory.json").read_text())
assert len(table_states) == 23
assert len({item["id"] for item in table_states}) == len(table_states)

# The provenance records must stay well formed even though the files are absent.
source = json.loads((ROOT / "design/import-manifest.json").read_text())
batches = [source, *source.get("later_imports", [])]
for batch in batches:
    for entry in batch["files"]:
        assert {"path", "sha256", "bytes"} <= entry.keys(), f"malformed import record: {entry}"
assets = json.loads((ROOT / "design/baseline-assets-manifest.json").read_text())
for entry in assets:
    assert {"path", "sha256"} <= entry.keys(), f"malformed baseline record: {entry}"

assert DEST.read_text() == rendered(), "Native tokens drifted from JSON"
print(
    f"Verified {sum(len(batch['files']) for batch in batches)} recorded design files "
    f"(not shipped), {len(assets)} recorded baseline assets, 50 screen records, "
    f"23 table states and native token parity."
)
