# G1a — base rule spec `rules/base-v1.md`

**Status: SIGNED**

| Field | Value |
|---|---|
| Gate | G1a (`dev-docs/implementation-plan.md` section 3) |
| Signer | project owner |
| Date | 2026-10-16 |
| Spec signed | `rules/base-v1.md` (decisions D1–D24 in its section 9) |
| Released profile | `P1-base-v1` version `1.0.0`, hash `72aac50223ea0feb844027d8c99e24554a1510c1b3d41dcc4c55db621de7de31` (`rules/profiles/P1-base-v1.json`) |
| Replaces | the development profile `P0-provisional` 0.1.0 (same option values; only the id, version, display name, provisional flag and source references changed) |
| Rules reviewer | none. The plan asked for the owner **and** a Guandan rules reviewer; no reviewer signed. |

## Basis of the signature

Stated by the project owner, recorded here as given:

- The owner checked the spec against the **abridged 2022 trial text only**: `rules/sources/competitive-rules-2022-trial-gansu-excerpt.md` (竞技掼蛋竞赛规则（试行）, reposted by 甘肃棋院, which says the content is abridged).
- The printed 2017 and 2022 rule books were **not** checked.
- **D2** (9- and 10-card bombs; `bomb.maxSize` 10) and **D11** (whether the reposted national texts HA17 and CG22a are faithful) are **left as the stated defaults**. They are accepted as defaults, not as verified rules: `bomb.maxSize` stays marked `pending` in the option registry (`RuleOptions.BombMaxSize`) and the capability catalog keeps saying so.
- Every other decision in section 9 (D1, D3–D10, D12–D24) is adopted at the default the spec states.
- The owner is **not a Guandan expert**. No player or referee has reviewed the defaults.

## What this signature does and does not establish

- It makes `P1-base-v1` releasable: the release gate (`ReleaseGate`, `:app:verifyReleaseDistributable`) accepts a profile whose `provisional` flag is false, and `CapabilityCatalogTest` requires this file to name the profile's exact id, version and hash.
- It does not make any rule more correct than the evidence in `rules/base-v1.md` Appendix A. A rule change found later ships as a new profile version (plan WI-02 rollback), never as an edit of `P1-base-v1` 1.0.0.
