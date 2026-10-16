# Rules engine pre-commit review (WI-02)

**Date:** 2026-10-14
**Scope:** `core/src/main/kotlin/cn/wpgd/app/core/rules/*.kt`, `core/src/main/kotlin/cn/wpgd/app/core/Card.kt`, and every test in `core/src/test/kotlin/cn/wpgd/app/core/rules/`.
**Read against:** `rules/base-v1.md` (draft, D1–D18), `rules/profiles/P0-provisional.json`, `dev-docs/implementation-plan.md` WI-02 and the section 3 paragraph on development versus release.
**Method:** I read all of the code. Then I tested it on a scratch copy of the repo outside the working tree (probe tests plus 13 mutations), using the project's own `./gradlew :core:test`. The scratch copy has been deleted, and no file in the repo was changed except this review.

## Summary

**Verdict: commit after fixes.**
Under P0, play legality and the generator are correct for everything I could reach. On 690 hands the generator matched an independent subset oracle with zero mismatches, all 137 golden cases pass, and every mutation of a core ordering rule was caught.
There are four Medium findings: a sourced rule (R3.3) that an option can override, a loader crash that escapes its "never throws" contract, no pinned profile hash, and six section-9 decisions hard-coded with no registry option. The Low findings are test gaps. No finding is Critical or High.

## Findings

| ID | Sev | File:line | Problem | Proof | Suggested fix |
|---|---|---|---|---|---|
| F1 | Medium | `rules/Shapes.kt:31-37`, `rules/RuleOptions.kt:38-40` | `sequence.wrapAroundA=true` makes J-Q-K-A-2 and KK-AA-22 legal at level 2. Sourced R3.3 (E28, E29) forbids both. | RAN: level 2, wrap on: `SJ DQ CK SA D2` reads as `STRAIGHT [11,12,13,14,2]`, and `SK DK SA DA S2 D2` reads as `CONSECUTIVE_PAIRS [13,14,2]` | Skip wrap windows that pass through the level rank when level is 2, or narrow the option so it only covers D1 (R3.4). Add a golden case with wrap on. |
| F2 | Medium | `rules/RuleProfileLoader.kt:40-52` | `parse` catches only `RuleProfileError`. Deeply nested JSON throws `StackOverflowError` out of the loader, although its KDoc says "the loader never throws". | RAN: `sourceRefs` holding 100,000 nested `[` gave `THREW java.lang.StackOverflowError` | Cap the input size and nesting depth before parsing, and map the failure to `Malformed`. Test it. |
| F3 | Medium | `rules/RuleProfile.kt:39-56`, `RuleProfileTest.kt:66-91` | No test pins the hash value or the canonical JSON. Removing the option sort, or renaming a canonical field, silently changes P0's hash. | RAN: mutations M1 (no sort) and M24 (`format` renamed to `fmt`) SURVIVED the full `:core:test` | Pin P0's canonical JSON string and hash literal in a test. Change them only together with a version or `CANONICAL_FORMAT` bump. |
| F4 | Medium | `Shapes.kt:73` (D5, D12), `Shapes.kt:39` (D13), `Shapes.kt:52` (D14), `Classifier.kt:86` (D15), `Shapes.kt:91,156-165` (D16), `RuleOption.kt:10` | R8.1 says the section-9 defaults are the registry defaults. D5 and D12–D16 are hard-coded with no option, and the `Decision` enum stops at D11. | READ. A golden case that cites D12+ would crash `GoldenCaseTest.kt:69` (`Decision.valueOf`). | Add D12–D18 to `Decision`. Either register options for D5 and D12–D16, or amend R8.1 to say which decisions are engine constants. |
| F5 | Low | `Classifier.kt:50,71-99`, `LegalActionGenerator.kt:23,50` | Failure handling for impossible inputs is inconsistent. `judge` accepts three wildcards; `readings`, `generate` and the loose-profile `judge` throw an untyped `IllegalArgumentException`. | RAN: `judge(H2 H2 H2 S7, [7,7,7])` returns a `Readable` 4-card bomb; with `alwaysDeclared=false`, `judge(...)` throws an IAE | Check the wildcard count in `judge` and return a typed `RejectReason`. Keep `require` for internal callers only. |
| F6 | Low | `RuleProfileTest.kt:145-146` | The two assertions are byte-identical. The escape case (`"a"` against `"a"`, see `RuleProfileLoader.kt:131`) is never tested. | RAN: mutation M2 (raw key, escapes not decoded) SURVIVED. The code itself is correct: the escaped duplicate was rejected in my probe. | Make line 146 use `"bomb.maxSize"` inside a raw string. |
| F7 | Low | `Classifier.kt:73` | `RejectReason.DUPLICATE_CARD` has no test, and no golden case expects it. | RAN: mutation M4 (check deleted) SURVIVED | Add `judge` with two cards that share one id and expect `DUPLICATE_CARD`. |
| F8 | Low | `Shapes.kt:33,52,73` | Option-on behaviour for D12 (wrap key), D14 (mixed joker pair key) and the J-Q-K-A-2 / Q-K-A-2-3 wrap windows is untested. | RAN: M10 (D14 key changed to max), M11 (D12 key changed to first card) and M29 (J-Q-K-A-2 window dropped) all SURVIVED | Add `RuleOptionBehaviourTest` assertions on the keys and on all three wrap windows. |
| F9 | Low | `RuleProfile.kt:42-43` | The rule that retired options still count in the hash is untested. | RAN: M3 (clause deleted) SURVIVED | Assert that the hash differs when a deprecated option is set. |
| F10 | Low | `RuleProfileLoader.kt:65-73`, `RuleSettings.kt:23` | `bomb.minSize` greater than `bomb.maxSize` loads silently and removes every same-rank bomb. | READ (no cross-field check exists) | Reject the profile with `OutOfRange` when min > max. |
| F11 | Low | `LargeBombCountOracleTest.kt:38,45,63` | The "independent" count oracle reuses production `Shapes.windows`, `Shapes.bomb`, `sequence` and `allowsBombSize`, so a defect there is mirrored, not caught. | READ | Build the expected windows and keys from literals in the test. |
| F12 | Low | `LegalActionOracleTest.kt:114` | The subset oracle stops at 8 cards, so it never reaches 9- or 10-card bombs or 9+ card interactions. | RAN: my 9–12-card oracle over 4 profiles (240 hands) found 0 mismatches. The gap is coverage, not a known bug. | Raise `MAX_HAND` to 11 or 12 for a few seeds (2^12 subsets is cheap). |
| F13 | Low | `Sha256Test.kt:36` (`CoreMultiplatformGuardTest`) | The guard regex misses `android.*` imports and fully qualified `java.` uses that do not appear in an import line. | READ. `grep` of `core/src/main` currently finds neither. | Extend the regex to `^\s*import\s+(java\|javax\|android)\.` and to `\bjava\.[a-z]`. |
| F14 | Low | `LegalActionGenerator.kt:190-192` | Action card ids are always "first copy of each face", so the ids depend on hand order. Same input gives the same output, but a permuted hand gives other ids. | RAN: hand reversed: same key order `true`, same card ids `false` | Document that WI-03 and WI-07 must match actions by face key, or sort the hand by id first. |
| F15 | Low | `Classifier.kt:105`, `LegalActionGenerator.kt:50` | The two-wildcards-per-deal fact (R1.8) is written twice, once as a named constant and once as a literal `2`. | READ | Use one shared constant in `Ranks` or `Card.kt`. |
| F16 | Low | `rules/golden/golden-v1.json` (levels used: 9, 2, 7) | No spec-anchored case at level A (R1.6): heart-A wildcards, A as level card, and A-low runs at level A. | READ. Only the random oracle visits level 14, and it checks generator against classifier, not against the spec. | Add golden cases at level A. |

### Notes on the findings

- **F1.** The registry entry cites "R3.3, R3.4" as its source, so the option currently overrides a sourced rule, not only the D1 default. If the rules reviewer intends wrap-on to include level 2, R3.3 needs an amendment that says so.
- **F2.** Today profiles come from bundled files. A profile received from a host in WI-05 would be untrusted input, and this is the case that matters.
- **F4.** D17 is tied to an existing option, which is fine. D18 holds by construction: with null and with 10 the settings differ, but the legal sets are the same (tested in `RuleOptionBehaviourTest.kt:38`).
- **Rows checked with a test that fails if the code breaks them:** R1.5, R1.7, R2.1–R2.16, R3.1–R3.5, R4.1–R4.9, R5.1–R5.5, R6.1–R6.4, R7.2–R7.6 and R7.8–R7.11, from the golden set plus the unit tests. Mutations of R1.5 (M7), R2.15 (M19), R4.9 / R5.3 (M6), R6.2 (M31) and R7.8 declaration size (M30) were all KILLED. R1.6 has no anchored test (F16). R7.7 (where the wildcard is placed) is a UI concern and not testable here.

## Verified by running code

Every run used JDK 21 with `./gradlew :core:test`. All runs except the baseline used a scratch copy at `/tmp/wpgd-review/repo` (since deleted), with a probe file `core/src/test/kotlin/cn/wpgd/app/core/rules/ScratchReviewTest.kt` and a mutation driver `/tmp/wpgd-review/tools/mutate.py`.

- **Baseline in the real repo** (`./gradlew :core:test --rerun-tasks`): 67 tests, 0 failures. The tests printed:
  - `GOLDEN total=139 run=137 passed=137 failed=0 skipped=2` (UN01 and UN02 are unsourced)
  - `ORACLE hands=450 follows=229 actions=2729`, covering all 10 types
  - `BUDGET actions=17334 median_us=2951`
- **Exhaustiveness:**
  - 7 targeted hands × 2 profiles (P0 and all options flipped): 0 generator/oracle mismatches (`targeted mismatches=0 of 14`). The hands were: mixed physical-plus-wildcard bombs; a straight flush completed by a wildcard; a level-7 run with wildcards as their natural self; both wildcards with all four jokers; level A with A-low and A-high flushes; J-Q-K-A-2 at level 2; a straight flush with a natural-self wildcard.
  - Random 9–12-card hands, 4 profiles: `random12 hands=240 mismatches=0`.
- **F1:** the two R3.3 readings quoted in the table.
- **F2, F5, F6:** loader and classifier error paths, as quoted in the table. Duplicate ids return `Rejected(DUPLICATE_CARD)`. An escaped duplicate key is rejected as `Malformed`.
- **Hash:**
  - Canonical JSON is key-sorted and does not depend on locale.
  - The hash changes with `provisional`, `displayName` and the order of `sourceRefs`.
  - An explicit `bomb.maxSize: 10` gives the same hash as leaving it at the default.
  - `null` and `10` give different hashes for the same legal set (D18; an observation, not a defect).
- **Determinism:** the same input gives an identical list. The reversed hand gives the same keys in the same order but different card ids (F14).
- **Performance (P0, 27 cards):**

  | Hand | P0 actions | P0 time | All options flipped |
  |---|---|---|---|
  | four suits × six ranks, 2 wildcards | 17,334 | 2.8 ms | 6.3 ms |
  | two cards per rank, 2 wildcards | 3,254 | 0.6 ms | — |
  | level-K runs | 8,669 | 1.4 ms | — |

  Nothing enumerates subsets of the full hand. `Classifier.readings` tries at most 91 rank multisets × 2 suits. The oracles are exponential, but they are test-only and capped.
- **Mutations** (each mutation applied alone, then the full `:core:test`):

  | Result | Mutations |
  |---|---|
  | SURVIVED | M1, M2, M3, M4, M10, M11, M24, M29 |
  | KILLED | M6, M7, M19, M30, M31 |

## Not verified

- Whether the section-9 defaults match how Guandan is actually played. That is the rules reviewer's call (G1a). This review checks code against the draft only.
- Kotlin/Native or JS compilation. The multiplatform check was a source grep plus reading the imports (only `kotlinx.serialization` and the Kotlin stdlib). No non-JVM target exists to build.
- Device performance. Timings are JVM-only and come from one warm machine.
- The behaviour of `kotlinx.serialization` JSON string escaping on non-JVM targets, which the hash relies on for non-ASCII `displayName` and `sourceRefs`.
- Whether WI-03 will match submitted plays to generated actions by face key or by card id (F14). That code does not exist yet.
- The golden set's own correctness, beyond running it. `tools/golden/audit.py` was not run.
