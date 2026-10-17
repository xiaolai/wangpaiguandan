# G1b — match profile spec `rules/match-v1.md`

**Status: SIGNED**

| Field | Value |
|---|---|
| Gate | G1b (`dev-docs/implementation-plan.md` section 3) |
| Signer | project owner |
| Date | 2026-10-16 |
| Spec signed | `rules/match-v1.md` (rows M1.1–M8.6, decisions D25–D40 in its section 10) |
| Released profile | `M1-match-v1` version `1.0.0`, hash `832431cd63641b9812c9e146f7f4a0b48271548c9c6eca0fbe550f6fe2391715` (`rules/profiles/M1-match-v1.json`) |
| Replaces | the development profile `M0-provisional` 0.1.0 (same option values; only the id, version, display name, provisional flag and source references changed) |
| Rules reviewer | none. The plan asked for the owner **and** a Guandan rules reviewer; no reviewer signed, and nobody signed the fixture list separately (WI-04 acceptance). |

## Basis of the signature

Stated by the project owner, recorded here as given:

- The owner checked the spec against the **abridged 2022 trial text only** (`rules/sources/competitive-rules-2022-trial-gansu-excerpt.md`). The printed 2017 and 2022 rule books were **not** checked.
- Every decision D25–D40 is adopted at the default section 10 states, including D35 (no limit on failed A attempts) and D38 (an early double-up ends the deal; the two remaining places are recorded as inferred).
- The plan's G1b default ("pick one published event rule document as the first profile") was not followed: the profile is the match-v1 draft built from several sources, as written.
- The owner is **not a Guandan expert**. No player or referee has reviewed the defaults.

## What this signature does and does not establish

- It makes `M1-match-v1` releasable (same gate and catalog checks as G1a).
- It does not settle the conflict the plan notes between `design/original/README.md` ("过 A（失败 3 次）") and the D35 default (no limit); the signed profile uses no limit. A limit ships, if ever, as a new profile version.
