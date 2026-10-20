#!/usr/bin/env python3
"""Render the themed-icon (monochrome) layer from the supplied SVG.

The design's monochrome artwork uses an SVG <mask> to hide the back card behind the front one.
A vector drawable has no mask, and the subtraction it performs is not expressible with a fill
rule either (the card's inner hole overlaps the masked region), so this one layer ships as a
bitmap rendered from the pinned source rather than as a hand-made approximation. Run this after
the source changes; it refuses to render a source whose hash differs from the manifest.
"""
from pathlib import Path
import hashlib
import json
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "design/original/icons/android-monochrome.svg"
MANIFEST = ROOT / "design/import-manifest.json"
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def pinned_hash() -> str:
    manifest = json.loads(MANIFEST.read_text())
    for batch in [manifest, *manifest.get("later_imports", [])]:
        for entry in batch["files"]:
            if entry["path"] == "icons/android-monochrome.svg":
                return entry["sha256"]
    raise SystemExit("icons/android-monochrome.svg is not pinned in design/import-manifest.json")


def main() -> None:
    renderer = shutil.which("rsvg-convert")
    if renderer is None:
        raise SystemExit("rsvg-convert is required to render the monochrome layer (brew install librsvg)")
    actual = hashlib.sha256(SOURCE.read_bytes()).hexdigest()
    expected = pinned_hash()
    if actual != expected:
        raise SystemExit(f"source changed: {SOURCE} is {actual}, manifest pins {expected}")
    for density, size in DENSITIES.items():
        out = ROOT / f"app/src/main/res/drawable-{density}/ic_launcher_monochrome.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([renderer, "-w", str(size), "-h", str(size), "-o", str(out), str(SOURCE)], check=True)
        print(f"{out.relative_to(ROOT)} {size}x{size} {out.stat().st_size} bytes")


if __name__ == "__main__":
    main()
