# Match profile spec v1 (G1b)

**Status: SIGNED 2026-10-16 by the project owner (gate G1b); no Guandan rules reviewer signed.**
**Date:** 2026-10-14 (draft); signed 2026-10-16
**Signature record:** `dev-docs/decisions/G1b-match-profile.md`. Basis: the abridged 2022 trial text only (`rules/sources/competitive-rules-2022-trial-gansu-excerpt.md`); the printed 2017 and 2022 rule books were not checked; every decision D25–D40 is adopted at its stated default; the owner is not a Guandan expert. The released match profile is `M1-match-v1` 1.0.0 (`rules/profiles/M1-match-v1.json`), with base profile `P1-base-v1`.

**History (draft text, unchanged below):**
**Sign-off required:** the owner and a Guandan rules reviewer must both sign this spec (gate G1b in `dev-docs/implementation-plan.md`) before any release build uses tribute or match rules. Development uses it now as the provisional match profile `M0-provisional` (`rules/profiles/M0-provisional.json`), together with the base profile `P0-provisional` (`rules/base-v1.md`).

## 0. How to read this spec

`rules/base-v1.md` says which plays are legal inside one deal. This spec covers everything around the deals of one match: levels, settlement, passing A, tribute, return, resistance, the opening lead and card-count reporting.

Every rule row ends with a tag in square brackets:

| Tag | Meaning |
|---|---|
| `[JS17:69]` | Stated by source file `rules/sources/text/JS17.txt`, starting on line 69. Appendix A quotes it verbatim with an English gloss. Several references mean several sources say the same thing. |
| `[DERIVED from JS17:79]` | No source says it in one sentence; it follows from the quoted lines. A reviewer should confirm the reasoning. |
| `[... — D28]` | The behaviour depends on decision D28 in section 10. Until D28 is signed it is a **default**. |
| `[UNSOURCED — D25]` | No source found. The text is the default proposed for the decision. |
| `[PRODUCT — D31]` | Not a rule of the card game; how this app represents a rule. |

Base-v1 uses decisions D1–D24. This spec continues from D25.

### Sources and precedence

The same sources and the same precedence as base-v1 (its Appendix B has the register and hashes): **JS17** first, then **HA17**, then **CG22a**; **WG25** corroborates. **CUP**, **SEU**, **YZU** and **NJMU** are event rules for staff tournaments; SEU, YZU and NJMU share one template text, so they count as one source. Where an event rule differs from JS17, the row says so and the default follows JS17 unless a decision says otherwise.

Unlike base-v1, the sources here **do** disagree on several points: the return limit, ties in a double tribute, who leads after a double tribute, split jokers for resistance, failure counting when passing A, the reporting threshold, and whether a card count must be given when asked. Each disagreement is a decision in section 10.

Design copy is not a source. `design/original/README.md` lists "过 A（失败 3 次）" as decided; no official source has a failure limit (row M3.7), so this spec treats it as decision D35 with the official texts' behaviour as the default.

## 1. Teams, levels and deals

| ID | Rule | Tag |
|---|---|---|
| M1.1 | Two teams: SOUTH with NORTH, EAST with WEST (partners sit opposite). Each team has its own level. Both teams start a match at level 2, and a match runs until one team passes A. | [JS17:50, CG22a:197] |
| M1.2 | The first deal is played at level 2. Every later deal is played at the level that the previous deal's 上游 team reached. | [JS17:36] |
| M1.3 | In a deal, the seats that play out are 上游, 二游, 三游 in that order; the last is 下游. When one team takes 上游 and 二游, the other team is 双下 (double-down). | [JS17:28, WG25:102] |
| M1.4 | A deal ends when three seats have played out, or as soon as one team takes 上游 and 二游 (an early double-up). Same as base-v1 D21. | [CG22a:266, HA17:108] |
| M1.5 | After an early double-up the two seats still holding cards are not ranked against each other. Settlement records both as places 3–4, basis "inferred double-down", never as played places. | [JS17:28; PRODUCT — D38] |
| M1.6 | After three seats play out, the fourth is 下游 (place 4). Settlement records it with basis "last by elimination", separate from the three played places. | [HA17:108; PRODUCT — D38] |
| M1.7 | Every settlement carries the completion policy as a label (`endEarly`). A full-ranking policy (playing on after a double-up) is not implemented. | [CG22a:266; PRODUCT — D38] |

## 2. Settlement and level gains

| ID | Rule | Tag |
|---|---|---|
| M2.1 | Only the team of the 上游 gains levels. | [JS17:98, HA17:121, CG22a:197, WG25:207] |
| M2.2 | The gain depends on where the 上游's partner finished: 二游 +3, 三游 +2, 下游 +1. An early double-up counts as partner 二游 (+3). The event rules say the same in other words. | [JS17:98, HA17:123, CG22a:197, WG25:207, SEU:46, NJMU:18] |
| M2.3 | The other team's level does not change. | [DERIVED from JS17:98] |
| M2.4 | A gain never takes a team past A: a team below A that gains enough stops at A (for example Q +3 is A). "A must be played." | [DERIVED from JS17:98, HA17:294, CG22a:198] |
| M2.5 | The next deal is played at the level of the 上游's team after this settlement. | [JS17:36] |

Over the 24 possible finishing orders of four seats, 8 put the partner second (early double-up, +3), 8 third (+2) and 8 last (+1). `SettlementTest` checks all 24.

## 3. Passing A

| ID | Rule | Tag |
|---|---|---|
| M3.1 | A team at A can pass A only in a deal played at level A. A team at A that wins a deal played at the other team's level stays at A and does not pass (audit A04). | [DERIVED from JS17:98, HA17:294, CG22a:198 — D33] |
| M3.2 | In a deal at A, a team at A passes A when one of its players is 上游 and the partner is not 下游 (partner 二游 or 三游, gain 2 or more). SEU:46 says "升1级以上", which could also be read as allowing partner 下游; the default follows the official texts. | [JS17:98, CG22a:198, WG25:207, HA17:294, CUP:14, NJMU:18 — D34] |
| M3.3 | Passing A ends the match; that team wins. | [JS17:100, CUP:95] |
| M3.4 | A team at A that takes 上游 in a deal at A with its partner 下游 does not pass and stays at A. | [HA17:294, JS17:98] |
| M3.5 | A **failed attempt** is a deal played at level A, while the team is at A, that the team does not pass: either it took 上游 with partner 下游, or the other team took 上游. CUP and NJMU count attempts but do not define one; the "other team took 上游" case is a default. | [CUP:14, NJMU:18; UNSOURCED detail — D36] |
| M3.6 | A deal played at another level while a team is at A (an interruption) is not an attempt: it neither adds a failure nor resets the count. | [UNSOURCED — D37] |
| M3.7 | Failure limit: none by default. A team keeps playing A until it passes. JS17, CG22a and WG25 state no limit, and HA17 says to keep playing A. CUP sends a team back to 2 after three failures; NJMU after two. | [HA17:294, JS17:98, CG22a:198 — D35] |
| M3.8 | Where a limit applies and a team reaches it: that team's level returns to 2 and its failure count to 0. The other team's level does not change. The next deal's level follows M2.5, so if the failing team was 上游 the next deal is at 2. | [CUP:14, NJMU:18 — D35] |
| M3.9 | A team's failure count is 0 whenever it is not at A. | [DERIVED from M3.5, M3.8] |

## 4. Tribute

| ID | Rule | Tag |
|---|---|---|
| M4.1 | There is no tribute before the first deal. From the second deal on, tribute and return take place after the cards are dealt and before the first lead. | [JS17:69, CG22a:289, JS17:30] |
| M4.2 | **Single tribute** (no double-down in the previous deal): the 下游 gives one card to the 上游. | [JS17:69, HA17:264, CG22a:289, WG25:157, CUP:66] |
| M4.3 | The tribute card is the giver's highest card in the deal's rank order (base-v1 R1.5: big joker, small joker, level card, A … 2), with the heart level cards (wildcards) excluded. A non-heart level card therefore ranks above an A. | [JS17:69, HA17:264, CG22a:289, WG25:157; order JS17:79] |
| M4.4 | When several cards share that highest rank, the giver chooses which one to give. Suit never decides rank (base-v1 R1.7). | [UNSOURCED — D25] |
| M4.5 | **Double tribute**: after a double-down, both players of the double-down team give one card. | [JS17:70, HA17:268, CG22a:291, WG25:161, CUP:74] |
| M4.6 | The 上游 receives the higher of the two tribute cards and the 上游's partner (the 二游) the lower. | [JS17:70, HA17:268, CG22a:291, WG25:161, CUP:76] |
| M4.7 | When the two tribute cards rank equal, tribute goes clockwise: each giver gives to the opponent on its left, its 上家. So the 上游's 下家 gives to the 上游. CUP calls this "贡左还右". SEU lets the winners choose suits and the losers choose returns; YZU and NJMU let the losers choose the returned cards. | [JS17:70, CG22a:291, WG25:162, HA17:268, CUP:78, JS17:27; SEU:35, YZU:21, NJMU:12 differ — D26] |
| M4.8 | Tribute cards are put face up on the table: which card each giver gives is public. | [CG22a:287, JS17:70] |
| M4.9 | In a double tribute no card changes hands until both tributes are given, because the routing (M4.6, M4.7) depends on both cards. A single tribute moves at once. | [DERIVED from JS17:70] |

## 5. Return tribute

| ID | Rule | Tag |
|---|---|---|
| M5.1 | Every player who received a tribute card returns one card to the player it received from. | [JS17:69, JS17:70, HA17:264, CG22a:291] |
| M5.2 | The returned card must rank 10 or lower. **Sources disagree:** HA17 limits only returns to one's own partner and allows any card to an opponent; every return in this spec goes to an opponent, so HA17 read literally has no limit. YZU allows any card. JS17, CG22a, WG25, CUP, SEU and NJMU state the limit of 10. | [JS17:69, CG22a:289, WG25:157, CUP:68, SEU:35, NJMU:12; HA17:265, HA17:358, YZU:21 differ — D28] |
| M5.3 | A level card does not count as "10 or lower", even when its face is 10 or lower, because the rank order puts level cards above A. The heart level cards are level cards. | [DERIVED from JS17:79 — D29] |
| M5.4 | All-high exception: if no card in the returner's hand ranks 10 or lower, it returns its lowest card in the deal's rank order. When several cards share the lowest rank, the returner chooses. JS17 and HA17 do not address the case. | [CG22a:289, WG25:159, CUP:68; choice UNSOURCED — D25] |
| M5.5 | Returns are put face down. When every return is made, they are turned up together and the cards move to their new owners; from then on they are public. | [JS17:70, CG22a:291, WG25:162, CUP:78, CG22a:287] |
| M5.6 | Until the reveal, only the returner knows its returned card. The table knows only that a card was put down. | [DERIVED from JS17:70] |

## 6. Resistance (抗贡)

| ID | Rule | Tag |
|---|---|---|
| M6.1 | Single tribute: if the 下游 holds both big jokers, no tribute is given. | [JS17:71, HA17:270, CG22a:293, WG25:165, CUP:72, SEU:40, YZU:21] |
| M6.2 | Double tribute: if the double-down team holds both big jokers between its two hands (one each, or both in one hand), no tribute is given. HA17 and the template text say so explicitly. JS17, CG22a, WG25 and CUP say "两个大王" of the giving side without saying whether one hand must hold both. | [HA17:271, SEU:36, YZU:21, NJMU:12; JS17:71, CG22a:293, WG25:165, CUP:82 silent — D30] |
| M6.3 | Resistance is a right, not a duty: "不须进贡" (need not give), "可抗贡" (may resist). A giver claims it before any tribute card is given; once a tribute card is given, the claim is waived for that deal. A variant resists automatically as soon as the tribute starts. | [JS17:71, SEU:36; claim procedure PRODUCT — D31] |
| M6.4 | A claim shows the two big jokers to the table: the holders and the joker cards are public. | [PRODUCT — D31] |
| M6.5 | After resistance no card changes hands and the 上游 leads. | [JS17:71, HA17:270, CG22a:293, WG25:165] |

## 7. Opening lead

| ID | Rule | Tag |
|---|---|---|
| M7.1 | First deal: a card is drawn at random (a joker or the heart 2 is drawn again), and the player who holds it leads. The app draws the card from the seeded deal randomness; the card and its holder are public. CG22a's duplicate-deal format instead has East lead the first deal. | [JS17:56, CG22a:243, WG25:143, HA17:115, CUP:23; CG22a:248 differs — D32] |
| M7.2 | After a single tribute, the giver (the previous 下游) leads. | [JS17:69, HA17:115, CG22a:289, WG25:157, CUP:33, SEU:42] |
| M7.3 | After a double tribute, the giver whose card went to the 上游 leads. CUP and SEU say "the bigger tribute leads", which is the same player unless the cards tie. YZU says the last player leads; NJMU says the 下家 of the last 上游. HA17 gives both "the bigger giver" and "the 上游's 下家" in one paragraph. | [JS17:70, CG22a:291, WG25:162, CUP:80; CUP:33, SEU:42, HA17:268, YZU:21, NJMU:12 — D27] |
| M7.4 | So when the two tribute cards tie (M4.7), the 上游's 下家 leads, as CUP, SEU and the second clause of HA17 say. | [DERIVED from JS17:70; CUP:33, SEU:42, HA17:268] |
| M7.5 | After resistance, the 上游 leads. | [JS17:71, HA17:116, CG22a:293, WG25:165, CUP:33] |
| M7.6 | After the first deal the opening lead is never random and is never chosen by the caller: the reducer derives it from the previous result and the tribute (audit A02). | [DERIVED from M7.2–M7.5] |

## 8. Card-count reporting (报牌)

| ID | Rule | Tag |
|---|---|---|
| M8.1 | When a play leaves a player with 10 cards or fewer (and at least one), that player reports the exact number of cards left, at once. SEU sets the threshold at 6. WG25 lets organisers switch reporting off. | [JS17:95, HA17:281, CG22a:301, WG25:203, CUP:86; SEU:27 differs — D39] |
| M8.2 | Each player reports at most once per deal. | [JS17:95, HA17:281] |
| M8.3 | The report is public, and the reported number stays on the table until the deal ends. | [CG22a:301, HA17:281] |
| M8.4 | Nobody may ask a player's card count, and nobody answers. **Sources disagree:** CUP says an opponent may ask while the player holds 10 cards or fewer and must be answered; SEU says "剩余10张牌有问必报". | [JS17:95, HA17:284, CG22a:304, WG25:203; CUP:90, SEU:27 differ — D40] |
| M8.5 | Apart from reports and answers, no hand size is public (base-v1 D24). Projections never contain a hand size. | [PRODUCT — D39] |
| M8.6 | A player whose play empties its hand does not report; its finish is public anyway. | [DERIVED from JS17:95] |

## 9. Profile options

Rules are data (base-v1 R8.1). The match options live in their own registry, `MatchOptions` (`core/src/main/kotlin/cn/wpgd/app/core/rules/MatchOptions.kt`), and are set by a match profile such as `M1-match-v1`. A base profile never contains them, so adding a match option leaves base profile hashes unchanged. A choice option lists only the values the engine implements; any other value is rejected when the profile loads.

| Option | Type | Default | Rows | Decision |
|---|---|---|---|---|
| `level.gainPartnerSecond` | integer 1–12 | 3 | M2.2 | sourced |
| `level.gainPartnerThird` | integer 1–12 | 2 | M2.2 | sourced |
| `level.gainPartnerFourth` | integer 1–12 | 1 | M2.2 | sourced |
| `passingA.requireDealAtA` | boolean | true | M3.1 | D33 |
| `passingA.minGain` | integer 1–3 | 2 | M3.2 | D34 |
| `passingA.maxFailures` | integer 1–12, or null for no limit | null | M3.7 | D35 |
| `passingA.resetLevel` | integer 2–13 | 2 | M3.8 | D35 |
| `passingA.opponentWinCounts` | boolean | true | M3.5 | D36 |
| `passingA.interruptionResets` | boolean | false | M3.6 | D37 |
| `tribute.equalRankChoice` | one of `player` | `player` | M4.4, M5.4 | D25 |
| `tribute.tieRouting` | one of `clockwise` | `clockwise` | M4.7 | D26 |
| `returnTribute.maxRank` | integer 2–14, or null for any card | 10 | M5.2 | D28 |
| `returnTribute.levelCardsByFace` | boolean | false | M5.3 | D29 |
| `resistance.splitBetweenPartners` | boolean | true | M6.2 | D30 |
| `resistance.mode` | one of `claimed`, `automatic` | `claimed` | M6.3, M6.4 | D31 |
| `lead.firstDeal` | one of `cutCard`, `east` | `cutCard` | M7.1 | D32 |
| `lead.afterDoubleTribute` | one of `giverToFirst` | `giverToFirst` | M7.3 | D27 |
| `deal.doubleUpCompletion` | one of `endEarly` | `endEarly` | M1.4–M1.7 | D38 |
| `reporting.threshold` | integer 1–26, or null for no reporting | 10 | M8.1 | D39 |
| `reporting.answerWhenAsked` | boolean | false | M8.4 | D40 |
| `reporting.askThreshold` | integer 1–26 | 10 | M8.4 | D40 |

## 10. Unresolved

Each entry needs a signed decision. Until then the **default** is what the draft, `M0-provisional` and the tests assume. **2026-10-16:** the project owner signed every default below (G1b); `M1-match-v1` carries them.

| ID | Question | Why sources do not settle it | Rules affected | Suggested default | Decides |
|---|---|---|---|---|---|
| D25 | When a rule names a rank (the highest tribute card, the lowest return) and several cards share it, who picks the card? | No source addresses it. Suit never changes rank (base-v1 R1.7), so the choice does not change the rules outcome, only which physical card moves. | M4.4, M5.4 | **Default:** the giving or returning player picks. | Rules reviewer |
| D26 | Equal tribute cards in a double tribute: who gives to whom? | JS17, CG22a, WG25, HA17 and CUP say clockwise. SEU lets the winners choose suits and the losers choose returns; YZU and NJMU let the losers choose returned cards. | M4.7, M7.4 | **Default:** clockwise, each giver to its 上家 (JS17). Only this value is implemented. | Rules reviewer |
| D27 | Who leads after a double tribute? | JS17, CG22a, WG25 and CUP: the giver to the 上游. HA17's paragraph names both the bigger giver and the 上游's 下家; YZU names the last player; NJMU the 下家 of the last 上游. | M7.3, M7.4 | **Default:** the giver whose card went to the 上游 (JS17). Only this value is implemented. | Rules reviewer |
| D28 | Is there a return limit, and what is it? | Six sources say 10 or lower. HA17 applies the limit only to returns to one's own partner (any card to an opponent), and YZU allows any card. | M5.2 | **Default:** 10 or lower for every return (JS17). `null` (any card) is available as a variant. | Rules reviewer |
| D29 | Does a level card with face 10 or lower meet the return limit? | No source says. JS17:79 defines the rank order (牌点) with level cards above A, which reads as "no". | M5.3 | **Default:** no; level cards, wildcards included, rank above A for the limit. The face-value reading is a variant. | Rules reviewer |
| D30 | Does a double-down team resist when its two players hold one big joker each? | HA17 and the template text say yes. JS17, CG22a, WG25 and CUP say "two big jokers" of the giving side without saying whether one hand must hold both. | M6.2 | **Default:** yes. The one-hand reading is a variant. | Rules reviewer |
| D31 | Is resistance automatic or claimed? Is it shown to the table? | Sources say the side "need not" (JS17) or "may" (SEU) give tribute, so it is a right. No source says how it is shown. | M6.3, M6.4 | **Default:** claimed by a giver before any tribute is given; the jokers are shown. Automatic is a variant. | Owner |
| D32 | Who leads the first deal? | All sources use the drawn card, except CG22a's duplicate-deal format (East leads). | M7.1 | **Default:** drawn card from the seeded randomness, redrawn on a joker or heart 2 (JS17). East is a variant. | Owner |
| D33 | Must a team at A pass in a deal played at A, or can it pass by winning the other team's deal? | Sources say "A must be played" and "the side playing A", which implies a deal at A, but none states the off-level case (audit A04). | M3.1 | **Default:** only a deal at A. The off-level variant exists to show the difference, not as a recommendation. | Rules reviewer |
| D34 | Can a team pass A with its partner 下游 (gain 1)? | Every official source says no. SEU:46 says "升1级以上", which could be read either way. | M3.2 | **Default:** no; gain 2 or more. | Rules reviewer |
| D35 | Is there a limit on failed A attempts, and what follows? | JS17, CG22a and WG25 state none and HA17 says to keep playing A. CUP sends a team back to 2 after three failures, NJMU after two. The design copy says three; it is not a source. | M3.7, M3.8 | **Default:** no limit (official sources). A limit of 3 (CUP) or 2 (NJMU) with reset to 2 is available. | Owner + rules reviewer |
| D36 | Does a deal at A that the other team wins count as a failed attempt? | CUP ("三次打A不过") and NJMU count failures but do not define an attempt (audit A03). | M3.5 | **Default:** yes; every deal at A that the team does not pass counts. | Rules reviewer |
| D37 | Do interruptions (deals at the other team's level) reset the count? | No source addresses it (audit A03). | M3.6 | **Default:** no; the count is cumulative while the team is at A. | Rules reviewer |
| D38 | After an early double-up, are the other two seats ranked, and how is that shown? | CG22a and HA17 end the deal at once, so no ranking exists. The upstream app played on (audit A36). | M1.5–M1.7 | **Default:** end early; the two places are recorded as inferred 3–4. Only this policy is implemented. | Owner |
| D39 | Reporting threshold. | Five sources say 10; SEU says 6; WG25 lets organisers switch reporting off. | M8.1, M8.5 | **Default:** 10. `null` (off) and other thresholds are available. | Rules reviewer |
| D40 | May a player be asked for its card count? | JS17, HA17, CG22a and WG25 forbid asking; CUP and SEU require an answer. | M8.4 | **Default:** no asking. The CUP variant (opponents ask, answer while 10 or fewer) is available. | Rules reviewer |

## Appendix A. Evidence

Each quote is verbatim from `rules/sources/text/<Key>.txt`, starting on the line given and continuing for at most two more lines (PDF and HTML lines wrap). Whitespace and full-width forms are not significant (NFKC). `MatchSpecTest` checks every quote against its file.

| Ref | Quote (verbatim) | English gloss |
|---|---|---|
| JS17:27 | 位于左方的对手为上家，位于右方的对手为下家 | The opponent on your left is your 上家; the one on your right is your 下家. |
| JS17:28 | 按牌手出完各自全手牌的先后，称为上游、二游、三游、下游。如一方两位牌手获得上游、二游，则对方称为双下。 | By the order of playing out: 上游, 二游, 三游, 下游. If one side takes 上游 and 二游, the other side is 双下. |
| JS17:30 | 从第二副牌开始，在出牌前由下游向上游或双下方向对方进贡牌张，并对应还牌，以确定首圈领出者。 | From the second deal, before play, 下游 (or the 双下 side) gives tribute and receives returns, which decides the first leader. |
| JS17:36 | 牌点与上副牌上游所升至级数相同的所有花色牌为级牌，首副牌的级牌为2。 | Level cards are those of the rank the previous deal's 上游 reached; the first deal's level is 2. |
| JS17:50 | 比赛中，双方从打2开始按序升级至一方打过A，为一局。 | A game runs from both sides playing 2 until one side passes A. |
| JS17:56 | 南家切牌并翻出一张牌（如翻出的牌为王牌或红心2，则重切重翻）。从南家开始，依翻出牌张的牌点，按逆时针方向点数出首抓者，抓到翻出牌张者首圈领出牌。 | South cuts and turns a card (a joker or heart 2 is re-cut); counting from South gives the first drawer; whoever draws that card leads the first trick. |
| JS17:69 | 从第二副牌开始，在出牌前，由上副牌的下游向上游进贡全手牌中牌点最大的一张牌（红心级牌除外），上游选择全手牌中任意一张牌点不得超过10的牌还给下游。首圈由下游领出牌。 | From the second deal, before play, the previous 下游 gives 上游 its highest card (heart level cards excepted); 上游 returns any card not above 10. 下游 leads. |
| JS17:70 | 如上副牌出现双下，则双下方两位牌手均向对方进贡。上游选择牌点较大的进贡者，同伴选择牌点较小的进贡者，并对应还牌。若牌点相同，则按顺时针方向进贡，对应还牌。还牌时，应将牌面向下，两位进贡者同时亮牌。还牌后，均由进贡给上游者首圈领出牌。 | After a double-down both give; 上游 takes the bigger, its partner the smaller, with returns to match. Equal: tribute goes clockwise. Returns face down, both givers reveal together. The giver to 上游 leads. |
| JS17:71 | 如应进贡者（下游或双下方）抓到两个大王，则不须进贡（抗贡）。首圈由上游领出牌。 | If the giver(s) (下游 or the 双下 side) drew both big jokers, no tribute is needed (resistance); 上游 leads. |
| JS17:79 | 牌点由大到小排列为：大王、小王、级牌、A、K、Q、J、10、9、8、7、6、5、4、3、2。 | Rank order high to low: big joker, small joker, level card, A, K … 2. |
| JS17:95 | 牌手在出完一手牌后，如手中剩余牌不超过10张，应立即将报牌卡放至本家出牌区域，并准确、清楚报出剩余张数。牌手报牌后，无论是否继续出牌，均不得再次报牌，其他牌手也不得再追问。 | After a play leaving 10 cards or fewer, the player at once puts out the report card and states the exact count. After reporting, no second report, and nobody may ask. |
| JS17:98 | 只有上游方可以升级，同伴二游升3级，同伴三游升2级，同伴下游升1级。A级必打，且必须同伴获得二游或三游，才能打过A级。 | Only 上游's side goes up: partner 二游 +3, 三游 +2, 下游 +1. Level A must be played, and A is passed only with the partner 二游 or 三游. |
| JS17:100 | 先打过A级，或在规定时间内、打完规定的副数后，升级数高的一方获胜。 | The side that first passes A wins (or the side with more levels at the time or deal limit). |
| HA17:108 | 如果是打成“双下”(即同一方的选手分别获得上游和二游)时，则一副牌自然结束。 | When one side takes 上游 and 二游, the deal ends. |
| HA17:115 | 一局牌第一副牌的第一圈，是由抓到抽出的牌张者先领出牌。以后每副牌由向上游贡牌的下游者领出牌。 | The first deal is led by whoever drew the drawn card; later deals by the 下游 who gave tribute to 上游. |
| HA17:116 | 如不需贡牌，则由上游者领出牌。 | If no tribute is needed, 上游 leads. |
| HA17:121 | 只有获得上游的一方可以升级。 | Only the side with 上游 goes up. |
| HA17:123 | 如果上游选手的搭档为二游(双下)，则上游方升3级；如其为三游，则升2级；如其为下游，则升１级。 | Partner 二游 (double-down): +3; 三游: +2; 下游: +1. |
| HA17:264 | 进贡的牌必须是自己手中最大的牌（“红心参谋”除外）。接受进贡者须将自己手中的一张牌还给进贡者，并由下游者出牌。 | The tribute must be the giver's biggest card (heart level card excepted). The receiver returns one card; 下游 leads. |
| HA17:265 | 还给己方搭档的牌必须是10以下(含10)的牌；还给对方的牌可以为任意牌。 | A return to one's own partner must be 10 or lower; a return to an opponent may be any card. |
| HA17:268 | 双贡时，上游者拿贡的大牌，并还牌给贡大牌者。二游者拿贡的小牌，并还牌给贡小牌者。由贡大牌者在第一圈领出牌。如双方进贡的牌一样大小，则按照顺时针方向进贡。还牌时应将牌面向下，按逆时针方向分别还牌。双方得牌后同时亮牌，并由上游者的下家(右手方者)领出牌。 | Double tribute: 上游 takes the big card, 二游 the small, each returns to its giver; the bigger giver leads. Equal: clockwise. Returns face down, counter-clockwise; revealed together, and the 上游's 下家 leads. |
| HA17:270 | 下游者抓到两个大王，则不用进贡，由上游者领出牌。 | If 下游 drew both big jokers, no tribute; 上游 leads. |
| HA17:271 | 双贡时，如下游方二人各抓到一个大王或任一方抓到两个大王，则都不用进贡，由上游者领出牌。 | Double tribute: if the two losers hold one big joker each, or either holds both, no tribute; 上游 leads. |
| HA17:281 | 每名选手在打完一手牌后手中牌达到10张以下(包括10张牌) 时，必须主动报张数（每次全手牌只报一次），称为10张报牌。 | After a play leaving 10 or fewer cards, a player must report the count (once per hand). |
| HA17:284 | 一名选手10张主动报清楚牌张后，任何一方选手均不得再次询问其手中牌张数。该选手也不得以任何方式作出回答。 | After the report, nobody may ask the count again, and the player may not answer in any way. |
| HA17:294 | 一方过Ａ取胜结束（A必须打，不得直接升级过A）。但必须一人是上游、另一人不是下游才能算取胜, 否则需再继续打“Ａ”。 | A side wins by passing A (A must be played; no passing A by gain alone), but only with one player 上游 and the other not 下游; otherwise it keeps playing A. |
| HA17:358 | 上游者如将Ｊ(含Ｊ)以上的牌还给己方搭档，称为还错牌。 | If 上游 returns J or higher to its own partner, that is a wrong return. |
| CG22a:197 | 只有上游一方可以升级，根据搭档的游数，确定获得的级数。搭档二游，升三级；搭档三游，升二级；搭档下游，升一级。 | Only 上游's side goes up, by the partner's place: 二游 +3, 三游 +2, 下游 +1. |
| CG22a:198 | 过A：A级必打。过A是指打A级的一方一家为上游，其搭档不是下游。 | Passing A: A must be played. Passing A means the side playing A has 上游 and the partner is not 下游. |
| CG22a:243 | 首副牌，由东家洗牌，南家切牌并翻出一张牌，如翻出的牌为王牌或红桃2，则重切重翻。从南家开始，依翻出牌张的牌点，按逆时针方向点数出首抓者，抓到翻出牌张者首圈领出牌。 | First deal: South turns a card (joker or heart 2 re-cut); whoever draws it leads. |
| CG22a:248 | 比赛采用复制用牌时第一副牌由东家首出 | With duplicate deals, East leads the first deal. |
| CG22a:266 | 三名运动员的全手牌出完时，一副牌自然结束。如果同一方的两名运动员分别获得上游及二游，则一副牌自然结束。 | The deal ends when three players have played out, or when one side takes 上游 and 二游. |
| CG22a:287 | 贡牌和还牌时，牌张均须放至本家提醒区。 | Tribute and return cards are placed in the player's reminder area on the table. |
| CG22a:289 | 由上副牌的下游向上游进贡全手牌中最大的一张牌（红心级牌除外），上游选择全手牌中一张牌点不超过10的牌还给下游，如全手牌均大于10，则还最小的牌。首圈由下游领出牌。 | 下游 gives 上游 its biggest card (heart level card excepted); 上游 returns a card not above 10, or its smallest card if all are above 10. 下游 leads. |
| CG22a:291 | 如上副牌出现双下，则双下方两名运动员均向对方进贡，称为双贡。上游选择牌点较大的牌，搭档选择牌点较小的牌，并对应还牌。若牌点相同，则按顺时针方向进贡，对应还牌。还牌时，将牌面向下，两位进贡者同时亮牌。还牌后，均由进贡给上游者首圈领出牌。 | Same double-tribute rule as JS17:70. |
| CG22a:293 | 下游或双下方在进贡前抓到两个大王，则抗贡，不再贡牌，首圈由上游领出牌。 | If 下游 or the 双下 side drew both big jokers before tribute, it resists; 上游 leads. |
| CG22a:301 | 运动员在出完一手牌后，如剩余牌不超过10张，应立即将相应张数的报牌卡放至本家提醒区，待一副牌结束后才可收回。 | After a play leaving 10 or fewer, put out the report card showing the count; it stays until the deal ends. |
| CG22a:304 | 反复报牌、追问余牌：按第四类判罚尺度判罚。 | Reporting repeatedly or asking for the remaining count is penalised. |
| WG25:102 | 一副牌中，按牌手出完各自全手牌的先后，称为上游、二游、三游、下游。如一方两位牌手获得上游、二游，则对方称为双下。 | Same as JS17:28. |
| WG25:143 | 首副牌，由东家洗牌，南家从中间翻出一张牌（如翻出的牌为王牌或红心2，则重切重翻），从南家开始，依翻出牌张的牌点，按逆时针方向点数出首抓者，抓到翻出牌张者首圈领出牌。 | Same first-deal rule as JS17:56. |
| WG25:157 | 由上副牌的下游向上游进贡全手牌中牌点最大的一张牌（红心级牌除外），上游选择全手牌中任意一张牌点10（含）以下的牌还给下游。 | 下游 gives its highest card (heart level card excepted); 上游 returns any card of 10 or lower. |
| WG25:159 | 如上游手中牌点均超过10，则选择牌点最小的牌还牌。首圈由下游领出牌。 | If all of 上游's cards are above 10, it returns its smallest. 下游 leads. |
| WG25:161 | 如上副牌出现双下，则双下方两位牌手均向对方进贡。上游选择牌点较大的牌，对家选择牌点较小的牌，并对应还牌。 | Double-down: both give; 上游 takes the bigger, its partner the smaller, returns to match. |
| WG25:162 | 还牌时，应将牌面向下，两位进贡者同时亮牌。如贡牌牌点相同，则按顺时针方向进贡，对应还牌。还牌后，由进贡给上游者首圈领出牌。 | Returns face down, revealed together. Equal: clockwise. The giver to 上游 leads. |
| WG25:165 | 如应进贡者（下游或双下方）抓到两个大王，则不须进贡（抗贡）。首圈由上游领出牌。 | Same resistance rule as JS17:71. |
| WG25:203 | 牌手在出完一手牌后，如手中剩余牌张为10张（含）以内，应及时、准确、清楚报出剩余张数。正式比赛中可采用报牌器具报牌，禁止牌手追问和反复报牌，或在一定范围和阶段的比赛中取消报牌。 | Report the exact count at 10 or fewer; asking and repeated reports are forbidden; reporting may be cancelled in some events. |
| WG25:207 | 只有上游方可以升级，对家获得二游升3级，对家获得三游升2级，对家获得下游升1级。A级必打，获得上游，且对家获得二游或三游，才能打过A级。 | Same gains and passing-A rule as JS17:98. |
| CUP:14 | 一方打过A（过A时必须本方一位选手为头游，另一位选手不能为下游，三次打A不过时回到2重新开始） | A side passes A (one player 头游, the other not 下游; three failures at A send it back to 2). |
| CUP:23 | 王、红心2重翻）。从南家开始，依据翻出牌张的牌点，按逆时针数出首抓牌者；抓到明牌者首圈先出牌。 | (A joker or heart 2 is turned again.) Whoever draws the turned card leads the first deal. |
| CUP:33 | 首副牌，抓到明牌者先出牌；第2副牌起，进贡者先出牌；双下时，进贡牌大者先出牌；双下进贡同样牌时，头游下家先出牌；抗 | First deal: the drawer of the turned card leads; later the giver leads; after a double-down the bigger giver; on equal tributes the 头游's 下家; on resistance … |
| CUP:66 | 单贡与还牌。从第2副牌开始，在出牌前，由上副牌的下游 | Single tribute: from the second deal, before play, the previous 下游 … |
| CUP:68 | 向头游进贡全手牌中最大的一张牌（红心级牌除外），头游选择全手牌中一张牌点不超过10的牌还给下游，如全手牌均大于10，则还 | … gives 头游 its biggest card (heart level card excepted); 头游 returns a card not above 10; if all are above 10 it returns … |
| CUP:72 | 最小的牌。还牌后，首圈由下游领出牌。下游有2张大王抗贡。 | … its smallest card. 下游 leads. A 下游 holding two big jokers resists. |
| CUP:74 | 双贡与还牌。如上副牌出现双下，则双下方两名队员均向对 | Double tribute: after a double-down both players of that side … |
| CUP:76 | 方进贡，头游选择牌点较大的牌，并对应还牌；双下进贡相同牌点 | … give; 头游 takes the bigger and returns to match; on equal tribute … |
| CUP:78 | 时，则按顺时针方向进贡，对应还牌（即贡左还右），还牌时，将牌面向下，两位进贡者同时亮牌。 | … tribute goes clockwise (give left, return right); returns face down, revealed together. |
| CUP:80 | 面向下，两位进贡者同时亮牌。还牌后，均由进贡给头游者首圈领出牌。 | … revealed together; the giver to 头游 leads. |
| CUP:82 | 出牌。进贡者有2张大王抗贡。 | Givers with two big jokers resist. |
| CUP:86 | 一手牌出完，手中牌剩10张（含）以内，必须主动报牌张数， | After a play leaving 10 or fewer, the count must be reported. |
| CUP:90 | 否要牌；手中牌剩10张以内，对手问询时须回答剩余张数。 | With 10 or fewer cards, an opponent's question about the count must be answered. |
| CUP:95 | 一方打过A，本轮比赛结束，打过A方胜。 | When a side passes A the round ends and that side wins. |
| SEU:27 | 报牌：剩余6张牌要求主动报牌，剩余10张牌有问必报。 | Report at 6 cards left; at 10 cards left, answer whenever asked. |
| SEU:35 | 赢家分别还一张任意牌(不得超过10)，进贡的牌大小相同时， | The winners each return any card (not above 10); when the tributes are equal … |
| SEU:36 | 赢家可以选择花色，输家也可以选择还牌的大小，但如果每人各有一张大王或者一人有两张大王，可抗贡 | … the winners may choose suits and the losers may choose the returns; if each loser has one big joker, or one has both, they may resist. |
| SEU:40 | 赢家还一张任意牌(不得超过10)，但如果有两张大王，可抗贡。 | (Single tribute) the winner returns any card not above 10; with two big jokers the loser may resist. |
| SEU:42 | 出牌权：一般情况由进贡过的下游优先出牌，若下游形成抗贡则由上游先出；双下情况下，由进贡牌大的一家先出牌，若进贡的牌大小相同则由上游的下家先出。 | Lead: the 下游 who gave; on resistance 上游; after a double-down the bigger giver; on equal tributes the 上游's 下家. |
| SEU:46 | 如果对手是双下，赢家升3级；如果对手有一家是末游，赢家升2级；如果赢家自己对门是末游，赢家升1级。每局从2打到A，其中打A必须是赢家升1级以上为一局结束。 | Double-down +3; an opponent last +2; own partner last +1. A game runs 2 to A; at A the winner must go up "1 level or more" to finish. |
| YZU:21 | 如果是双下，两个输方要向两个头家（赢家）分主次进贡一张（除主牌红桃外的）最大的牌，赢家分别还一张任意牌，最未家可先出牌，但如果每人各有一张大王或者一人有两张大王可抗贡，但赢家先发牌。如果两个输方进贡的牌一样大，则赢家返还的任意牌两个输方可任意选牌，后选的输方先出牌。 | Double-down: both losers give their biggest card (heart level card excepted); the winners return any card; the last player may lead; one big joker each, or two in one hand, may resist and the winner leads. Equal tributes: the losers pick the returns, and the one who picks second leads. |
| NJMU:12 | 如果是双下，两个输方要向两个头游（赢家）分主次进贡一张（除主牌红桃外的）最大的牌，赢家分别还一张10以下任意牌，最末头游的下家先发牌，但如果每人各有一张大王或者一人有两张大王，可抗贡，赢家先发牌；如果进贡的牌一样大，输家二人可选择赢家还的牌，选择头游返还的牌的先出牌。 | Double-down: both losers give their biggest card; the winners return a card of 10 or lower; the 下家 of the last 头游 leads; one big joker each or two in one hand may resist. Equal tributes: the losers choose the returns; whoever takes 头游's return leads. |
| NJMU:18 | 如果打到A，对家必须有一人末游才可以最终赢得本局。打A两次未过关回到2。 | At A, an opponent must be last to win the game. Two failures at A send the side back to 2. |
