# Audit of golden-v1.json

**Date:** 2026-10-14
**Audited file:** `core/src/test/resources/fixtures/rules/golden-v1.json` (139 cases at the time of this audit)
**Against:** `rules/base-v1.md` (DRAFT, not signed) and the text extractions in `rules/sources/text/`
**Result:** final pass **0 FAIL**, 0 cross-case contradictions.

The audit shows that each expected result follows from the cited rule rows as this auditor reads them, that the cited quotes exist at the cited lines, and that the status and decision labels match the rule tags. It does **not** show that the rules are correct Guandan, and **no Guandan rules reviewer has checked this golden set**.

## Method

Each check re-opened files from disk. Nothing was taken from memory.

1. **Rule rows.** For each case, re-read every cited row `R*.*` in `base-v1.md` and its tag.
2. **Source files.** For each evidence ID in those tags (ranges like `E10–E21` expanded), open `rules/sources/text/<Key>.txt` and require the Appendix A quote to start on the cited line. Whitespace is ignored on both sides (NFKC). This covers 56 distinct evidence items.
3. **Status and decisions.** Recompute the case's decision set as the union of every `D*` named in its cited rows' tags. The case must list exactly that set. `sourced` means the set is empty, and `default-dependent` means it is not. Every decision listed must also appear in the case's `why` text. Every rule named in `why` must be in the case's `rules` list.
4. **Physical possibility.** Table cards, play cards and hand cards together must fit one 108-card deal: at most two copies of each exact card (R1.2). `wildcards_held` must equal the heart level cards actually present.
5. **Independent re-derivation.** An evaluator written from the rule text and the section-9 defaults (and from nothing else) recomputes every case:
   - the play's legal readings;
   - for a follow, whether the stated reading beats the table;
   - for a "must not beat" case, that **no** reading of the same cards beats the table;
   - the bomb size and the straight-flush flag;
   - the rejection reason;
   - whether `exclusive` is true;
   - whether the table's stated classification is a valid reading of the table cards.

   For generator cases it enumerates the bomb compositions (GE01) or every two-wildcard declaration (GE03).
6. **Cross-case consistency.** Two checks:
   - No two cases with the same setup may have different expected values.
   - No two follow cases may give the same matchup different results, and no two readings may each beat the other.
7. **Audit of the audit.** Ten deliberate errors were planted in a copy of the JSON: a wrong beat result, a wrong bomb size, a wrap-around made legal, a wrong triple rank, a wrong status, a third copy of one card, a missing generator reading, a wrong rejection reason, and two wrong "must not beat" results. The audit flagged all ten. It also reported the contradiction one of them created (SF01 vs SF03: each beats the other). So a green result here is not vacuous.

The audit script was kept outside the repository (at `/tmp/golden/audit.py`, run as `python3 -I audit.py <repo>`), the same arrangement base-v1's own audit used.

## Pass 0 (author's corrections before the first audit run)

| Case | Problem | Fix |
|---|---|---|
| WC02 | Description garbled. | Rewritten. |
| BS02 | Claimed the five 8s have only the bomb reading. That claim needs D8, but the brief asks for 4–8 card bombs to be sourced. | `exclusive` set to false; D8 is now tested only by T11. |
| SQ20 | Marked sourced. But its cards have another legal reading (wildcard as 10), so rejecting the stated declaration instead of re-reading the play is the R7.8 product rule. That makes it the same kind of case as WC16 and WC17, which were marked D6. | R7.8 added; status default-dependent (D6). |

## Pass 1

| Finding | Count | Cases | Fix |
|---|---|---|---|
| `why` cites a rule not listed in `rules` | 9 | T06, T08, T12, T22 (R1.5 / R2.15 / R5.1); BS05 (R1.2); SQ12, SQ13 (R3.3); WC16 (R2.8); WC24 (R7.9) | Added the missing rule to `rules` in eight cases. None of the additions added a decision. In WC24, R7.9 was named only to say it does **not** apply; listing it would wrongly make the case depend on D7, so the sentence was reworded instead. |
| Evaluator disagreement | 0 | | |
| Quote not found | 0 | | |
| Status or decision mismatch | 0 | | |
| Cross-case contradiction | 0 | | |

## Pass 2 (final): per case

"Rules re-read" lists the rows re-opened. "Quotes found at" lists the source file and line where each cited quote was found again. PRODUCT and UNSOURCED rows cite no evidence.

| Case | Status | Decisions | Rules re-read | Result | Reason |
|---|---|---|---|---|---|
| T01 | sourced | — | R2.1 | PASS | evaluator agrees; quotes found at JS17:39 |
| T02 | sourced | — | R2.1 | PASS | evaluator agrees; quotes found at JS17:39 |
| T03 | sourced | — | R2.2 | PASS | evaluator agrees; quotes found at CG22a:205, JS17:40 |
| T04 | sourced | — | R2.2 | PASS | evaluator agrees; quotes found at CG22a:205, JS17:40 |
| T05 | sourced | — | R2.2 | PASS | evaluator agrees; quotes found at CG22a:205, JS17:40 |
| T06 | default-dependent | D3 | R2.3, R6.4, R1.5 | PASS | evaluator agrees; quotes found at JS17:39, JS17:40, JS17:48, JS17:79 |
| T07 | sourced | — | R2.4 | PASS | evaluator agrees; quotes found at JS17:42 |
| T08 | sourced | — | R2.5, R2.15 | PASS | evaluator agrees; quotes found at HA17:152, JS17:44, JS17:80 |
| T09 | sourced | — | R2.5, R4.1 | PASS | evaluator agrees; quotes found at CG22a:211, HA17:152, HA17:160, JS17:44, JS17:47 |
| T10 | sourced | — | R2.5 | PASS | evaluator agrees; quotes found at HA17:152, JS17:44 |
| T11 | default-dependent | D8 | R2.6, R4.1 | PASS | evaluator agrees; quotes found at CG22a:211, HA17:160, JS17:47 |
| T12 | sourced | — | R2.8, R5.1 | PASS | evaluator agrees; quotes found at HA17:156, HA17:166, JS17:45, JS17:46, PD17:142 |
| T13 | sourced | — | R2.8 | PASS | evaluator agrees; quotes found at HA17:156, JS17:45, PD17:142 |
| T14 | sourced | — | R2.8 | PASS | evaluator agrees; quotes found at HA17:156, JS17:45, PD17:142 |
| T15 | sourced | — | R2.9 | PASS | evaluator agrees; quotes found at HA17:140, JS17:41 |
| T16 | sourced | — | R2.9, R4.1 | PASS | evaluator agrees; quotes found at CG22a:211, HA17:140, HA17:160, JS17:41, JS17:47 |
| T17 | sourced | — | R2.9 | PASS | evaluator agrees; quotes found at HA17:140, JS17:41 |
| T18 | sourced | — | R2.10 | PASS | evaluator agrees; quotes found at HA17:148, JS17:43 |
| T19 | sourced | — | R2.10 | PASS | evaluator agrees; quotes found at HA17:148, JS17:43 |
| T20 | sourced | — | R5.1 | PASS | evaluator agrees; quotes found at HA17:166, JS17:46 |
| T21 | sourced | — | R6.1 | PASS | evaluator agrees; quotes found at JS17:48 |
| T22 | default-dependent | D3 | R6.4, R1.5 | PASS | evaluator agrees; quotes found at JS17:39, JS17:40, JS17:48, JS17:79 |
| T23 | default-dependent | D3 | R2.7, R2.5 | PASS | evaluator agrees; quotes found at HA17:152, JS17:40, JS17:44 |
| T24 | default-dependent | D4 | R2.11 | PASS | evaluator agrees; quotes found at SEU:8 |
| BS01 | sourced | — | R4.1 | PASS | evaluator agrees; quotes found at CG22a:211, HA17:160, JS17:47 |
| BS02 | sourced | — | R4.1 | PASS | evaluator agrees; quotes found at CG22a:211, HA17:160, JS17:47 |
| BS03 | sourced | — | R4.1 | PASS | evaluator agrees; quotes found at CG22a:211, HA17:160, JS17:47 |
| BS04 | sourced | — | R4.1 | PASS | evaluator agrees; quotes found at CG22a:211, HA17:160, JS17:47 |
| BS05 | sourced | — | R4.1, R4.3, R1.2 | PASS | evaluator agrees; quotes found at CG22a:211, HA17:160, JS17:20, JS17:47 |
| BS06 | sourced | — | R4.2, R7.2 | PASS | evaluator agrees; quotes found at CG22a:199, HA17:201, HA17:212 |
| BS07 | sourced | — | R4.2, R7.2 | PASS | evaluator agrees; quotes found at CG22a:199, HA17:201, HA17:212 |
| BS08 | sourced | — | R4.1, R4.2, R7.5 | PASS | evaluator agrees; quotes found at CG22a:211, HA17:160, HA17:201, HA17:212, JS17:36, JS17:47 |
| BS09 | default-dependent | D2 | R4.5 | PASS | evaluator agrees; quotes found at HA17:212, JS17:20, JS17:47 |
| BS10 | default-dependent | D2 | R4.5 | PASS | evaluator agrees; quotes found at HA17:212, JS17:20, JS17:47 |
| BS11 | default-dependent | D2 | R4.4, R4.6 | PASS | evaluator agrees; quotes found at HA17:212, JS17:20, JS17:36, JS17:37, JS17:47, NJMU:15, SEU:5 |
| SQ01 | sourced | — | R2.8, R3.1, R3.2 | PASS | evaluator agrees; quotes found at HA17:143, HA17:148, HA17:156, JS17:45, JS17:79, PD17:142 |
| SQ02 | sourced | — | R2.8, R3.1, R3.2 | PASS | evaluator agrees; quotes found at HA17:143, HA17:148, HA17:156, JS17:45, JS17:79, PD17:142 |
| SQ03 | sourced | — | R2.9, R3.1, R3.2 | PASS | evaluator agrees; quotes found at HA17:140, HA17:143, HA17:148, HA17:156, JS17:41, JS17:79 |
| SQ04 | sourced | — | R2.9, R3.2 | PASS | evaluator agrees; quotes found at HA17:140, HA17:143, HA17:148, HA17:156, JS17:41 |
| SQ05 | sourced | — | R2.10, R3.1, R3.2 | PASS | evaluator agrees; quotes found at HA17:143, HA17:148, HA17:156, JS17:43, JS17:79 |
| SQ06 | sourced | — | R2.10, R3.2 | PASS | evaluator agrees; quotes found at HA17:143, HA17:148, HA17:156, JS17:43 |
| SQ07 | sourced | — | R3.3 | PASS | evaluator agrees; quotes found at HA17:143, HA17:156 |
| SQ08 | sourced | — | R3.3 | PASS | evaluator agrees; quotes found at HA17:143, HA17:156 |
| SQ09 | sourced | — | R3.5, R2.9 | PASS | evaluator agrees; quotes found at CG22a:231, HA17:140, HA17:143, HA17:250, JS17:41, WG25:177 |
| SQ10 | sourced | — | R3.2, R3.5, R2.9 | PASS | evaluator agrees; quotes found at CG22a:231, HA17:140, HA17:143, HA17:148, HA17:156, HA17:250, JS17:41, WG25:177 |
| SQ11 | default-dependent | D1 | R3.4 | PASS | evaluator agrees; quotes found at n/a (PRODUCT/UNSOURCED rows) |
| SQ12 | default-dependent | D1 | R3.4, R3.3 | PASS | evaluator agrees; quotes found at HA17:143, HA17:156 |
| SQ13 | default-dependent | D1 | R3.4, R3.3 | PASS | evaluator agrees; quotes found at HA17:143, HA17:156 |
| SQ14 | default-dependent | D1 | R3.4 | PASS | evaluator agrees; quotes found at n/a (PRODUCT/UNSOURCED rows) |
| SQ15 | sourced | — | R3.5, R2.8 | PASS | evaluator agrees; quotes found at CG22a:231, HA17:143, HA17:156, HA17:250, JS17:45, PD17:142, WG25:177 |
| SQ16 | sourced | — | R3.5 | PASS | evaluator agrees; quotes found at CG22a:231, HA17:143, HA17:250, WG25:177 |
| SQ17 | sourced | — | R3.5, R2.9 | PASS | evaluator agrees; quotes found at CG22a:231, HA17:140, HA17:143, HA17:250, JS17:41, WG25:177 |
| SQ18 | sourced | — | R3.5 | PASS | evaluator agrees; quotes found at CG22a:231, HA17:143, HA17:250, WG25:177 |
| SQ19 | sourced | — | R3.6, R7.4, R5.1, R3.1 | PASS | evaluator agrees; quotes found at CG22a:231, HA17:143, HA17:166, HA17:201, HA17:202, HA17:206, HA17:212, HA17:219, HA17:224, HA17:231, HA17:240, JS17:46, JS17:79 |
| SQ20 | default-dependent | D6 | R3.3, R3.6, R7.8 | PASS | evaluator agrees; quotes found at CG22a:231, HA17:143, HA17:156, HA17:201 |
| SQ21 | sourced | — | R7.6, R7.4, R2.8 | PASS | evaluator agrees; quotes found at HA17:156, HA17:201, HA17:202, HA17:206, HA17:212, HA17:219, HA17:224, HA17:231, HA17:240, HA17:251, JS17:45, PD17:142 |
| SQ22 | sourced | — | R7.6, R7.4, R2.8 | PASS | evaluator agrees; quotes found at HA17:156, HA17:201, HA17:202, HA17:206, HA17:212, HA17:219, HA17:224, HA17:231, HA17:240, HA17:251, JS17:45, PD17:142 |
| BR01 | sourced | — | R4.7 | PASS | evaluator agrees; quotes found at JS17:81 |
| BR02 | sourced | — | R4.7 | PASS | evaluator agrees; quotes found at JS17:81 |
| BR03 | sourced | — | R4.7 | PASS | evaluator agrees; quotes found at JS17:81 |
| BR04 | sourced | — | R4.8 | PASS | evaluator agrees; quotes found at HA17:256, JS17:79, JS17:81 |
| BR05 | sourced | — | R4.8 | PASS | evaluator agrees; quotes found at HA17:256, JS17:79, JS17:81 |
| BR06 | sourced | — | R4.8 | PASS | evaluator agrees; quotes found at HA17:256, JS17:79, JS17:81 |
| BR07 | sourced | — | R4.8, R2.13 | PASS | evaluator agrees; quotes found at CG22a:227, HA17:140, HA17:148, HA17:152, HA17:156, HA17:193, HA17:256, JS17:39, JS17:40, JS17:41, JS17:42, JS17:43, JS17:44, JS17:45, JS17:79, JS17:80, JS17:81, PD17:142 |
| BR08 | sourced | — | R4.8, R1.5 | PASS | evaluator agrees; quotes found at HA17:256, JS17:79, JS17:81 |
| BR09 | sourced | — | R4.8 | PASS | evaluator agrees; quotes found at HA17:256, JS17:79, JS17:81 |
| BR10 | sourced | — | R4.8, R4.2, R7.5 | PASS | evaluator agrees; quotes found at HA17:201, HA17:212, HA17:256, JS17:36, JS17:79, JS17:81 |
| BR11 | default-dependent | D2 | R4.5, R4.8 | PASS | evaluator agrees; quotes found at HA17:212, HA17:256, JS17:20, JS17:47, JS17:79, JS17:81 |
| BR12 | default-dependent | D2 | R4.5, R4.8 | PASS | evaluator agrees; quotes found at HA17:212, HA17:256, JS17:20, JS17:47, JS17:79, JS17:81 |
| BR13 | default-dependent | D2 | R4.4, R4.8 | PASS | evaluator agrees; quotes found at HA17:212, HA17:256, JS17:20, JS17:47, JS17:79, JS17:81, NJMU:15, SEU:5 |
| BR14 | sourced | — | R6.2, R4.9 | PASS | evaluator agrees; quotes found at CG22a:227, HA17:259, JS17:81, JS17:82, JS17:83 |
| BR15 | sourced | — | R6.2, R4.9 | PASS | evaluator agrees; quotes found at CG22a:227, HA17:259, JS17:81, JS17:82, JS17:83 |
| BR16 | default-dependent | D2 | R6.2, R4.4 | PASS | evaluator agrees; quotes found at HA17:212, HA17:259, JS17:20, JS17:47, JS17:83, NJMU:15, SEU:5 |
| BR17 | default-dependent | D2 | R6.2, R4.4 | PASS | evaluator agrees; quotes found at HA17:212, HA17:259, JS17:20, JS17:47, JS17:83, NJMU:15, SEU:5 |
| SF01 | sourced | — | R5.3 | PASS | evaluator agrees; quotes found at CG22a:227, JS17:81, JS17:82, WG25:183 |
| SF02 | sourced | — | R5.3 | PASS | evaluator agrees; quotes found at CG22a:227, JS17:81, JS17:82, WG25:183 |
| SF03 | sourced | — | R5.3, R4.9 | PASS | evaluator agrees; quotes found at CG22a:227, JS17:81, JS17:82, JS17:83, WG25:183 |
| SF04 | sourced | — | R5.3, R4.9 | PASS | evaluator agrees; quotes found at CG22a:227, JS17:81, JS17:82, JS17:83, WG25:183 |
| SF05 | sourced | — | R5.3, R4.9 | PASS | evaluator agrees; quotes found at CG22a:227, JS17:81, JS17:82, JS17:83, WG25:183 |
| SF06 | sourced | — | R5.3, R4.9 | PASS | evaluator agrees; quotes found at CG22a:227, JS17:81, JS17:82, JS17:83, WG25:183 |
| SF07 | sourced | — | R4.2, R7.2, R5.3 | PASS | evaluator agrees; quotes found at CG22a:199, CG22a:227, HA17:201, HA17:212, JS17:81, JS17:82, WG25:183 |
| SF08 | sourced | — | R5.3 | PASS | evaluator agrees; quotes found at CG22a:227, JS17:81, JS17:82, WG25:183 |
| SF09 | default-dependent | D5 | R5.4, R2.16 | PASS | evaluator agrees; quotes found at JS17:79, JS17:80, JS17:82, YZU:6, YZU:8 |
| SF10 | default-dependent | D5 | R5.4, R2.16, R1.7 | PASS | evaluator agrees; quotes found at CG22a:225, JS17:79, JS17:80, JS17:82, YZU:6, YZU:8 |
| SF11 | sourced | — | R6.2, R5.3 | PASS | evaluator agrees; quotes found at CG22a:227, HA17:259, JS17:81, JS17:82, JS17:83, WG25:183 |
| SF12 | sourced | — | R5.1, R3.1 | PASS | evaluator agrees; quotes found at HA17:143, HA17:166, JS17:46, JS17:79 |
| SF13 | sourced | — | R5.1, R3.1 | PASS | evaluator agrees; quotes found at HA17:143, HA17:166, JS17:46, JS17:79 |
| WC01 | sourced | — | R7.2, R7.4, R2.2, R2.13 | PASS | evaluator agrees; quotes found at CG22a:199, CG22a:205, CG22a:227, HA17:140, HA17:148, HA17:152, HA17:156, HA17:193, HA17:201, HA17:202, HA17:206, HA17:212, HA17:219, HA17:224, HA17:231, HA17:240, JS17:39, JS17:40, JS17:41, JS17:42, JS17:43, JS17:44, JS17:45, JS17:80, PD17:142 |
| WC02 | sourced | — | R7.2, R2.4 | PASS | evaluator agrees; quotes found at CG22a:199, HA17:201, JS17:42 |
| WC03 | sourced | — | R7.3 | PASS | evaluator agrees; quotes found at CG22a:199, HA17:201 |
| WC04 | sourced | — | R6.3, R7.3 | PASS | evaluator agrees; quotes found at CG22a:199, HA17:201 |
| WC05 | sourced | — | R7.6, R2.5 | PASS | evaluator agrees; quotes found at HA17:152, HA17:206, HA17:251, JS17:44 |
| WC06 | sourced | — | R7.6, R2.5 | PASS | evaluator agrees; quotes found at HA17:152, HA17:206, HA17:251, JS17:44 |
| WC07 | sourced | — | R7.6, R2.15 | PASS | evaluator agrees; quotes found at HA17:206, HA17:251, JS17:80 |
| WC08 | sourced | — | R7.6, R2.15 | PASS | evaluator agrees; quotes found at HA17:206, HA17:251, JS17:80 |
| WC09 | sourced | — | R7.6, R2.10 | PASS | evaluator agrees; quotes found at HA17:148, HA17:206, HA17:251, JS17:43 |
| WC10 | sourced | — | R7.6, R2.9 | PASS | evaluator agrees; quotes found at HA17:140, HA17:206, HA17:251, JS17:41 |
| WC11 | sourced | — | R7.6, R7.2, R2.9 | PASS | evaluator agrees; quotes found at CG22a:199, HA17:140, HA17:201, HA17:206, HA17:251, JS17:41 |
| WC12 | sourced | — | R7.4, R5.1 | PASS | evaluator agrees; quotes found at HA17:166, HA17:201, HA17:202, HA17:206, HA17:212, HA17:219, HA17:224, HA17:231, HA17:240, JS17:46 |
| WC13 | default-dependent | D6, D9 | R7.11, R7.10 | PASS | evaluator agrees; quotes found at HA17:201, JS17:46 |
| WC14 | sourced | — | R7.4, R2.9 | PASS | evaluator agrees; quotes found at HA17:140, HA17:201, HA17:202, HA17:206, HA17:212, HA17:219, HA17:224, HA17:231, HA17:240, JS17:41 |
| WC15 | sourced | — | R7.4, R2.10 | PASS | evaluator agrees; quotes found at HA17:148, HA17:201, HA17:202, HA17:206, HA17:212, HA17:219, HA17:224, HA17:231, HA17:240, JS17:43 |
| WC16 | default-dependent | D6 | R7.8, R2.8 | PASS | evaluator agrees; quotes found at HA17:156, JS17:45, PD17:142 |
| WC17 | default-dependent | D6 | R7.8 | PASS | evaluator agrees; quotes found at n/a (PRODUCT/UNSOURCED rows) |
| WC18 | default-dependent | D6 | R7.8 | PASS | evaluator agrees; quotes found at n/a (PRODUCT/UNSOURCED rows) |
| WC19 | default-dependent | D7 | R7.9, R1.5 | PASS | evaluator agrees; quotes found at JS17:79 |
| WC20 | default-dependent | D7 | R7.9 | PASS | evaluator agrees; quotes found at n/a (PRODUCT/UNSOURCED rows) |
| WC21 | default-dependent | D7 | R7.9, R7.3, R1.5 | PASS | evaluator agrees; quotes found at CG22a:199, HA17:201, JS17:79 |
| WC22 | default-dependent | D7 | R7.9, R1.5, R2.15 | PASS | evaluator agrees; quotes found at JS17:79, JS17:80 |
| WC23 | default-dependent | D7 | R7.9 | PASS | evaluator agrees; quotes found at n/a (PRODUCT/UNSOURCED rows) |
| WC24 | sourced | — | R7.5, R2.2, R1.5 | PASS | evaluator agrees; quotes found at CG22a:205, HA17:201, JS17:36, JS17:40, JS17:79 |
| WC25 | sourced | — | R7.5, R3.5, R5.1 | PASS | evaluator agrees; quotes found at CG22a:231, HA17:143, HA17:166, HA17:201, HA17:250, JS17:36, JS17:46, WG25:177 |
| FO01 | sourced | — | R2.14, R2.13 | PASS | evaluator agrees; quotes found at CG22a:227, HA17:140, HA17:148, HA17:152, HA17:156, HA17:193, JS17:39, JS17:40, JS17:41, JS17:42, JS17:43, JS17:44, JS17:45, JS17:80, PD17:142 |
| FO02 | sourced | — | R2.14, R2.13 | PASS | evaluator agrees; quotes found at CG22a:227, HA17:140, HA17:148, HA17:152, HA17:156, HA17:193, JS17:39, JS17:40, JS17:41, JS17:42, JS17:43, JS17:44, JS17:45, JS17:80, PD17:142 |
| FO03 | sourced | — | R2.14, R2.13 | PASS | evaluator agrees; quotes found at CG22a:227, HA17:140, HA17:148, HA17:152, HA17:156, HA17:193, JS17:39, JS17:40, JS17:41, JS17:42, JS17:43, JS17:44, JS17:45, JS17:80, PD17:142 |
| FO04 | sourced | — | R1.7, R2.13 | PASS | evaluator agrees; quotes found at CG22a:225, CG22a:227, HA17:140, HA17:148, HA17:152, HA17:156, HA17:193, JS17:39, JS17:40, JS17:41, JS17:42, JS17:43, JS17:44, JS17:45, JS17:80, PD17:142 |
| FO05 | sourced | — | R1.5, R2.15 | PASS | evaluator agrees; quotes found at JS17:79, JS17:80 |
| FO06 | sourced | — | R1.5, R2.15 | PASS | evaluator agrees; quotes found at JS17:79, JS17:80 |
| FO07 | sourced | — | R1.5, R2.15 | PASS | evaluator agrees; quotes found at JS17:79, JS17:80 |
| FO08 | sourced | — | R2.2, R1.5, R2.15 | PASS | evaluator agrees; quotes found at CG22a:205, JS17:40, JS17:79, JS17:80 |
| FO09 | sourced | — | R2.2, R1.5, R2.15 | PASS | evaluator agrees; quotes found at CG22a:205, JS17:40, JS17:79, JS17:80 |
| FO10 | sourced | — | R2.15, R2.13 | PASS | evaluator agrees; quotes found at CG22a:227, HA17:140, HA17:148, HA17:152, HA17:156, HA17:193, JS17:39, JS17:40, JS17:41, JS17:42, JS17:43, JS17:44, JS17:45, JS17:80, PD17:142 |
| FO11 | sourced | — | R2.15 | PASS | evaluator agrees; quotes found at JS17:80 |
| FO12 | sourced | — | R2.15, R2.13 | PASS | evaluator agrees; quotes found at CG22a:227, HA17:140, HA17:148, HA17:152, HA17:156, HA17:193, JS17:39, JS17:40, JS17:41, JS17:42, JS17:43, JS17:44, JS17:45, JS17:80, PD17:142 |
| FO13 | sourced | — | R2.15, R1.5 | PASS | evaluator agrees; quotes found at JS17:79, JS17:80 |
| FO14 | default-dependent | D5 | R2.16, R2.13 | PASS | evaluator agrees; quotes found at CG22a:227, HA17:140, HA17:148, HA17:152, HA17:156, HA17:193, JS17:39, JS17:40, JS17:41, JS17:42, JS17:43, JS17:44, JS17:45, JS17:79, JS17:80, PD17:142, YZU:6, YZU:8 |
| FO15 | default-dependent | D5 | R2.16, R3.1 | PASS | evaluator agrees; quotes found at HA17:143, JS17:79, JS17:80, YZU:6, YZU:8 |
| FO16 | default-dependent | D5 | R2.16, R1.7, R2.13 | PASS | evaluator agrees; quotes found at CG22a:225, CG22a:227, HA17:140, HA17:148, HA17:152, HA17:156, HA17:193, JS17:39, JS17:40, JS17:41, JS17:42, JS17:43, JS17:44, JS17:45, JS17:79, JS17:80, PD17:142, YZU:6, YZU:8 |
| FO17 | default-dependent | D5 | R2.16 | PASS | evaluator agrees; quotes found at JS17:79, JS17:80, YZU:6, YZU:8 |
| FO18 | default-dependent | D5 | R2.16, R3.1 | PASS | evaluator agrees; quotes found at HA17:143, JS17:79, JS17:80, YZU:6, YZU:8 |
| FO19 | default-dependent | D5 | R2.16, R3.2 | PASS | evaluator agrees; quotes found at HA17:143, HA17:148, HA17:156, JS17:79, JS17:80, YZU:6, YZU:8 |
| FO20 | default-dependent | D9 | R5.5, R5.3 | PASS | evaluator agrees; quotes found at CG22a:227, JS17:46, JS17:81, JS17:82, WG25:183 |
| FO21 | default-dependent | D3 | R2.15, R2.7 | PASS | evaluator agrees; quotes found at JS17:40, JS17:44, JS17:80 |
| GE01 | default-dependent | D2, D6 | R4.1, R4.2, R4.4, R4.5, R7.8 | PASS | evaluator agrees; quotes found at CG22a:211, HA17:160, HA17:212, JS17:20, JS17:47, NJMU:15, SEU:5 |
| GE02 | sourced | — | R6.1, R6.3 | PASS | evaluator agrees; quotes found at CG22a:199, HA17:201, JS17:48 |
| GE03 | default-dependent | D6 | R7.6, R7.8, R7.10, R2.9, R2.10 | PASS | evaluator agrees; quotes found at HA17:140, HA17:148, HA17:206, HA17:251, JS17:41, JS17:43 |
| PR01 | default-dependent | D10 | R8.1 | PASS | R8.1 text says unknown option keys are rejected; quotes found at n/a (PRODUCT/UNSOURCED rows) |
| UN01 | unsourced | — | R7.8, R7.9 | PASS | no expected result (unresolved); quotes found at n/a (PRODUCT/UNSOURCED rows) |
| UN02 | unsourced | — | R7.10 | PASS | no expected result (unresolved); quotes found at n/a (PRODUCT/UNSOURCED rows) |

## Cross-case consistency

| Check | Result |
|---|---|
| Same setup, different expected | none |
| Same matchup (play reading vs table reading at one level), different result | none |
| Two readings each beating the other | none |
| `default-dependent` case without a named decision | none |
| Every expected value derived from one evaluator under one set of defaults | yes, so the cases cannot contradict each other unless the evaluator contradicts itself |

## Decisions named by default-dependent cases

| Decision | Cases |
|---|---|
| D1 wrap-around | SQ11, SQ12, SQ13, SQ14 |
| D2 9- and 10-card bombs | BS09, BS10, BS11, BR11, BR12, BR13, BR16, BR17, GE01 |
| D3 joker-only plays | T06, T22, T23, FO21 |
| D4 jokers in sequences | T24 |
| D5 sequence comparison | SF09, SF10, FO14, FO15, FO16, FO17, FO18, FO19 |
| D6 explicit declarations | SQ20, WC13, WC16, WC17, WC18, GE01, GE03 |
| D7 lone wildcards | WC19, WC20, WC21, WC22, WC23 |
| D8 777+77 | T11 |
| D9 same-suit runs | WC13, FO20 |
| D10 profile options | PR01 |

## Source-authority exposure (D11)

D11 asks whether the reposted national texts (HA17, CG22a) are faithful. It is not a tag on any rule row, so it does not change any case's status. But 42 cases cite at least one rule whose only cited evidence is HA17 or CG22a, with no JS17, WG25 or PD17 quote among that rule's evidence. If the reposts turn out to be wrong, these are the cases to re-check:

| Rules whose cited evidence is reposts only | R1.7, R3.2, R3.3, R3.6, R4.2, R6.3, R7.2, R7.3, R7.4, R7.6 |
|---|---|
| Cases | BR10, BS06, BS07, BS08, FO04, FO16, FO19, GE01, GE02, GE03, SF07, SF10, SQ01–SQ08, SQ10, SQ12, SQ13, SQ19–SQ22, WC01–WC12, WC14, WC15, WC21 |

## Limits of this audit

- **One author.** The cases, the evaluator and this audit were all written by the same author from the same reading of `base-v1.md`. An error in that reading would show up in all three and pass. No second reviewer, human or model, has checked them.
- **No rules-reviewer review.** The audit can show that a result follows from the draft. It cannot show that the draft is how Guandan is played.
- **Format choices are not rules.** Three choices come from this golden set, not from `base-v1.md`:
  - natural self is written as `as_rank` = level rank (see UN01);
  - suits are written only for straight flushes (R7.10);
  - the `rejected_reason` labels are suggested names.
- **The text extractions are taken as given.** As in the base-v1 audit, the sources are checked only through the local text extractions. A repost error cannot be detected here (D11).
