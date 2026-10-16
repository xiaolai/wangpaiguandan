# 竞技掼蛋竞赛规则（试行）— excerpt from a repost (Gansu Chess Academy)

Status: **abridged official text, reposted. Not the full edition.**

| Field | Value |
|---|---|
| Title | 竞技掼蛋竞赛规则（试行） |
| Issuing body (per the page) | 国家体育总局棋牌运动管理中心 审定 |
| Edition date (per the preface on the page) | 2022年9月8日 |
| Repost | 甘肃棋院官网, 通知公告 (`http://gansuqiyuan.com/show/id/1320.html`), posted 2024-03-07 |
| Saved copy | Downloads, `通知公告_甘肃棋院官网.mhtml`, SHA-256 `d87ef419867df7afa6cdc310095e03cbe147ded056d79ebfc8a871216cb3235f` (not copied into the repo) |
| Abridgement notice (verbatim) | 本规则内容有删减，主要供掼蛋协会裁判员、运动员临时学习参考……如需完整版，请到当地新华书店或线上平台购买。 |
| Document number | Not shown on the page |

The page says the content is abridged. Rules that the page omits are **not** known to be absent from the full edition. Treat this excerpt as the best available official wording, and check every point against the full book (人民体育出版社, per other reports; not verified).

## Quoted rule text (Chinese, verbatim from the page)

**第三条 牌型与名称**
- 8. 炸弹：四张或四张以上牌点相同的牌，如：2222、33333、444444等。
- 9. 同花顺：花色相同的顺子，如：红桃34567。
- 10. 四大天王：大王、小王各两张牌。

**第四条 牌型大小的比较**
- 三（3）. 炸弹：张数多者大，张数相同则比较牌点大小。
- 四（二）. 不同牌型之间的大小比较：四大天王＞超过五张数的炸弹＞同花顺＞五张数炸弹＞四张数炸弹＞7种牌型（单张、对子、三同张、三带对、三连对、三同连张和顺子）。
- 二（2）. A在搭配成三连对、三同连张、顺子、同花顺时，可视作最小牌点1使用。

**第二条 术语**
- 14. 级牌：牌点与上副牌上游所升至级数相同的牌为级牌，共8张。首副牌的级牌为2。红心级牌可以替代任意牌张（不含大、小王）参与不同牌型的组合。

**第三条 牌型与名称（续）**
- 2. 对子：两张牌点相同的牌型，包括两张大王和两张小王，如：22。
- 3. 三连对：三个牌点相邻的对子牌型，如：22+33+44。
- 5. 三同连张：两个牌点相邻的三同张牌型，如：222+333。
- 7. 顺子：五个牌点相邻的单张组成的牌型，如：34567。

**第五条 级牌的插带使用**
- 1. 级牌在顺子、三连对或三同连张的牌型组合中，只能按级牌牌点大小插带；
- 2. 当红桃级牌搭配牌型时，应将红桃级牌摆放于所替代的牌点位置，必要时说明牌型及牌点。

## What this changes for the draft (`rules/base-v1.md`)

1. **Bomb size (D2).** The text sets no upper limit: a bomb is four or more same-rank cards, and more cards beat fewer. The draft's "4–8 sourced, 10 by template" reading and its "10 is the maximum" default are **not supported** by this text. The official wording suggests 9 and 10 cards are legal, but it does not say how a wildcard counts. This needs a decision and a new row, not a silent change.
2. **Straight flush vs bombs.** Bombs of more than five cards beat a straight flush; bombs of five or fewer do not. This matches the draft's C8.
3. **Four jokers.** 四大天王 is the top combination. Matches C9.
4. **Wildcard.** 红心级牌 replaces any card except the jokers. Matches C12–C13.
5. **Level cards.** There are 8 level cards; the first deal's level is 2. Level cards in straights, triples-in-sequence and triple-pairs are placed at their own rank. Matches C11.
6. **A as low card.** A counts as 1 only inside 三连对, 三同连张, 顺子, 同花顺. The text does not mention a K-A-2-3-4 wrap-around. This matches "A-low allowed" only; D1 stays open.
7. **Triple-with-pair.** Compared on the triple only (R-row for 三带对).

## Not a source for

- `guandanmaster.org/rules.html` (掼蛋大师, a fan site). A fetch summary of that page reports a ranking "天炸、十张炸弹、九张炸弹…". That wording comes from a tool-summarised fetch, not from a saved copy, so it has not been checked character by character. Even taken at face value, it is not an official source and it conflicts with the abridged official text, which has no 9- or 10-card ranking. Do not cite it.
