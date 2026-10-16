# Audit of base-v1.md (G1a draft)

**Date:** 2026-10-14
**Audited file:** `rules/base-v1.md` (status DRAFT — not signed)
**Result:** after fixes, **0 FAIL**. The audit shows that every cited quote exists at the cited place and that every rule row has a source tag or an Unresolved decision. It does **not** show that the rules are correct Guandan. Only the rules reviewer can judge that.

## Method

Two checks, each run against the source text files reopened from disk, not from memory.

1. **Mechanical check** (`verify.py`, kept outside the repo at `/tmp/g1a/verify.py`, run as `python3 -I verify.py <repo>`):
   - For each evidence item in Appendix A, open `rules/sources/text/<Key>.txt`, take the cited line plus the next two lines, remove all whitespace (NFKC-normalised on both sides), and require the quote to appear starting on the cited line.
   - For each rule row `R*.*` in sections 1–8: require a trailing `[...]` tag; require it to cite an evidence ID that exists, or say `UNSOURCED`/`PRODUCT` with a decision `D*` defined in section 9; FAIL any row that relies on template text (SEU, YZU, NJMU, or a `TPL` mark) without naming a decision, because that would present template-only text as settled.
2. **Manual check:** for every rule row, re-read the cited quotes and judge whether they support the rule as worded (not just whether the quote exists).
3. **Coverage check:** compare the draft with the WI-02 tests listed in `dev-docs/implementation-plan.md` (WI-02, "First failing tests" and "Audit regressions").

The text files were made from the saved originals with `textutil -convert txt` (`.doc`, `.html`) and `pdftotext -layout` (`.pdf`). Their SHA-256 hashes are in the source sidecars.

## Pass 1

| Finding | Count | Cause | Action |
|---|---|---|---|
| Evidence quote FAIL | 30 | **Verifier bug, not a spec error.** The verifier split files with Python `splitlines()`, which also breaks on Unicode separators (for example U+2028) that appear in the HTML extractions. Its line numbers drifted from `grep -n` and from the cited lines. All 30 were HA17, YZU, PD17 and WG25 lines. | Verifier changed to split on `\n` only. Re-run: all 63 quotes PASS at the cited lines. No spec change. |
| Rule tag FAIL (mechanical) | 3 | R2.13, R4.4 and R5.2 relied on template-only text (E26, E36/E37, E43) but named no decision, so they read as settled. | Fixed: see rule table notes. |
| Rule support FAIL (manual) | 3 | R2.12 cited E25, whose quote did not contain the "leader may lead any type" sentence. R3.3 stated a general principle that the source shows only by two level-2 examples. R7.4 claimed "any suit" without citing E48. | Fixed: E25 quote extended (same line, re-verified); R3.3 narrowed, general rule moved into R3.4/D1; E48 added to R7.4. |
| WI-02 coverage gap | 2 | The draft did not say (a) whether a wildcard's suit is part of the declaration outside straight flushes, which decides how many actions the generator must emit; (b) whether a wildcard that completes a same-suit run may be declared as an ordinary straight. | Added R7.10 (`PRODUCT — D6`) and R7.11 (`DERIVED — D9`); D6 and D9 updated. |

Pass 1 total: 6 genuine FAILs in the spec (3 mechanical, 3 manual) and 2 coverage gaps. 30 further FAILs came from the verifier bug.

## Pass 2 (final): evidence quotes

All quotes re-checked against the cited file and line. Quotes are shown shortened here; the full quote is in Appendix A of the spec.

| Evidence | File | Line | Quote (start) | Result |
|---|---|---|---|---|
| E01 | `text/JS17.txt` | 20 | 全副牌共有每种花色牌各26张（2、3、4、5、6、7、8… | PASS |
| E02 | `text/JS17.txt` | 21 | 每位牌手应持有的全部牌张为27张 | PASS |
| E03 | `text/JS17.txt` | 36 | 牌点与上副牌上游所升至级数相同的所有花色牌为级牌，首副牌… | PASS |
| E04 | `text/CG22a.txt` | 199 | 相同的牌为级牌，共8张 | PASS |
| E05 | `text/JS17.txt` | 79 | 牌点由大到小排列为：大王、小王、级牌、A、K、Q、J、1… | PASS |
| E06 | `text/HA17.txt` | 199 | 打A时A就是参谋 | PASS |
| E07 | `text/CG22a.txt` | 225 | 各种牌型的大小均不比较花色。 | PASS |
| E08 | `text/JS17.txt` | 37 | 红心级牌（逢人配）可以和任意牌点的花色牌搭配成合理牌型使… | PASS |
| E09 | `text/HA17.txt` | 96 | 每副牌都增加了2张红心级牌(又称红心参谋、逢人配、百搭) | PASS |
| E10 | `text/JS17.txt` | 39 | 单张：任意一张牌。 | PASS |
| E11 | `text/JS17.txt` | 40 | 对子：两张牌点相同的牌，包括两张大王和两张小王。 | PASS |
| E12 | `text/JS17.txt` | 42 | 三同张：三张牌点相同的牌。 | PASS |
| E13 | `text/JS17.txt` | 44 | 三带二：一个三同张和一个对子。 | PASS |
| E14 | `text/HA17.txt` | 152 | 三同张不可以带一张牌，也不可以带二张牌点不同的牌。 | PASS |
| E15 | `text/JS17.txt` | 45 | 顺子：五张牌点相邻的单张。 | PASS |
| E16 | `text/HA17.txt` | 156 | 五张且只能五张相连的单张牌，花色可以不限。 | PASS |
| E17 | `text/PD17.txt` | 142 | 即五张且只能五张相连的单张牌，花色不限 | PASS |
| E18 | `text/JS17.txt` | 41 | 三连对：三个牌点相邻对子。 | PASS |
| E19 | `text/HA17.txt` | 140 | 必须而且只能是三连对作为一手牌同时打出，不可以二连对，如… | PASS |
| E20 | `text/JS17.txt` | 43 | 二连三（钢板）：两个牌点相邻的三同张牌。 | PASS |
| E21 | `text/HA17.txt` | 148 | 一手牌不可以有三个或三个以上相连的三同张牌，如:JJJ … | PASS |
| E22 | `text/SEU.txt` | 8 | 不包括双王 | PASS |
| E24 | `text/JS17.txt` | 80 | 同牌型比较牌点大小，牌型之间不能压牌。三带二仅比较三同张… | PASS |
| E25 | `text/HA17.txt` | 193 | 领出牌时可以出任意合理牌型，其余三家须按照相同牌型按顺序… | PASS |
| E26 | `text/YZU.txt` | 15 | 只有当牌型相同和总张数相同的牌，才可比较大小 | PASS |
| E27 | `text/JS17.txt` | 79 | A在搭配成三连对、二连三、顺子、同花顺时，可作1。 | PASS |
| E28 | `text/HA17.txt` | 143 | 当2是参谋时，不可以KK AA 22打出，只能算22 3… | PASS |
| E29 | `text/HA17.txt` | 156 | 可以A、2、3、4、5或10、J、Q、K、A，而不能组成… | PASS |
| E30 | `text/CG22a.txt` | 231 | 级牌在顺子、三连对或三同连张的牌型组合中，只能按级牌牌点… | PASS |
| E31 | `text/WG25.txt` | 177 | 级牌在搭配成三连对、二连三、顺子、同花顺时，作原牌点。 | PASS |
| E32 | `text/HA17.txt` | 143 | 一对参谋可以按其自然顺序参加三连对同时打出。 | PASS |
| E33 | `text/JS17.txt` | 47 | 炸弹：四张或四张以上牌点相同的单张。 | PASS |
| E34 | `text/CG22a.txt` | 211 | 炸弹：四张或四张以上牌点相同的牌，如:2222、3333… | PASS |
| E35 | `text/HA17.txt` | 212 | 四张及以上炸弹：如666(任意花色)+红心2，算四张6等 | PASS |
| E36 | `text/NJMU.txt` | 15 | 如2张红桃主牌和八张一起，可变为10张的炸弹。 | PASS |
| E37 | `text/SEU.txt` | 5 | 如和八张炸弹一起，可变为 10 张炸弹 | PASS |
| E38 | `text/JS17.txt` | 81 | 炸弹：张数多者大，张数相同比较牌点大小。炸弹可压单张、对… | PASS |
| E39 | `text/JS17.txt` | 82 | 同花顺：比较牌点大小。同花顺可压不超过5张的炸弹。 | PASS |
| E40 | `text/CG22a.txt` | 227 | 四大天王＞超过五张数的炸弹＞同花顺＞五张数炸弹＞四张数炸… | PASS |
| E41 | `text/JS17.txt` | 46 | 同花顺（火箭）：花色相同的顺子。 | PASS |
| E42 | `text/HA17.txt` | 166 | 五张且只能五张相连、花色相同的顺子 | PASS |
| E43 | `text/YZU.txt` | 6 | 最大的为同花 10-J-Q-K-A ，最小的为 A- 2… | PASS |
| E44 | `text/YZU.txt` | 8 | 最大的是 AAKKQQ, 最小的是 AA2233 | PASS |
| E45 | `text/WG25.txt` | 183 | 同花顺可压单张、对子、三连对、三同张、二连三、三带对、顺… | PASS |
| E46 | `text/JS17.txt` | 48 | 四大天王（天王炸）：大王、小王各两张。 | PASS |
| E47 | `text/JS17.txt` | 83 | 四大天王：可压所有牌型。 | PASS |
| E48 | `text/HA17.txt` | 201 | 红心X可作为万能牌配用，称为红心参谋（俗称逢人配或百搭，… | PASS |
| E49 | `text/CG22a.txt` | 199 | 红心级牌可以替代任意牌张（不含大、小王）参与不同牌型的组… | PASS |
| E50 | `text/HA17.txt` | 202 | 对子：梅花8+红心2，算一对8 | PASS |
| E51 | `text/HA17.txt` | 206 | 三带对：KK+66(可任意配不同花色)+红心2，可算三张… | PASS |
| E52 | `text/HA17.txt` | 219 | 三连对：55677或55667+红心2，均算556677… | PASS |
| E53 | `text/HA17.txt` | 224 | 顺子：4568或4567(任意杂花色)+红心2，均可算4… | PASS |
| E54 | `text/HA17.txt` | 231 | 同花顺：789J 或78910(方片)+红心2，均可算7… | PASS |
| E55 | `text/HA17.txt` | 240 | 三同连张：33344 或33444(任意花色)+红心2，… | PASS |
| E56 | `text/HA17.txt` | 251 | 当二个红心参谋加在二个连对打出，如与７７８８ 同时打出时… | PASS |
| E57 | `text/HA17.txt` | 251 | 当配在顺子的两端时，须说明算大点还是算小点。 | PASS |
| E58 | `text/JS17.txt` | 86 | 红心级牌搭配牌型时，应摆放在所充当牌点的位置，必要时说明… | PASS |
| E59 | `text/CG22a.txt` | 232 | 当红桃级牌搭配牌型时，应将红桃级牌摆放于所替代的牌点位置… | PASS |
| E60 | `text/HA17.txt` | 259 | 四大天王：4张大小王作为一手牌齐出，是最大的炸弹，可以压… | PASS |
| E61 | `text/HA17.txt` | 160 | 炸弹：四张或四张以上牌点相同的牌，如:4444、JJJJ… | PASS |
| E62 | `text/HA17.txt` | 250 | 参谋既可以大于Ａ，也允许按自然顺序插在顺子或三连对、三同… | PASS |
| E63 | `text/HA17.txt` | 256 | 张数多的炸弹可以压任何张数少的炸弹。如果炸弹的牌张数相同… | PASS |
| E64 | `text/CG22a.txt` | 205 | 对子：两张牌点相同的牌型，包括两张大王和两张小王 | PASS |
E23 is not assigned. E26 (YZU line 15, template) is kept in Appendix A as corroboration but no longer cited by any rule.

## Pass 2 (final): rule tags

| Rule | Tag | Mechanical | Manual support | Note |
|---|---|---|---|---|
| R1.1 | `[E01]` | PASS | PASS |  |
| R1.2 | `[DERIVED from E01]` | PASS | PASS | DERIVED, no decision: counting from E01. |
| R1.3 | `[E02]` | PASS | PASS |  |
| R1.4 | `[E03, E04]` | PASS | PASS |  |
| R1.5 | `[E05]` | PASS | PASS |  |
| R1.6 | `[E06]` | PASS | PASS |  |
| R1.7 | `[E07]` | PASS | PASS |  |
| R1.8 | `[E08, E09]` | PASS | PASS |  |
| R2.1 | `[E10]` | PASS | PASS |  |
| R2.2 | `[E11, E64]` | PASS | PASS |  |
| R2.3 | `[DERIVED from E11, E05 — confirm in D3]` | PASS | PASS |  |
| R2.4 | `[E12]` | PASS | PASS |  |
| R2.5 | `[E13, E14]` | PASS | PASS |  |
| R2.6 | `[UNSOURCED — D8]` | PASS | PASS |  |
| R2.7 | `[DERIVED from E13, E11 — confirm in D3]` | PASS | PASS |  |
| R2.8 | `[E15, E16, E17]` | PASS | PASS |  |
| R2.9 | `[E18, E19]` | PASS | PASS |  |
| R2.10 | `[E20, E21]` | PASS | PASS |  |
| R2.11 | `[E22 TPL — confirm in D4]` | PASS | PASS |  |
| R2.12 | `[E25]` | PASS | PASS | Pass 1 FAIL (manual): E25's quote lacked the lead sentence. Fixed by extending E25 to include 领出牌时可以出任意合理牌型. |
| R2.13 | `[E25, E24, E40; same card count DERIVED from E10–E21]` | PASS | PASS | Pass 1 FAIL (mechanical): cited template E26 for 'same card count' as settled. Fixed: E26 removed; same count derived from fixed type sizes. |
| R2.14 | `[E24]` | PASS | PASS |  |
| R2.15 | `[E24]` | PASS | PASS |  |
| R2.16 | `[DERIVED from E24, E27; extremes E43, E44 TPL — confirm in D5]` | PASS | PASS |  |
| R3.1 | `[E27, E28]` | PASS | PASS |  |
| R3.2 | `[E28, E29, E21]` | PASS | PASS |  |
| R3.3 | `[E28, E29]` | PASS | PASS | Pass 1 FAIL (manual): stated 'A cannot be high and low' as a general rule; sources give only two level-2 examples. Fixed: narrowed to the examples; general rule moved to R3.4 / D1. |
| R3.4 | `[UNSOURCED — D1]` | PASS | PASS |  |
| R3.5 | `[E30, E31, E32, E62, E28]` | PASS | PASS |  |
| R3.6 | `[DERIVED from E48, E30]` | PASS | PASS | DERIVED, no decision: E48 + E30 applied together. |
| R4.1 | `[E33, E34, E61]` | PASS | PASS |  |
| R4.2 | `[E35]` | PASS | PASS |  |
| R4.3 | `[DERIVED from E01, E33]` | PASS | PASS | DERIVED, no decision: 8 cards per rank from E01. |
| R4.4 | `[E36, E37 TPL; DERIVED from E33, E35, E01 — confirm in D2]` | PASS | PASS | Pass 1 FAIL (mechanical): 10-card bomb named only by template, stated as settled. Fixed: now routed to D2. |
| R4.5 | `[DERIVED from E33, E35, E01 — confirm in D2]` | PASS | PASS |  |
| R4.6 | `[DERIVED from E01, E03, E08 — confirm in D2]` | PASS | PASS |  |
| R4.7 | `[E38]` | PASS | PASS |  |
| R4.8 | `[E38, E63, E05]` | PASS | PASS |  |
| R4.9 | `[E40, E38, E39, E47]` | PASS | PASS |  |
| R5.1 | `[E41, E42]` | PASS | PASS |  |
| R5.2 | `[E27, E41; order E43 TPL — confirm in D5]` | PASS | PASS | Pass 1 FAIL (mechanical): lowest/highest order from template only, stated as settled. Fixed: order routed to D5. |
| R5.3 | `[E39, E45, E38, E40]` | PASS | PASS |  |
| R5.4 | `[E39, E24 — confirm in D5]` | PASS | PASS |  |
| R5.5 | `[DERIVED from E41 — confirm in D9]` | PASS | PASS |  |
| R6.1 | `[E46]` | PASS | PASS |  |
| R6.2 | `[E47, E60]` | PASS | PASS |  |
| R6.3 | `[E48, E49]` | PASS | PASS |  |
| R6.4 | `[DERIVED from E10, E11, E46 — confirm in D3]` | PASS | PASS |  |
| R7.1 | `[E08, E48]` | PASS | PASS |  |
| R7.2 | `[E48, E49]` | PASS | PASS |  |
| R7.3 | `[E48, E49]` | PASS | PASS |  |
| R7.4 | `[E48, E50, E51, E35, E52, E53, E54, E55]` | PASS | PASS | Pass 1 FAIL (manual): 'any suit' not covered by cited examples. Fixed: E48 added. |
| R7.5 | `[DERIVED from E03, E48]` | PASS | PASS | DERIVED, no decision: heart level card is a level card (E03). |
| R7.6 | `[E56, E51, E57]` | PASS | PASS |  |
| R7.7 | `[E48, E58, E59]` | PASS | PASS |  |
| R7.8 | `[PRODUCT — D6]` | PASS | PASS |  |
| R7.9 | `[UNSOURCED — D7]` | PASS | PASS |  |
| R7.10 | `[PRODUCT — D6]` | PASS | PASS | Added after the WI-02 coverage check (suit in declarations). |
| R7.11 | `[DERIVED from E48, E41 — confirm in D9]` | PASS | PASS | Added after the WI-02 coverage check (wildcard-completed same-suit run). |
| R8.1 | `[PRODUCT — D10]` | PASS | PASS |  |
## Template-only clauses

Clauses whose only explicit statement is the shared university template (SEU, YZU, NJMU; one source, not three). Each one is now routed to a decision rather than stated as settled:

| Rule | Template evidence | Decision |
|---|---|---|
| R2.11 jokers excluded from sequences | E22 | D4 |
| R2.16 lowest/highest sequence examples | E43, E44 | D5 |
| R4.4 10-card bomb named explicitly | E36, E37 | D2 |
| R5.2 lowest/highest straight flush | E43 | D5 |

## WI-02 test coverage

| WI-02 test or regression | Expected behaviour defined by the draft? | Where |
|---|---|---|
| `RuleProfileTest`: unknown option rejects | Yes, by default | R8.1, D10 |
| `RuleProfileTest`: hash stable | **Not defined.** The hash's canonical input is an implementation choice, not a rule. | WI-02 design |
| `CombinationTest`: each type | Yes | R2.1–R2.10, R4.1, R5.1, R6.1 |
| `CombinationTest`: A-low straight | Yes | R3.1, R3.2 |
| `CombinationTest`: jokers excluded from sequences | Yes, by default (template only) | R2.11, D4 |
| `BombOrderTest`: 4–10 card bombs | Yes; 9 and 10 by default | R4.1–R4.6, D2 |
| `BombOrderTest`: same-size level bombs | Yes | R4.8 |
| `BombOrderTest`: straight flush, four jokers | Yes | R5.3, R6.2, R4.9 |
| `WildcardDeclarationTest`: two valid interpretations both offered | Yes; source example 7788 + two wildcards | R7.6 (E56), R7.8 |
| `WildcardDeclarationTest`: forged declaration rejects | Yes, by default | R7.8, D6 |
| `WildcardDeclarationTest`: jokers cannot be substituted | Yes | R7.3, R6.3 |
| `LegalActionOracleTest`: subset oracle equals generator | Yes, given defaults D1–D9: every ambiguity the oracle could hit (wrap-around, 777+77, same-suit runs, lone wildcards, suit in declarations) now has a stated default | Sections 2–7, D1–D9 |
| `LargeBombCountOracleTest`: branch A sizes up to 10 from natural count + usable wildcards | Yes, by default; level-rank bombs capped at 8 | R4.5, R4.6, D2 |
| `LargeBombCountOracleTest`: branch B special bombs | Yes: four jokers and straight flushes are the only special bombs | R4.9, R5, R6 |
| `FourJokerBombTest` | Yes | R6.1–R6.3 |
| `SevenBomb27CardTest` (8+1, 7+2, 8+2 sevens at level 2) | Yes, **by default only** | R4.5, D2 |
| `GeneratorBudgetTest` | **Not defined.** A performance budget, not a rule. | WI-02 / WI-08 |
| Audit A05: concrete ranks and suits retained | **Not defined.** It is a data-model property; R7.8 and R7.10 define what a declaration states, not how physical cards are stored. | WI-02 design |
| Audit A06: level 2, heart 2 + seven vs a pair of sixes, pair of sevens legal | Yes | R7.2, R7.4 (E50), R2.13 |
| Audit A07: four sevens vs a single ace, bomb legal | Yes | R4.7 |
| Pass as leader, out-of-turn, partner lead | Out of scope for base rules (WI-03 reducer) | — |

## Final counts

| Item | Count |
|---|---|
| Evidence items checked | 63 |
| Evidence PASS / FAIL (final) | 63 / 0 |
| Rule rows checked | 60 |
| Rule rows PASS / FAIL (final, mechanical and manual) | 60 / 0 |
| Genuine FAILs found in pass 1 | 6 (3 mechanical, 3 manual) |
| Fixed in place | 6 |
| Converted to Unresolved instead of fixed | 0 new decisions; the 3 template-only rows were routed to the existing D2 and D5 |
| Coverage gaps found and closed | 2 (R7.10, R7.11) |
| DERIVED rows without a decision (reviewer should confirm the reasoning) | 5: R1.2, R2.13 (card count only), R3.6, R4.3, R7.5 |
| Open decisions in section 9 | 11 (D1–D11) |

## Limits of this audit

- The audit checks the evidence against **local text extractions**. It cannot detect an error made by the website that reposted HA17 or CG22 (decision D11).
- The JS17 `.doc` came from a web archive snapshot of the Jiangsu Sports Bureau site, not from the bureau today.
- The manual support check was made by the same author who drafted the spec. No second reviewer, human or model, has checked it.
