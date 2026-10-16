# G1a clause check against the two cited event rules

Status: **draft, not a signed gate.** This checks which G1a clauses have a text source in the two PDFs in this folder. It is not `rules/base-v1.md` and does not sign G1a.

> **Superseded for G1a by `rules/base-v1.md` (draft, 2026-10-14)**, which adds the 2017 Jiangsu official text, reposts of the 2017 and 2022 national texts, and a 2025 rule set, and maps C1–C17 to quoted evidence (its Appendix C).
>
> **Correction (2026-10-14):** this file calls CUP a single-deck event (rows CUP, C11, C17 and gap 4). That is wrong. CUP 八、1 defines 四大天王 as "大王小王各两张" (two big and two small jokers), which requires two decks (`text/CUP.txt` line 120). CUP is a two-deck event like the others.

Sources (text extracted with `pdftotext -layout`):

| Key | File | SHA-256 (first 16 hex) | Scope |
|---|---|---|---|
| CUP | `cup-event-rules-2024.pdf` | `ff25dc13852eb0b7` | 机关职工掼牌（掼蛋）交流活动比赛规则, 4 pages, one civil-servant exchange event. Single deck rules with level-card wildcard. |
| SEU | `seu-event-rules.pdf` | `9a7f20ca99230e0b` | 扑克牌（掼蛋）比赛规则（两副牌）, a two-deck competition with its own scoring table. |

Neither document is a complete base rulebook. Both are event rules.

## Clause table

Legend: **Sourced** = the text states it. **Partial** = one source states it, or the sources only imply it. **Unsourced** = neither source states it. **Ambiguous** = the text can be read two ways.

| # | G1a clause | CUP | SEU | Status |
|---|---|---|---|---|
| C1 | Combination types: single, pair, triple, triple-with-pair, straight, straight flush, steel plate (2 triples in sequence), triple pairs, bomb, four jokers | 八、1: single, pair, 三连对, 三同张, 二连三, 三带二, 顺子, 同花顺, 炸弹, 四大天王 | 一、5–9, 4: single, pair, 三张, 三带两, 顺子, 三连对, 钢板, bombs, 四王 | Sourced (both agree on the list) |
| C2 | Straight: five consecutive singles, A-low and A-high allowed, no wrap-around | 八、1 and 八、3(1): A may be 1 in 顺子, 三连对, 二连三, 同花顺 | 一、3(1): A-2-3-4-5 and 10-J-Q-K-A allowed | Partial. Both allow A-low. Neither states the wrap-around rule (K-A-2-3-4) as forbidden. Needs an explicit ruling. |
| C3 | Jokers excluded from sequences | Not stated | 一、3(1): 不包括双王 | Partial (SEU only). |
| C4 | Triple-pair (三连对) length: 3 consecutive pairs, A-low AA2233 and high AAKKQQ | 八、1 lists 三连对, no length given | 一、3(2): 3 pairs max; 两连对不可出 (2 pairs forbidden) | Partial (SEU only). Confirms 2-pair sequences are illegal. |
| C5 | Steel plate (二连三): 2 consecutive triples, no longer | 八、1 lists 二连三, no length given | 一、4: 2 consecutive triples max (333444, 444555) | Partial (SEU only). |
| C6 | Bomb: four or more same-rank cards | 八、3(3): 四张或四张以上牌点相同的单张 | 一、1: 4 to 8 same-rank cards are bombs | Sourced. CUP has no upper bound. SEU's upper bound is 8 physical cards, plus the wildcard case below. |
| C7 | Bomb ordering: more cards beat fewer; same size compares rank | 八、3(3) | 一、9 | Sourced, both agree. |
| C8 | Straight flush (5 consecutive, same suit) beats bombs of 5 or fewer cards; loses to bombs of 6 or more | 八、3(4): 同花顺可压不超过5张炸弹; 超过5张炸弹可压同花顺 | 一、9: 六张及以上炸弹 > 同花顺 > 五张炸弹 | Sourced. Both agree. |
| C9 | Four jokers (四王 / 四大天王) beat every other combination | 八、3(5): 可压所有牌型 | 一、9: 四王是最大的牌 | Sourced. Both agree. |
| C10 | Joker-only combinations (two jokers as a pair, etc.) | 八、1 and 八、3 do not address | 一、9 and 一、10 do not address | Unsourced beyond C9. |
| C11 | Level card: rank equals the level, sits between the small joker and the other cards, and can be used inside sequences by natural order | 八、2: 级牌小于小王而大于其它花色牌, 可按自然顺序插在顺子、同花顺、三连对 | 一、15 gives order for a level of 10; no explicit sequence rule | Partial (CUP only, for the single-deck context). |
| C12 | Wildcard (red-heart level card) substitutes for any non-joker card | 八、2: 可以和任意牌点的花色牌搭配 | 一、10 and 注: 除王外可配任何牌 | Sourced. Both agree. |
| C13 | Wildcard cannot replace a joker | 八、2 excludes 大王/小王 by its own wording ("任意牌点的花色牌") | 注: 除王外 | Sourced. Both agree. |
| C14 | Wildcard declaration (player chooses the interpretation and the interpretation is part of the action) | Not stated | Not stated | Unsourced. Only a rule of the product (WI-02), not of either event. |
| C15 | Bomb sizes 9 and 10 when wildcards are added to a bomb of physical cards | Not stated | 注: 逢人配 with 八张炸弹 "可变为10张炸弹" | **Ambiguous.** The SEU sentence says 8 physical cards plus the wildcard make a 10-card bomb. It does not state 8+1 (9 cards), 7+2 (9 cards), or 8+2 (10 cards). The plan's three seven-bomb test cases depend on this. |
| C16 | Max bomb size | Not stated | Eight physical cards, ten with the wildcard case | Ambiguous (see C15). The plan's "4–10" bound is the SEU wildcard maximum applied to every bomb, not a stated rule. |
| C17 | Joker count limit | Not stated | Not stated | Unsourced. Two decks in SEU, one in CUP; neither states a count limit beyond the four-joker bomb. |

## What this means for G1a

- **Sourced and agreed by both:** C1 (list), C6 (bomb floor), C7, C8, C9, C12, C13.
- **Sourced by one source only:** C3, C4, C5, C11. These need the other event's confirmation or a stated profile choice.
- **Ambiguous and blocking the plan's tests:** C15 and C16. The seven-bomb tests in WI-02 (`rules/SevenBomb27CardTest.kt`) and the 10-card bomb rule cannot be signed from these two documents.
- **Unsourced:** C10, C14, C17. C14 is a product design choice, not a rule. It needs an owner decision, not a source.

## Conflicts and gaps to resolve before a signature

1. **Bomb size with wildcards (C15, C16).** Pick one sentence the profile will state, and cite SEU 注 as its source with the chosen reading. Example: "A wildcard may complete a bomb of physical cards up to 10 cards, per SEU 注." A rules reviewer must confirm the reading.
2. **Wrap-around sequences (C2).** Neither source forbids K-A-2-3-4. The profile must state it.
3. **Joker-only combinations (C10).** Not covered. Decide and cite.
4. **Source scope.** CUP is a single-deck civil-servant event. SEU is a two-deck competition. A profile that uses both must say which clause came from which, and must not present the mix as an official national rule. The audit (`dev-docs/reference/guandan-code-audit.md`, "Rules evidence and review boundary") already says this.

## Additional sources found online (added after the first pass)

The owner does not know Guandan, so these were found by web search. Files live in this folder.

| Key | File | Scope | Bomb and wildcard text |
|---|---|---|---|
| YZU | `yangzhou-univ-rules.doc` | Yangzhou University staff tournament rules (2 decks) | 4–8 same-value bombs; 主牌红桃 2 张 = 逢人配; "如和八张一起，可变为10张的炸蛋"; 同花顺 only 5 cards; 四王 top |
| NJMU | `nanjing-medical-univ-rules.doc` | Nanjing Medical University staff rules | Same bomb list and same "八张一起…10张" sentence as YZU |
| SEU | `seu-event-rules.pdf` (earlier) | Southeast University competition rules | Same sentence again |

**Finding:** YZU, NJMU and SEU use the same bomb sentence. Three documents repeating one sentence is one source copied three times, not three confirmations.

Online sources that are not local files (cited, not downloaded):

- Official 《淮安掼蛋竞赛规则》 (National Sports Administration Chess and Card Management Centre, 2017 national-standard edition). News reports confirm it exists and fix some terms: 木板 = 三连对; 杂花顺 = 顺子 = exactly five consecutive singles, any suit; 8 competition formats. ([People's Daily 2017](http://qipai.people.com.cn/GB/n1/2017/0415/c22160-29213186.html), [Jiangsu news 2017](https://jsnews.jschina.com.cn/ha/a/201704/t20170417_370209.shtml)). The rule text itself was not found online. The People's Daily page failed a TLS hostname check, so it was not opened.
- 《竞技掼蛋竞赛规则（试行）》, a 2022 trial edition from the same body. Only secondary reports were found. No text.
- [Chinese Wikipedia 掼蛋](https://zh.wikipedia.org/zh-hans/%E6%8E%BC%E8%9B%8B): bomb = 4 or more same-rank cards, no upper limit stated; 逢人配 = 红心级牌 for any non-joker rank; 同花顺 beats up to 5-card bombs; 天王炸 beats everything. It cites 《江苏省掼蛋竞赛简易规则》 (2017) as its base.
- [English Wikipedia Guandan](https://en.wikipedia.org/wiki/Guandan): bomb = 4 or 5, then 6 or more; "The length of set could go as high as 10 with wild cards." Level cards in hearts are wild for any non-joker; a level card keeps its own value except inside a straight.

**Effect on C15/C16:** Every source found that gives a number gives 10 as the upper bound with wildcards, and that number comes from the same template sentence. No source found states 9-card bombs. The official 2017 text is still the one that would settle it.

## Next step

G1a needs a rules reviewer and one of: a chosen reading for C15/C16 and C2, or a third source. The two PDFs cover about half of the clause list and leave C15 open. This file is a draft; it does not sign anything.
