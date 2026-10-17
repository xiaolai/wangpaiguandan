# Project license

SPDX-License-Identifier: MIT

Recorded by the project owner on 2026-10-16. The project's own code, rule profiles, rule documents
and app resources are distributed under the MIT License. The full text is `LICENSE` at the
repository root (copyright holder "Wangpaiguandan contributors", 2026).

What reads this file:

- `./gradlew :app:verifyReleaseDistributable` counts the license as recorded because the
  `SPDX-License-Identifier:` line above is present and the former `OWNER DECISION REQUIRED` status
  line is gone. A signed release (`-Pwpgd.releaseSigning=true`) runs that gate before packaging.
- `LicenseInventoryTest` checks that exactly one of the SPDX line and the placeholder is present,
  that `LICENSE` exists and starts with the title of the license the SPDX line names, and that no
  entry in `release/license-inventory.json` is still marked with the old `PROJECT` placeholder.

Still open (not decided by recording the project license):

- The distribution terms of the design material supplied with the project (`design/`, the entry
  marked `OWNER-DECISION` in `release/license-inventory.json`: the generated design tokens). The
  project owner has not recorded them. No gate checks this entry today.

## How to change it

1. Replace the `SPDX-License-Identifier:` line with the new identifier from
   https://spdx.org/licenses/.
2. Replace `LICENSE` with the new license's full text, and add its title to the map in
   `LicenseInventoryTest`.
3. Update the `license` fields in `release/license-inventory.json` that carry the old identifier.
4. Run `./tools/ci.sh`.
