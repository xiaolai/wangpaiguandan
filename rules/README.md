# Rules folder

| Path | What it is |
|---|---|
| `base-v1.md` | Draft base rule spec (gate G1a). Every rule row cites a source or a decision D1–D24. |
| `match-v1.md` | Draft match profile spec (gate G1b): levels, passing A, tribute, return, resistance, opening lead, card-count reporting. Every row cites source lines or a decision D25–D40. |
| `golden/` | Notes and audits for the hand-worked base-v1 reference cases; the cases themselves are written by `tools/golden/build.py` to `core/src/test/resources/fixtures/rules/golden-v1.json`. |
| `sources/` | The rule documents the spec quotes. |
| `profiles/` | Rule profiles: data files the engine loads. This README is mostly about them. |

## Rule profiles

A **rule profile** says which variant of each variable rule a game uses. It is a JSON file at `profiles/<id>.json`. Changing a rule variant is a change to that file, not to engine code.

The code lives in the `core.rules` package of the `:core` module (`core/src/main/kotlin/…/core/rules/`):

- `RuleOptions.kt` is the **registry**: the list of every rule a profile may vary. Each entry has a stable id (such as `bomb.maxSize`), a value type (integer, true/false, or an integer limit where `null` means "no limit"), a default, the base-v1 row it comes from, and the D-decision it depends on, if any. Citations live here, not in the profile files.
- `RuleProfileLoader.parse(json)` reads a profile and returns either the profile or a typed error. It never ignores anything it does not understand.
- A profile stores only the options it sets. Any option it leaves out takes the registry default.
- `MatchOptions.kt` is the second registry, for match-level rules (`rules/match-v1.md`). A **match profile** is loaded with `RuleProfileLoader.parse(json, MatchOptions.registry)`. Profile files whose id starts with `M` (such as `M1-match-v1`) are match profiles; all others are base profiles. A match is played under one base profile and one match profile, and both hashes are part of its state. Because the registries are separate, a new match option never changes a base profile's hash.

### File format

```json
{
  "id": "P1-base-v1",
  "version": "1.0.0",
  "displayName": "…",
  "provisional": false,
  "sourceRefs": ["rules/base-v1.md …"],
  "options": { "bomb.maxSize": 10 }
}
```

`id`, `version`, `displayName` and `provisional` are required. `version` is `MAJOR.MINOR.PATCH`. The `id` must match the file name.

The loader rejects: an unknown field, an unknown option key, a value of the wrong type (`"10"` or `9.5` where an integer is expected), a value outside the option's range, a duplicate key, a missing required field, and a malformed id or version.

### Hash

Every profile has a SHA-256 hash of a canonical form (keys sorted, no whitespace). The hash covers every field and the **effective** value of every option, including defaults. So reordering keys or reformatting the file does not change the hash, and changing any option, or any registry default, does. A match never resumes under a different hash.

### Adding an option

1. Add one entry to `RuleOptions.kt` with its id, type, default, base-v1 row and D-number, and add it to the `registry` list.
2. If the default is a decision, set it explicitly in the base profile. A signed profile is frozen: the change ships as a new profile version (or a new profile id) with its own signed decision record under `dev-docs/decisions/`.
3. Teach the engine to read it (`profile[RuleOptions.YourOption]`).

Adding an option changes every profile's hash, because the hash covers effective values. That is intended.

### Retiring an option

1. Set `deprecatedSince` on its registry entry. Profiles that still set it keep loading, and the loader reports a `DeprecatedOption` warning for each one. Nothing is dropped silently.
2. Once no supported profile sets it, delete the entry. From then on, a profile that sets it is rejected as an unknown option.

### Adding a variant profile

Copy a profile to `profiles/<new-id>.json`, give it a new `id` and `version`, and set only the options that differ. No code changes. A rule change to an existing profile is a new version of that profile, never an edit in place of a frozen one.

## Provisional and signed profiles

The packaged profiles are **signed** (2026-10-16, by the project owner; see `dev-docs/decisions/G1a-base-rules.md` and `G1b-match-profile.md`):

| Profile | Version | Spec | Formerly |
|---|---|---|---|
| `P1-base-v1` | 1.0.0 | base-v1 with the section-9 default for every D-decision applied | `P0-provisional` 0.1.0 (development) |
| `M1-match-v1` | 1.0.0 | match-v1 with the section-10 default for every decision (D25–D40) applied | `M0-provisional` 0.1.0 (development) |

The option values are the ones the provisional profiles carried; only the id, version, display name, provisional flag and source references changed, so the hashes changed. Each default is set explicitly in the file so it is visible. Options with a sourced rule (no D-number) are left to the registry default. `bomb.maxSize` depends on D2, which base-v1 still marks as pending a full-edition source check (the owner left it at the stated default when signing), so its registry entry stays `pending` and the capability catalog says so.

Note: profile `M1-match-v1` is not milestone M1 of the plan; the M prefix only marks a match profile.

A **provisional** profile is for development only. A build that carries one is a development build, and must show the profile id, version and hash in diagnostics. A **signed** profile is one whose decisions have been signed (gates G1a and G1b; the plan asks for the owner and a rules reviewer, and the current profiles were signed by the project owner alone); it must have `"provisional": false` and a signed decision record under `dev-docs/decisions/` that names its id and version (checked by `verifyReleaseDistributable`) and its hash (checked by `CapabilityCatalogTest`). The loader only reports the flag. WI-12 acts on it: a release build carrying a provisional profile stops at launch (`app/.../release/ReleaseGate.kt`, `ReleaseGateTest`), and `./gradlew :app:verifyReleaseDistributable` refuses to sign one.
