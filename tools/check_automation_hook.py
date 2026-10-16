#!/usr/bin/env python3
"""Prove the debug-only automation hook stays out of release and r8check builds.

The hook (app/src/debug/kotlin/cn/wpgd/app/automation/, dev-docs/automation-hook.md) must exist
only in debug builds. This scans, for every build named on the command line:

  * every classes*.dex inside the APK, as raw bytes: dex string pools hold class descriptors and
    string constants in (modified) UTF-8, so the hook's package descriptor, class name and marker
    string are found whenever any of its code was packaged;
  * the build type's compiled Kotlin classes before R8 (`--classes`), because R8 strips code nobody
    calls: a hook moved into main but left unreferenced would vanish from the APK yet still be
    compiled into the release build;
  * the merged manifest (`--manifest`): the hook has no component, and none may appear;
  * R8's mapping file (`--mapping`), which still names original classes R8 renamed or inlined:
    the APK's own strings cannot show those.

`absent` fails (exit 1) on any finding; `present` is the self-test against the debug build and
fails unless every needle is found, so a scanner that silently finds nothing cannot pass.

Usage:
  check_automation_hook.py absent  --apk A.apk --classes DIR [--manifest M.xml] [--mapping mapping.txt] [--apk ... --classes ...]
  check_automation_hook.py present --apk debug.apk --classes DIR
"""

import argparse
import os
import sys
import zipfile

# What identifies the hook. Bytes as they appear in a dex string pool and in .class constant pools.
NEEDLES = {
    "package descriptor": b"Lcn/wpgd/app/automation/",
    "class name": b"E2eStatusHook",
    "marker string": b"wpgd-e2e-hook-v1",
}
CLASS_DIR = os.path.join("cn", "wpgd", "app", "automation")
MANIFEST_NEEDLES = ("cn.wpgd.app.automation", "E2eStatusHook", "wpgd-e2e-hook")


def scan_apk(path):
    """Needle names found in the APK's dex files, and how many dex files were read."""
    if not os.path.isfile(path):
        raise SystemExit(f"APK not found: {path}")
    found = set()
    dexes = 0
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                dexes += 1
                data = z.read(name)
                if not data.startswith(b"dex\n"):
                    raise SystemExit(f"{path}!{name} is not a dex file")
                found |= {k for k, v in NEEDLES.items() if v in data}
    if dexes == 0:
        raise SystemExit(f"{path} holds no classes*.dex: refusing an empty scan")
    return found, dexes


def scan_classes(root):
    """Needle names found among compiled classes under root, and how many classes were read."""
    if not os.path.isdir(root):
        raise SystemExit(f"compiled classes not found: {root}")
    found = set()
    count = 0
    if os.path.isdir(os.path.join(root, CLASS_DIR)):
        found.add("package directory")
    for dirpath, _, files in os.walk(root):
        for f in files:
            if f.endswith(".class"):
                count += 1
                with open(os.path.join(dirpath, f), "rb") as fh:
                    data = fh.read()
                # A .class file names other classes with slashes and no leading L: check both forms.
                for k, v in NEEDLES.items():
                    if v in data or (v.startswith(b"L") and v[1:] in data):
                        found.add(k)
    if count == 0:
        raise SystemExit(f"{root} holds no .class file: refusing an empty scan")
    return found, count


def scan_manifest(path):
    if not os.path.isfile(path):
        raise SystemExit(f"manifest not found: {path}")
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    return {n for n in MANIFEST_NEEDLES if n in text}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("mode", choices=["absent", "present"])
    p.add_argument("--apk", action="append", default=[])
    p.add_argument("--classes", action="append", default=[])
    p.add_argument("--manifest", action="append", default=[])
    p.add_argument("--mapping", action="append", default=[])
    a = p.parse_args()
    if not a.apk or not a.classes:
        raise SystemExit("name at least one --apk and one --classes")

    problems = []
    for apk in a.apk:
        found, dexes = scan_apk(apk)
        print(f"{apk}: {dexes} dex files, found: {sorted(found) or 'nothing'}")
        if a.mode == "absent" and found:
            problems.append(f"{apk} contains the automation hook ({', '.join(sorted(found))})")
        if a.mode == "present" and found != set(NEEDLES):
            problems.append(f"self-test: {apk} should contain every needle, missing {sorted(set(NEEDLES) - found)}")
    for root in a.classes:
        found, count = scan_classes(root)
        print(f"{root}: {count} classes, found: {sorted(found) or 'nothing'}")
        if a.mode == "absent" and found:
            problems.append(f"{root} compiles the automation hook ({', '.join(sorted(found))})")
        if a.mode == "present" and "package directory" not in found:
            problems.append(f"self-test: {root} should hold {CLASS_DIR}")
    for m in a.manifest:
        found = scan_manifest(m)
        print(f"{m}: found: {sorted(found) or 'nothing'}")
        if found:
            problems.append(f"{m} names the automation hook ({', '.join(sorted(found))})")

    for m in a.mapping:
        if not os.path.isfile(m):
            raise SystemExit(f"R8 mapping not found: {m}")
        with open(m, encoding="utf-8") as fh:
            text = fh.read()
        found = sorted(n for n in ("cn.wpgd.app.automation.", "E2eStatusHook") if n in text)
        print(f"{m}: found: {found or 'nothing'}")
        if found:
            problems.append(f"{m} maps the automation hook ({', '.join(found)})")

    if problems:
        for x in problems:
            print("FAIL:", x, file=sys.stderr)
        return 1
    print("automation hook check:", "absent as required" if a.mode == "absent" else "self-test found the hook in the debug build")
    return 0


if __name__ == "__main__":
    sys.exit(main())
