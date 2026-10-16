---
title: "HP-4 — TalkBack and VoiceOver deal, 200% text, grayscale (procedure)"
created: 2026-10-15
status: "Procedure only. Not run: it needs a person who uses TalkBack daily and an Android phone; the VoiceOver half needs the iPhone client (WI-11, gate G3)."
---

# HP-4 — screen-reader usability

Plan references: M1a exit criterion 4; WI-08 acceptance ("HP-4 TalkBack deal"); WI-11 acceptance
("VoiceOver completes a deal"); ledger GDN-077 (turn clarity), GDN-097 (TalkBack and VoiceOver),
GDN-098 (200% text), GDN-099 (grayscale). Automated checks that precede it: `CardSemanticsTest`,
`TurnClarityTest`, `TouchTargetTest`, `LargeTextClipTest`, `ContrastTokenTest`,
`HiddenCountSemanticsTest`. They prove the semantics exist, not that a person can play with them.

Outcome: not run

## 1. What it proves

| Claim | Why a unit test cannot prove it |
|---|---|
| A screen-reader user completes a deal unaided | Usability is a human judgment |
| At 200% system text the table stays usable, not only unclipped | `LargeTextClipTest` checks layout, not comprehension |
| In grayscale every state remains distinguishable (wildcard badge 配, selection, turn) | Colour-independence is judged by eye |

## 2. Preconditions

1. `./tools/ci.sh` passed on the commit; default build installed.
2. Participant: a regular TalkBack user (not the developer), informed of the purpose; no personal
   data recorded beyond "regular TalkBack user, years of use".
3. Phone: any G5 phone (G5 unsigned; state the model). Record Android version and TalkBack version.
4. VoiceOver half: blocked until WI-11 provides an iPhone client.

## 3. Steps

1. TalkBack on, system font size at its largest setting that reaches 200% (record the exact
   setting), 设置 → 大牌模式（130%） left at default.
2. Main menu → 单人练习. The participant plays one complete deal without sighted help. The observer
   only notes; help given is recorded with the moment it was needed.
3. Scenarios to reach during the deal (observer prompts the goal, not the gestures): find whose
   turn it is and who leads the trick (GDN-077); select a pair and play it; play a wildcard and hear
   its declared reading; pass; hear an opponent's remaining-card report only when the profile makes
   it public (hidden counts are absent, GDN-079); read the result screen.
4. Turn on the system grayscale (Developer options → Simulate color space → Monochromacy, or the
   vendor's grayscale mode). Sighted observer checks that 配 badge, selected cards, the turn
   marker and disabled controls are distinguishable without colour.
5. Record time to complete the deal, every point of confusion, and every place help was given.

## 4. Result record

Copy this file to `dev-docs/evidence/HP-4-result-<date>.md` and fill:

| Field | Value |
|---|---|
| Commit, device, Android and TalkBack versions | |
| Font-size setting used | |
| Deal completed unaided (yes / with help at …) | |
| Confusions (moment, what was announced, what was expected) | |
| Grayscale check (each state: distinguishable yes/no) | |
| VoiceOver half | blocked: WI-11 |

Pass: the deal is completed without sighted help, and every grayscale state is distinguishable.

End the result file with a line `Outcome: PASS` or `Outcome: FAIL — <reason>`.
