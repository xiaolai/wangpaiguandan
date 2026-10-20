# Base rule spec v1 (G1a)

**Status: SIGNED 2026-10-16 by the project owner (gate G1a); no Guandan rules reviewer signed.**
**Date:** 2026-10-14 (draft); signed 2026-10-16
**Sign-off:** the plan asked for the owner and a Guandan rules reviewer. The project owner signed alone, on the basis of the abridged 2022 trial text only (`rules/sources/competitive-rules-2022-trial-gansu-excerpt.md`); the printed 2017 and 2022 rule books were not checked; D2 (9- and 10-card bombs) and D11 (faithfulness of the reposted national texts) are left as the stated defaults; the owner is not a Guandan expert. Record: `dev-docs/decisions/G1a-base-rules.md`. The released profile is `P1-base-v1` 1.0.0 (`rules/profiles/P1-base-v1.json`); during development it was the provisional profile P0 (`P0-provisional`, same option values).

**History:** sections 0–9 below were written as a draft on 2026-10-14 and are unchanged by the signature; where they say "until signed" or "default", the default is now the signed decision, except that D2 and D11 remain defaults accepted without the source check they ask for.

Audit of this draft: `rules/base-v1.audit.md`.

## 0. How to read this spec

This spec says which plays are legal in one deal of Guandan (掼蛋) and which play beats which. It does not cover tribute, level gains, passing A or scoring; those belong to the match profile (gate G1b).

Every rule line ends with a tag in square brackets:

| Tag | Meaning |
|---|---|
| `[E12]` | The rule is stated by evidence item E12. Appendix A quotes the exact Chinese sentence, its file and line, and an English gloss. |
| `[DERIVED from E01, E33]` | No source states the rule in one sentence, but it follows directly from the quoted sentences (for example by counting cards). A reviewer should confirm the reasoning. |
| `[... — D2]` | The rule depends on decision D2 in section 9. Until D2 is signed, the stated behaviour is a **default**, not a sourced rule. |
| `[UNSOURCED — D7]` | No source found. The text is a default proposed for decision D7. |
| `[PRODUCT — D6]` | Not a rule of the card game; a choice about how this app represents plays. |

**Where expert judgment is needed:** every line tagged with a `D` number, every `DERIVED` line, and the source authority question in D11. A reader who is not a Guandan player can check the tags against Appendix A, but cannot judge whether the defaults match how the game is played. That is the rules reviewer's job.

### Sources and precedence

Full source register, URLs and SHA-256 hashes: Appendix B. Text extractions used for line numbers live in `rules/sources/text/`.

| Key | Document | Kind | Weight in this spec |
|---|---|---|---|
| JS17 | 《江苏省掼蛋竞赛简易规则》（2017版）, Jiangsu Provincial Social Sports Management Centre | Primary, official, provincial (archived original file) | **Base document.** Used first wherever it speaks. |
| HA17 | 《淮安掼蛋竞赛规则》 national edition, approved January 2017 by the Chess and Card Sports Management Centre of the General Administration of Sport | Secondary repost of the national text | Used where JS17 is silent: sequence bounds, wrap-around examples, wildcard examples, declaration duty. |
| CG22a | 《竞技掼蛋竞赛规则（试行）》, same national centre, 2022 | Secondary repost (cross-checked against a second repost, CG22b) | Corroboration, and the level-card rule for sequences. |
| WG25 | 《世界掼牌（掼蛋）竞赛规则（2025）》, 世界体育总会掼蛋专业委员会 | Primary PDF of a body whose standing was not verified | Corroboration only. |
| PD17 | People's Daily report, 2017-04-15 | News | Corroborates HA17's sequence-length wording. |
| SEU, YZU, NJMU | University staff tournament rules | Event rules sharing one template text ("TPL") | Supporting only. Three copies of one template count as **one** source. |
| CUP | China University of Petroleum staff event rules, 2024 | Event rules | Supporting only. |

When sources disagree, JS17 wins, then HA17, then CG22a. No disagreement between JS17, HA17, CG22a and WG25 was found on any clause below; the differences are in what each one leaves out.

## 1. Card set, levels and rank order

| ID | Rule | Tag |
|---|---|---|
| R1.1 | A deal uses two standard decks: 108 cards. Each of the four suits has two copies of every rank 2–A (26 cards per suit), plus two small jokers (小王) and two big jokers (大王). | [E01] |
| R1.2 | So every non-joker rank has exactly 8 physical cards, and every exact card (rank + suit) exists twice. | [DERIVED from E01] |
| R1.3 | Each of the four players holds 27 cards after the deal. | [E02] |
| R1.4 | The **level** (级数) of a deal is a rank from 2 to A. The first deal is played at level 2. All cards of the level's rank, in every suit, are **level cards** (级牌): 8 cards per deal. | [E03, E04] |
| R1.5 | Rank order for comparing cards, high to low: big joker, small joker, level card, A, K, Q, J, 10, 9, 8, 7, 6, 5, 4, 3, 2 (with the level's rank moved out of its natural place to sit just below the small joker). | [E05] |
| R1.6 | At level A, the A cards are the level cards. | [E06] |
| R1.7 | Suits never decide which play is bigger. Suit matters only for whether five cards form a straight flush (section 5). | [E07] |
| R1.8 | The two **heart** level cards are **wildcards** (红心级牌, also called 逢人配 or 红心参谋). Section 7 gives their rules. | [E08, E09] |

## 2. Ordinary combination types and how to follow

A **play** is one or more cards put down at once. These seven types are the ordinary (non-bomb) plays.

| ID | Type (Chinese) | Exact shape | Tag |
|---|---|---|---|
| R2.1 | Single (单张) | Any one card, jokers included. | [E10] |
| R2.2 | Pair (对子) | Two cards of the same rank. Two big jokers form a pair; two small jokers form a pair. | [E11, E64] |
| R2.3 | Big joker + small joker | **Not** a pair: they are different ranks in R1.5. | [DERIVED from E11, E05 — confirm in D3] |
| R2.4 | Triple (三同张) | Three cards of the same rank. | [E12] |
| R2.5 | Triple with pair (三带二 / 三带对) | One triple plus one pair, five cards. A triple with one extra card, or with two unmatched cards, is illegal. | [E13, E14] |
| R2.6 | Triple with pair, pair of the same rank as the triple (e.g. 777+77) | Not stated by any source. Default: illegal as a triple-with-pair (five same-rank cards are a 5-card bomb). | [UNSOURCED — D8] |
| R2.7 | Triple with pair, pair made of jokers (e.g. 333 + two big jokers) | Not stated. The definitions above compose to allow it. Default: legal. | [DERIVED from E13, E11 — confirm in D3] |
| R2.8 | Straight (顺子) | **Exactly five** single cards of consecutive ranks, any suits. Four or six or more is illegal. | [E15, E16, E17] |
| R2.9 | Three consecutive pairs (三连对, also 木板) | **Exactly three** pairs of consecutive ranks (six cards). Two consecutive pairs, or four or more, are illegal. | [E18, E19] |
| R2.10 | Two consecutive triples (二连三 / 钢板 / 三同连张) | **Exactly two** triples of consecutive ranks (six cards). Three or more consecutive triples are illegal. | [E20, E21] |
| R2.11 | Jokers inside a straight, consecutive pairs or consecutive triples | Not allowed. Only the template text says so; the official texts are silent. | [E22 TPL — confirm in D4] |

Sequence shapes (straight, consecutive pairs, consecutive triples, straight flush) have more rules in section 3.

### Following

| ID | Rule | Tag |
|---|---|---|
| R2.12 | The player who leads a round may lead any legal type. | [E25] |
| R2.13 | Each later player either passes, or plays the **same type** and a higher rank, or plays a bomb-class play (sections 4–6). Every ordinary type has a fixed card count (R2.1–R2.10), so the same type always means the same number of cards. | [E25, E24, E40; same card count DERIVED from E10–E21] |
| R2.14 | Different ordinary types never beat each other (a triple cannot beat a pair). | [E24] |
| R2.15 | Within one ordinary type, the higher rank wins (R1.5). Triple-with-pair compares the triple only; the attached pair does not matter. | [E24] |
| R2.16 | A sequence's rank is the rank of its highest card in sequence order. When A counts as 1 (section 3), the sequence is the lowest of its type: A-2-3-4-5 is the lowest straight and AA2233 the lowest consecutive pairs; 10-J-Q-K-A and AAKKQQ are the highest. | [DERIVED from E24, E27; extremes E43, E44 TPL — confirm in D5] |

## 3. Sequence bounds

| ID | Rule | Tag |
|---|---|---|
| R3.1 | In a straight, straight flush, consecutive pairs or consecutive triples, A may count as 1 (below 2) or as the rank above K. | [E27, E28] |
| R3.2 | So A-2-3-4-5 and 10-J-Q-K-A are both legal straights; AA2233 and QQKKAA are both legal consecutive pairs; AAA222 and KKKAAA are both legal consecutive triples. | [E28, E29, E21] |
| R3.3 | When 2 is the level card, J-Q-K-A-2 is not a legal straight and KK-AA-22 is not a legal run of consecutive pairs. The source states both only for that case. | [E28, E29] |
| R3.4 | Any other wrap-around through A, such as K-A-2-3-4 or Q-K-A-2-3 (at any level), is not stated by any source. Default: illegal, on the principle that A is either 1 or the rank above K, never both in one sequence. | [UNSOURCED — D1] |
| R3.5 | A level card inside a sequence counts at its **natural** rank only, not at its raised rank from R1.5. Example at level 2: 22 33 44 is legal consecutive pairs; KK AA 22 is not. | [E30, E31, E32, E62, E28] |
| R3.6 | A wildcard used inside a sequence takes the rank it stands for (section 7); the rank it stands for is the natural rank, under R3.5, if it stands for a level card. | [DERIVED from E48, E30] |

## 4. Bombs and bomb order

A **bomb** may be played on any ordinary play, regardless of type.

| ID | Rule | Tag |
|---|---|---|
| R4.1 | A same-rank bomb (炸弹) is four or more cards of one rank. No official source states an upper limit. | [E33, E34, E61] |
| R4.2 | Wildcards count toward a bomb's size (three 6s + one wildcard = a four-card bomb of 6s). | [E35] |
| R4.3 | With physical cards only, a bomb has at most 8 cards (R1.2). | [DERIVED from E01, E33] |
| R4.4 | 8 physical cards + 2 wildcards make a 10-card bomb. Only the template text names this; official texts imply it without naming it. | [E36, E37 TPL; DERIVED from E33, E35, E01 — confirm in D2] |
| R4.5 | 9-card bombs (8 physical + 1 wildcard, or 7 physical + 2 wildcards) and the 10-card bomb above are legal, because R4.1 has no upper bound and R4.2 lets wildcards count. No source names a 9-card bomb. Default: legal. | [DERIVED from E33, E35, E01 — confirm in D2] |
| R4.6 | The largest possible same-rank bomb is 10 cards: 8 physical cards + both wildcards. A bomb of the level's own rank has at most 8 cards, because the two wildcards are themselves two of those 8 level cards. | [DERIVED from E01, E03, E08 — confirm in D2] |
| R4.7 | A bomb beats every ordinary play (section 2). | [E38] |
| R4.8 | Between same-rank bombs: more cards wins. With equal card counts, the higher rank wins under R1.5, so a bomb of level cards beats a bomb of the same size in A. | [E38, E63, E05] |
| R4.9 | Overall order, high to low: four jokers > same-rank bombs of 6 or more cards > straight flush > 5-card bomb > 4-card bomb > every ordinary play. | [E40, E38, E39, E47] |

## 5. Straight flush

| ID | Rule | Tag |
|---|---|---|
| R5.1 | A straight flush (同花顺, also 火箭) is a straight whose five cards are all one suit: exactly five cards. | [E41, E42] |
| R5.2 | A-2-3-4-5 and 10-J-Q-K-A of one suit are legal straight flushes. Sequence bounds of section 3 apply. That they are the lowest and the highest straight flush is stated only by the template text. | [E27, E41; order E43 TPL — confirm in D5] |
| R5.3 | A straight flush beats every ordinary play and any bomb of 5 or fewer cards. It loses to any same-rank bomb of 6 or more cards and to four jokers. | [E39, E45, E38, E40] |
| R5.4 | Between straight flushes, the higher rank wins (R2.16). | [E39, E24 — confirm in D5] |
| R5.5 | Five consecutive same-suit cards are always a straight flush, never an ordinary straight, so they cannot be led as a straight that ordinary straights may follow. | [DERIVED from E41 — confirm in D9] |

## 6. Four jokers

| ID | Rule | Tag |
|---|---|---|
| R6.1 | Four jokers (四大天王, also 天王炸 / 王炸) is both big jokers and both small jokers, played together. | [E46] |
| R6.2 | Four jokers beats every other play. | [E47, E60] |
| R6.3 | A wildcard cannot stand in for a joker, so four jokers always needs all four physical jokers. | [E48, E49] |
| R6.4 | Jokers form no other multi-card play: three jokers, or a big and a small joker together (R2.3), are illegal plays. Jokers may appear only as a single, a same-kind pair, the pair in a triple-with-pair (R2.7), or four jokers. | [DERIVED from E10, E11, E46 — confirm in D3] |

## 7. Wildcards (逢人配)

| ID | Rule | Tag |
|---|---|---|
| R7.1 | The two heart level cards are wildcards. | [E08, E48] |
| R7.2 | A wildcard may stand for any non-joker card: any rank, any suit. | [E48, E49] |
| R7.3 | A wildcard can never stand for a joker. | [E48, E49] |
| R7.4 | Wildcards may complete every type: pair, triple-with-pair, bomb, consecutive pairs, straight, straight flush (in any suit) and consecutive triples. | [E48, E50, E51, E35, E52, E53, E54, E55] |
| R7.5 | A wildcard is also a level card, so it may be played as its natural self (a heart level card) as well as a stand-in. | [DERIVED from E03, E48] |
| R7.6 | When a set of cards can be read in more than one way, the player chooses the reading. Sources' examples: 7788 + two wildcards may be three consecutive pairs or two consecutive triples; KK + 66 + wildcard may be three Ks with a pair of 6s, or three 6s with a pair of Ks; a wildcard at either end of a straight may be the high or the low end. | [E56, E51, E57] |
| R7.7 | The player must state the chosen reading when it is not obvious, and must place the wildcard in the position of the card it stands for. | [E48, E58, E59] |
| R7.8 | In this app, every play that contains a wildcard carries an explicit declaration of what each wildcard stands for, always, not only when ambiguous. The legal-move generator offers every distinct reading as a separate action. A declaration that does not describe a legal play of the cards submitted is rejected. | [PRODUCT — D6] |
| R7.9 | A wildcard played alone, or together with only the other wildcard, counts as its natural self (a single level card, or a pair of level cards). It is not declared as another rank. | [UNSOURCED — D7] |
| R7.10 | A declaration names the rank each wildcard stands for, and names a suit only when the play is a straight flush. Elsewhere suit cannot change the result (R1.7), so suit variants are not separate actions. | [PRODUCT — D6] |
| R7.11 | If a wildcard can complete five consecutive same-suit cards, the player may declare it in that suit (a straight flush) or, where the ranks allow, as an ordinary straight. Both readings are legal actions. | [DERIVED from E48, E41 — confirm in D9] |

## 8. Rule profile

| ID | Rule | Tag |
|---|---|---|
| R8.1 | Rules are data: each profile sets the registered options it changes (`rules/profiles/`, registry in `RuleOptions`). The defaults in section 9 are the registry defaults. An option key that is not registered is rejected. | [PRODUCT — D10, owner decision 2026-10-14] |

## 9. Unresolved

Each entry below needs a signed decision. Until then the **default** is what the draft and its tests assume. A default is a proposal, not a sourced rule.

| ID | Question | Why sources do not settle it | Rules affected | Suggested default (labelled **default**) | Decides |
|---|---|---|---|---|---|
| D1 | Is a wrap-around sequence through A legal (K-A-2-3-4, Q-K-A-2-3, KK-AA-22 when 2 is not the level)? | HA17 forbids J-Q-K-A-2 and KK-AA-22 only in examples where 2 is the level card (E28, E29). No source states the general rule. | R3.3, R3.4 | **Default:** illegal in every case. A is either 1 or above K, never both in one sequence. | Rules reviewer |
| D2 | Are 9-card bombs legal (8+1 wildcard, 7+2 wildcards), and is 10 the maximum? | **Status: pending full-edition check.** The abridged 2022 trial text (甘肃棋院 repost, `rules/sources/competitive-rules-2022-trial-gansu-excerpt.md`) also gives 4 as the minimum and no maximum. The page says the full edition is longer, so this does not settle the question. Every source gives 4 as the minimum and no maximum (E33, E34, E61). Only the template text names 10 (8 + both wildcards, E36, E37). No source names 9. The 9-card and 10-card rules are arithmetic on the card set (R1.2) plus "wildcards count" (E35). | R4.4, R4.5, R4.6; WI-02 `SevenBomb27CardTest`, `LargeBombCountOracleTest` | **Default:** sizes 4–10 are all legal, including 8+1, 7+2 and 8+2. Level-rank bombs max 8. | Rules reviewer |
| D3 | Joker-only plays: is big+small a pair (no)? May a joker pair be the pair in a triple-with-pair (333 + two big jokers)? Any other joker play besides single, same-kind pair and four jokers? | JS17, CG22a and WG25 say a pair includes two big or two small jokers (E11, E64). None addresses big+small, or jokers inside triple-with-pair. HA17's pair definition does not mention jokers. | R2.3, R2.7, R6.4 | **Default:** big+small is not a pair; a same-kind joker pair may be the pair in a triple-with-pair; no other joker plays. | Rules reviewer |
| D4 | Are jokers excluded from sequences? | Only the template text says "不包括双王" (E22). The official texts are silent, though no reading of their rank order makes a joker part of a natural sequence. | R2.11 | **Default:** excluded. | Rules reviewer |
| D5 | How are two sequences of the same type compared? | Sources say "compare rank" (E24) without saying which card. Only the template gives the lowest and highest examples (E43, E44). | R2.16, R5.4 | **Default:** compare the highest card in sequence order; an A-low sequence's highest card is its top non-A card (A-2-3-4-5 ranks as 5-high). | Rules reviewer |
| D6 | Does every play with a wildcard carry an explicit declaration? | Sources require a statement only "when necessary" (E58, E59) or "for a special type" (E48). This is a product choice, not a rule. | R7.8, R7.10; WI-02 `WildcardDeclarationTest`, `LegalActionOracleTest` | **Default:** yes, always explicit; the generator emits one action per distinct reading; the declaration carries a suit only for straight flushes. | Owner |
| D7 | May a lone wildcard, or the two wildcards alone, be declared as another rank (for example a single wildcard declared as a 3)? | No source addresses it. Declaring a lower rank never helps beat a play, but it changes the set of legal actions the generator must emit. | R7.9; WI-02 `LegalActionOracleTest` | **Default:** no; alone or together they count as their natural level-card self. | Rules reviewer |
| D8 | Is five-of-a-rank (777+77) a legal triple-with-pair? | E13 does not require the pair to differ in rank from the triple. HA17 requires the pair to be "相同牌点" (same rank as each other, E14), which does not answer this. | R2.6 | **Default:** illegal as a triple-with-pair; the cards may still be played as a 5-card bomb. | Rules reviewer |
| D9 | May five consecutive same-suit cards be played as an ordinary straight? And when a wildcard completes such a run, may the player declare it in another suit to make an ordinary straight? | Sources define straight flush as "a straight of one suit" (E41) and never say whether it may be downgraded. E48 lets a wildcard take any suit, which makes the wildcard case a player choice. | R5.5, R7.11 | **Default:** five physical same-suit cards are always a straight flush; a wildcard-completed run may be declared either way. | Rules reviewer |
| D10 | Do profiles expose options? | Owner decision, 2026-10-14: yes. Rules may be replaced, added or removed as data. | R8.1; WI-02 `RuleProfileTest`, `RuleProfileVariantTest` | **Decided:** options are allowed; an unregistered option key is rejected. | Owner (decided) |
| D11 | Are the reposted national texts (HA17, CG22a) faithful? | The official files were not found online. HA17 is a 2022 repost on a third-party site; CG22a/b are reposts on sohu.com. Only part of HA17 is corroborated (by PD17), and the two CG22 reposts agree with each other. | Every rule citing E06, E14, E16, E19, E21, E28–E32, E35, E42, E48, E50–E57, E59–E63 | **Default:** treat them as faithful; the reviewer checks the printed 2022 edition (人民体育出版社) or another authoritative copy before signing. | Owner + rules reviewer |
| D12 | When `sequence.wrapAroundA` is on, how is K-A-2-3-4 ranked? | Not stated by any source. The engine treats the run as ending on 4, so it sits below A-2-3-4-5. | R3.x (sequences); D1 | **Default:** rank by the last card (4-high), below A-2-3-4-5. | Rules reviewer |
| D13 | When `sequence.jokersAllowed` is on, where do jokers sit in a run? | Not stated. Jokers extend the order past A (small joker, then big joker) and never wrap. | R3.x; D4 | **Default:** small joker, then big joker, after A; no wrap. | Rules reviewer |
| D14 | When `joker.bigSmallIsPair` is on, how is a big+small joker pair ranked? | Not stated. The pair ranks as a small-joker pair. | R2.x (pairs); D3 | **Default:** ranks as a small-joker pair. | Rules reviewer |
| D15 | When declarations are optional, is one still required for a play with more than one reading? | Not stated. Declaration is required whenever the cards allow more than one reading. | R7.x (wildcards); D6 | **Default:** required when more than one reading exists. | Owner |
| D16 | Is a declaration that names a suit for a play that is not a straight flush rejected or ignored? | Not stated. UN02 is open. | R7.x; D6 | **Default:** rejected. | Owner |
| D17 | A wildcard used as its own natural rank inside a run whose other cards share one suit: straight or straight flush? | Not stated. Follows `wildcard.flushRunAsStraight`. | R5.x (straight flush); D9 | **Default:** the option value (on, so it counts as a straight). | Rules reviewer |
| D18 | Is `bomb.maxSize` null (no limit) the same as 10? | Not stated. The card set caps a bomb at 10 for a single rank, so null and 10 give the same legal set today. | R4.x; D2 | **Default:** null behaves as 10. | Rules reviewer |
| D19 | Play order and trick end. Added 2026-10-14 by WI-03, which needs a rule base-v1 does not state. | base-v1 has no rule row for play order (section 0 scopes it to legality). The sources do state it: JS17 line 77 (出牌以逆时针为序 … 如其余各家均放弃不出，则由最后出牌者下一圈领出牌), CG22a lines 263–265. Whether to promote it to rule rows is a spec change. | WI-03 `DealReducer`, `PartnerLeadTest` | **Default:** play passes counter-clockwise to the 下家 (seat order SOUTH, EAST, NORTH, WEST); a follower may pass, and may still play later in the same trick; a trick ends when every other seat still holding cards has passed since the latest play; that play's player leads the next trick. | Owner |
| D20 | Who leads when the trick's last player has played out (接风)? | JS17 line 77 (由己方同伴承接下一圈领出牌（对家接风）), CG22a line 282, WG25 lines 173–174. Not a base-v1 rule row. None says what happens if the partner has also played out; under D21 that state cannot arise, because the deal has already ended. | WI-03 `DealReducer`, `PartnerLeadTest` | **Default:** the partner leads. If the partner has also played out, the deal has ended (D21), so no lead passes; the engine treats reaching that state as a bug and fails loudly. | Owner |
| D21 | When does a deal end, and what is recorded? | CG22a line 266: the deal ends when three players have played out, or when one team takes first and second place. JS17 line 77 says only 直至全副牌出完. Not a base-v1 rule row. | WI-03 `DealReducer`, `SimulationInvariantTest`; WI-04 settlement | **Default:** CG22a line 266. The finish order lists the seats that played out, in order; the event that ends the deal also lists the seats that did not (one seat, the 下游, or the two seats of a double-up, unranked). | Owner |
| D22 | Are seats that have played out skipped in the play order? | Implied by "其余各家" (JS17 line 77) and by a seat with no cards being unable to act; no source says it in one sentence. | WI-03 `DealReducer` | **Default:** yes; such a seat never has the turn and its commands are refused. | Owner |
| D23 | A submitted play whose cards and declaration admit more than one reading that beats the table (possible only in variant profiles, such as `straightFlush.playableAsStraight` on): which reading is played? | R7.6 lets the player choose, but a play command carries only cards and a declaration. | WI-03 `Command.Play.combinationType` | **Default:** the command names the combination type; without one, an ambiguous play is refused (`AmbiguousReading`). Under P1 (formerly P0) every generator action has exactly one beating reading. | Owner |
| D24 | What is public at the table during a deal? | CG22a line 270: closed tricks are turned face down and nobody may look at them. Hand sizes are reported only under the match profile's reporting rule (GDN-042, WI-04). | WI-03 `SeatProjector`, `ProjectionPrivacyTest` | **Default:** public are the level, the profile hash, the committed revision, the leader, the seat to act, the plays and passes of the current trick (cards and stated declarations), and the finish order. Not public: any hand, any hand size, the cards of closed tricks, hints. | Owner |

**Note on D1 (engine, 2026-10-14):** the registry option `sequence.wrapAroundA` is a variant switch. Off (the default) applies R3.3 and R3.4 as written. On, it deliberately overrides sourced R3.3 as well as the R3.4 default: J-Q-K-A-2 and KK-AA-22 become legal at every level, including level 2.

## Appendix A. Evidence

Each quote is verbatim from the named text extraction in `rules/sources/text/`, at the line given or within the next two lines (some PDF and HTML lines wrap). Whitespace in the source is not significant.

| ID | Source | Line | Quote (verbatim) | English gloss |
|---|---|---|---|---|
| E01 | JS17 | 20 | 全副牌共有每种花色牌各26张（2、3、4、5、6、7、8、9、10、J、Q、K、A，各2张），小王、大王各2张，共108张。 | The full deck has 26 cards of each suit (each rank 2–A twice), two small and two big jokers, 108 cards. |
| E02 | JS17 | 21 | 每位牌手应持有的全部牌张为27张 | Each player holds 27 cards. |
| E03 | JS17 | 36 | 牌点与上副牌上游所升至级数相同的所有花色牌为级牌，首副牌的级牌为2。 | All cards whose rank equals the level reached are level cards; the first deal's level is 2. |
| E04 | CG22a | 199 | 相同的牌为级牌，共8张 | … are level cards, 8 in total. |
| E05 | JS17 | 79 | 牌点由大到小排列为：大王、小王、级牌、A、K、Q、J、10、9、8、7、6、5、4、3、2。 | Rank order high to low: big joker, small joker, level card, A, K … 2. |
| E06 | HA17 | 199 | 打A时A就是参谋 | When playing level A, A is the level card. |
| E07 | CG22a | 225 | 各种牌型的大小均不比较花色。 | No type compares suits. |
| E08 | JS17 | 37 | 红心级牌（逢人配）可以和任意牌点的花色牌搭配成合理牌型使用。 | The heart level card (逢人配) may combine with suit cards of any rank to form a legal type. |
| E09 | HA17 | 96 | 每副牌都增加了2张红心级牌(又称红心参谋、逢人配、百搭) | Every deal has 2 heart level cards (also called 红心参谋, 逢人配, 百搭). |
| E10 | JS17 | 39 | 单张：任意一张牌。 | Single: any one card. |
| E11 | JS17 | 40 | 对子：两张牌点相同的牌，包括两张大王和两张小王。 | Pair: two cards of the same rank, including two big jokers and two small jokers. |
| E12 | JS17 | 42 | 三同张：三张牌点相同的牌。 | Triple: three cards of the same rank. |
| E13 | JS17 | 44 | 三带二：一个三同张和一个对子。 | Triple-with-pair: one triple and one pair. |
| E14 | HA17 | 152 | 三同张不可以带一张牌，也不可以带二张牌点不同的牌。 | A triple may not carry one card, nor two cards of different ranks. |
| E15 | JS17 | 45 | 顺子：五张牌点相邻的单张。 | Straight: five singles of adjacent ranks. |
| E16 | HA17 | 156 | 五张且只能五张相连的单张牌，花色可以不限。 | Five, and only five, consecutive singles; any suits. |
| E17 | PD17 | 142 | 即五张且只能五张相连的单张牌，花色不限 | (News quoting the 2017 national rules) i.e. five and only five consecutive singles, any suits. |
| E18 | JS17 | 41 | 三连对：三个牌点相邻对子。 | Three consecutive pairs: three pairs of adjacent ranks. |
| E19 | HA17 | 140 | 必须而且只能是三连对作为一手牌同时打出，不可以二连对，如33 44 或77 88，也不可以四连对或四个以上的连对 | Must be exactly three consecutive pairs; not two (3344, 7788), nor four or more. |
| E20 | JS17 | 43 | 二连三（钢板）：两个牌点相邻的三同张牌。 | Two consecutive triples (steel plate): two triples of adjacent ranks. |
| E21 | HA17 | 148 | 一手牌不可以有三个或三个以上相连的三同张牌，如:JJJ QQQ KKK等。A和2的使用与第三款相同。 | A play may not contain three or more consecutive triples (e.g. JJJQQQKKK). A and 2 are used as in clause 3 (consecutive pairs). |
| E22 | SEU | 8 | 不包括双王 | (Straights) do not include the jokers. Same words in YZU line 7 and NJMU line 13 (template). |
| E24 | JS17 | 80 | 同牌型比较牌点大小，牌型之间不能压牌。三带二仅比较三同张牌点大小。 | Within one type compare ranks; different types cannot beat each other. Triple-with-pair compares only the triple. |
| E25 | HA17 | 193 | 领出牌时可以出任意合理牌型，其余三家须按照相同牌型按顺序轮流选择压(盖)牌，也可以出炸弹，下一家出的牌必须大于上一家出的牌。 | The leader may lead any legal type; the other three must follow in turn with the same type, or may play a bomb; each play must be bigger than the previous one. |
| E26 | YZU | 15 | 只有当牌型相同和总张数相同的牌，才可比较大小 | Only plays of the same type and the same total card count can be compared. (Template text; also NJMU line 14.) |
| E27 | JS17 | 79 | A在搭配成三连对、二连三、顺子、同花顺时，可作1。 | In consecutive pairs, consecutive triples, straights and straight flushes, A may count as 1. |
| E28 | HA17 | 143 | 当2是参谋时，不可以KK AA 22打出，只能算22 33 44打出。而AA则既可以作为AA 22 33，也可以作为QQ KK AA打出。 | When 2 is the level card, KK AA 22 cannot be played; only 22 33 44. AA can be played as AA 22 33 or as QQ KK AA. |
| E29 | HA17 | 156 | 可以A、2、3、4、5或10、J、Q、K、A，而不能组成J、Q、K、A、2（2是参谋时）这样的顺子。 | A-2-3-4-5 or 10-J-Q-K-A are allowed, but not a straight such as J-Q-K-A-2 (when 2 is the level card). |
| E30 | CG22a | 231 | 级牌在顺子、三连对或三同连张的牌型组合中，只能按级牌牌点大小插带 | In straights, consecutive pairs or consecutive triples, a level card may be inserted only at its own (natural) rank. |
| E31 | WG25 | 177 | 级牌在搭配成三连对、二连三、顺子、同花顺时，作原牌点。 | In consecutive pairs, consecutive triples, straights and straight flushes, a level card counts at its original rank. |
| E32 | HA17 | 143 | 一对参谋可以按其自然顺序参加三连对同时打出。 | A pair of level cards may join consecutive pairs in its natural order. |
| E33 | JS17 | 47 | 炸弹：四张或四张以上牌点相同的单张。 | Bomb: four or more cards of the same rank. |
| E34 | CG22a | 211 | 炸弹：四张或四张以上牌点相同的牌，如:2222、33333、444444等。 | Bomb: four or more cards of the same rank, e.g. 2222, 33333, 444444. |
| E35 | HA17 | 212 | 四张及以上炸弹：如666(任意花色)+红心2，算四张6等 | Bombs of four or more: e.g. 666 (any suits) + heart 2 counts as four 6s. |
| E36 | NJMU | 15 | 如2张红桃主牌和八张一起，可变为10张的炸弹。 | With the 2 heart level cards together with eight (same-rank cards), it can become a 10-card bomb. (Template.) |
| E37 | SEU | 5 | 如和八张炸弹一起，可变为 10 张炸弹 | Together with an eight-card bomb, it can become a 10-card bomb. (Template; also YZU line 5.) |
| E38 | JS17 | 81 | 炸弹：张数多者大，张数相同比较牌点大小。炸弹可压单张、对子、三连对、三同张、二连三、三带二、顺子等牌型。超过5张的炸弹可以压同花顺。 | Bombs: more cards is bigger; equal counts compare rank. A bomb beats single, pair, consecutive pairs, triple, consecutive triples, triple-with-pair, straight. A bomb of more than 5 cards beats a straight flush. |
| E39 | JS17 | 82 | 同花顺：比较牌点大小。同花顺可压不超过5张的炸弹。 | Straight flush: compare ranks. A straight flush beats bombs of at most 5 cards. |
| E40 | CG22a | 227 | 四大天王＞超过五张数的炸弹＞同花顺＞五张数炸弹＞四张数炸弹＞7种牌型 | Four jokers > bombs of more than five > straight flush > five-card bomb > four-card bomb > the 7 ordinary types. |
| E41 | JS17 | 46 | 同花顺（火箭）：花色相同的顺子。 | Straight flush (rocket): a straight of one suit. |
| E42 | HA17 | 166 | 五张且只能五张相连、花色相同的顺子 | A straight of five and only five consecutive cards of one suit. |
| E43 | YZU | 6 | 最大的为同花 10-J-Q-K-A ，最小的为 A- 2-3-4 -5 | (Straight flush) the largest is 10-J-Q-K-A of one suit, the smallest A-2-3-4-5. (Template.) |
| E44 | YZU | 8 | 最大的是 AAKKQQ, 最小的是 AA2233 | (Consecutive pairs) the largest is AAKKQQ, the smallest AA2233. (Template.) |
| E45 | WG25 | 183 | 同花顺可压单张、对子、三连对、三同张、二连三、三带对、顺子及 5 张（含）以内的炸弹。 | A straight flush beats single, pair, consecutive pairs, triple, consecutive triples, triple-with-pair, straight, and bombs of 5 cards or fewer. |
| E46 | JS17 | 48 | 四大天王（天王炸）：大王、小王各两张。 | Four jokers: two big jokers and two small jokers. |
| E47 | JS17 | 83 | 四大天王：可压所有牌型。 | Four jokers beat every type. |
| E48 | HA17 | 201 | 红心X可作为万能牌配用，称为红心参谋（俗称逢人配或百搭，但不可以配大、小王）。可以配成任意牌型和花色。如是组成特定的牌型，应在打出后立即加以说明。 | Heart X (X = level) is a universal card (逢人配 / 百搭), but cannot stand for a joker. It may make any type and any suit. When it forms a particular type, the player must say so right after playing. |
| E49 | CG22a | 199 | 红心级牌可以替代任意牌张（不含大、小王）参与不同牌型的组合。 | The heart level card may replace any card (not jokers) in any type. |
| E50 | HA17 | 202 | 对子：梅花8+红心2，算一对8 | Pair: club 8 + heart 2 counts as a pair of 8s. |
| E51 | HA17 | 206 | 三带对：KK+66(可任意配不同花色)+红心2，可算三张K带一对6；如算三张6带一对K，须在打出后立即说明 | Triple-with-pair: KK + 66 + heart 2 may be three Ks with a pair of 6s; to count it as three 6s with a pair of Ks, say so right after playing. |
| E52 | HA17 | 219 | 三连对：55677或55667+红心2，均算556677的三连对 | Consecutive pairs: 55677 or 55667 + heart 2 both count as 556677. |
| E53 | HA17 | 224 | 顺子：4568或4567(任意杂花色)+红心2，均可算45678的顺子 | Straight: 4568 or 4567 + heart 2 may count as 45678. |
| E54 | HA17 | 231 | 同花顺：789J 或78910(方片)+红心2，均可算78910J的同花顺 | Straight flush: diamond 789J or 78910 + heart 2 may count as the straight flush 7-8-9-10-J. |
| E55 | HA17 | 240 | 三同连张：33344 或33444(任意花色)+红心2，均可算333444的三同连张 | Consecutive triples: 33344 or 33444 + heart 2 may count as 333444. |
| E56 | HA17 | 251 | 当二个红心参谋加在二个连对打出，如与７７８８ 同时打出时，需说明算三连对还是三同连张等。 | When both heart level cards are played with two consecutive pairs such as 7788, the player must say whether it is consecutive pairs or consecutive triples. |
| E57 | HA17 | 251 | 当配在顺子的两端时，须说明算大点还是算小点。 | When it sits at either end of a straight, the player must say whether it counts high or low. |
| E58 | JS17 | 86 | 红心级牌搭配牌型时，应摆放在所充当牌点的位置，必要时说明牌型及牌点。 | When the heart level card forms a type, place it where the card it stands for would go; state the type and rank when necessary. |
| E59 | CG22a | 232 | 当红桃级牌搭配牌型时，应将红桃级牌摆放于所替代的牌点位置，必要时说明牌型及牌点。 | Same rule in the 2022 trial text. |
| E60 | HA17 | 259 | 四大天王：4张大小王作为一手牌齐出，是最大的炸弹，可以压所有的牌。 | Four jokers, played together, are the biggest bomb and beat everything. |
| E61 | HA17 | 160 | 炸弹：四张或四张以上牌点相同的牌，如:4444、JJJJJ、777777等。 | Bomb: four or more same-rank cards, e.g. 4444, JJJJJ, 777777. |
| E62 | HA17 | 250 | 参谋既可以大于Ａ，也允许按自然顺序插在顺子或三连对、三同连张、三带对中使用。 | A level card ranks above A, and may also be inserted at its natural place in straights, consecutive pairs, consecutive triples and triple-with-pair. |
| E63 | HA17 | 256 | 张数多的炸弹可以压任何张数少的炸弹。如果炸弹的牌张数相同，则按牌点确定大小。 | A bomb with more cards beats any bomb with fewer; with the same count, rank decides. |
| E64 | CG22a | 205 | 对子：两张牌点相同的牌型，包括两张大王和两张小王 | Pair: two same-rank cards, including two big jokers and two small jokers (2022 text). |

## Appendix B. Source register

All files are in `rules/sources/`. Each new download has a sidecar `.md` with URL, retrieval date and caveats.

| Key | File | SHA-256 | URL | Sidecar |
|---|---|---|---|---|
| JS17 | `js-simple-rules-2017.doc` | `e4badcaa8b7ad111a69db378dcc4e24beb4e1eba9ef0beef2892a0d667463aeb` | Wayback snapshot of `http://www.jssports.gov.cn/module/download/downfile.jsp?classid=0&filename=1704061633399213374.doc` | `js-simple-rules-2017.md` |
| HA17 | `huaian-national-rules-2017-repost.html` | `076007074e7b61e114eba8a799b564c7989b5606d953b96c6250cd861f71f7b9` | `http://cnmzppw.com/tv/20220301153936.html` | `huaian-national-rules-2017-repost.md` |
| CG22a | `competitive-rules-2022-trial-repost-a.html` | `5409d9ba59d970f91fbd304426a64f7c3a5b88d58eaee619826f9be287e069b0` | `https://www.sohu.com/a/713406311_121124665` | `competitive-rules-2022-trial-repost.md` |
| CG22b | `competitive-rules-2022-trial-repost-b.html` | `9696bbb347d49ade0e159b3a7f8ef3e655bb90436e14b7dfb872002230dafcd1` | `https://www.sohu.com/a/621481154_121124679` | `competitive-rules-2022-trial-repost.md` |
| WG25 | `world-rules-2025.pdf` | `63ee530d6d9cf51e70a53f198097938705f3c41f1c87f68fdb5605d5490c564f` | `https://gawsf.b-cdn.net/files/` + URL-encoded file name (full URL in sidecar) | `world-rules-2025.md` |
| PD17 | `people-daily-2017-04-15.html` | `1de9c7376cb19815e63a2fd03e4f2a380399994bf2b12d0f914acb2989ea0f26` | `http://qipai.people.com.cn/GB/n1/2017/0415/c22160-29213186.html` | `people-daily-2017-04-15.md` |
| CUP | `cup-event-rules-2024.pdf` | `ff25dc13852eb0b7fc959b3fb7cb0cc6071321f5b77493fd76441bd5d1fd473e` | `https://www.cup.edu.cn/jiguan/docs/2024-03/a37a68b7f82c405f94a214c2becf7cc3.pdf` (re-downloaded 2026-10-14, hash matches) | `g1a-clause-check.md` |
| SEU | `seu-event-rules.pdf` | `9a7f20ca99230e0b7d325413cb7b892be89ff6f3f4768019cdbd00a5ce791c18` | `https://ddgh.seu.edu.cn/_upload/article/files/44/c8/f455e1d04d2a998e40454931740a/4f853bb4-29b9-45dc-9c56-7627ed4c9726.pdf` (hash matches) | `g1a-clause-check.md` |
| NJMU | `nanjing-medical-univ-rules.doc` | `0765227c26c918f3bdb93cbeee7035ab1c483e517235430403aa8a8368ca393e` | `https://gonghui.njmu.edu.cn/_upload/article/files/5d/c8/858326da4e149f73e21ef98e9356/10310dd7-085d-4b97-abf2-b75ed3462411.doc` (hash matches) | `g1a-clause-check.md` |
| YZU | `yangzhou-univ-rules.doc` | `298dd2e87d18e7c7767018ee6219290b16ffbb6b7f285b8fdd19a0b6d574c989` | **Origin not confirmed.** The Yangzhou URL found by search serves a different file (hash `4a5181e1…`). | `g1a-clause-check.md` |

The documents themselves are not in this repository: the PDFs, Word files and HTML reposts were removed before publication, and `rules/sources/README.md` records why. This register keeps each file's name, hash and URL, so a copy can be obtained again and checked against the hash the citation was made from.

Text extractions (`rules/sources/text/<Key>.txt`) were made with `textutil -convert txt` (`.doc`, `.html`) and `pdftotext -layout` (`.pdf`). Line numbers in Appendix A refer to those files.

Not used as rule sources: Chinese Wikipedia 掼蛋 and English Wikipedia Guandan. English Wikipedia contradicts HA17 on one point: it says that at level 2, "2-2-3-3-4-4 cannot be a Tube", while HA17 (E28) says 22 33 44 is the **only** legal reading at level 2. This spec follows HA17 (R3.5); the reviewer should be aware of the conflict.

## Appendix C. G1a clause map

Clause numbers from `rules/sources/g1a-clause-check.md`.

| Clause | Topic | Rules | Evidence | Status after this draft |
|---|---|---|---|---|
| C1 | Combination types | R2.1–R2.10, R4.1, R5.1, R6.1 | E10–E21, E33, E41, E46 | Sourced (JS17, HA17) |
| C2 | Straight bounds, A-low, wrap-around | R2.8, R3.1–R3.4 | E15–E17, E27–E29 | A-low/A-high sourced; J-Q-K-A-2 forbidden (level-2 example); general wrap **D1** |
| C3 | Jokers excluded from sequences | R2.11 | E22 (template only) | **D4** |
| C4 | Consecutive pairs length 3, A-low and A-high | R2.9, R3.2 | E18, E19, E28, E44 | Sourced (HA17) |
| C5 | Consecutive triples length 2 | R2.10, R3.2 | E20, E21 | Sourced (JS17, HA17) |
| C6 | Bomb minimum 4 | R4.1 | E33, E34, E61 | Sourced |
| C7 | Bomb order | R4.8 | E38, E63 | Sourced |
| C8 | Straight flush vs bombs | R5.3, R4.9 | E38–E40, E45 | Sourced |
| C9 | Four jokers top | R6.2 | E47, E60 | Sourced |
| C10 | Joker-only combinations | R2.2, R2.3, R2.7, R6.4 | E11, E64 | Joker pairs sourced; rest **D3** |
| C11 | Level card rank and its place in sequences | R1.4–R1.6, R3.5 | E03–E06, E30–E32, E62 | Sourced (CG22a, WG25, HA17) |
| C12 | Wildcard substitutes any non-joker card | R7.2, R7.4 | E48–E55 | Sourced |
| C13 | Wildcard cannot be a joker | R7.3, R6.3 | E48, E49 | Sourced |
| C14 | Wildcard declaration | R7.6–R7.9 | E48, E51, E56–E59 | Duty to declare sourced; "always explicit" is product **D6**; lone wildcards **D7** |
| C15 | 9- and 10-card bombs with wildcards | R4.4, R4.5 | E35–E37 | 10 named by template only; 9 derived; **D2** |
| C16 | Maximum bomb size | R4.6 | E01, E33 | Derived (no stated maximum); **D2** |
| C17 | Joker count limit | R1.1, R6.3, R6.4 | E01, E46, E48 | Card set sourced; joker-play limits **D3** |
