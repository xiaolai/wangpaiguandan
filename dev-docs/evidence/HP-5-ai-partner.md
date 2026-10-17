---
title: "HP-5 — human partner sessions with the baseline AI (procedure)"
created: 2026-10-15
status: "Procedure only. Not run: it needs human players who know Guandan and an Android phone."
---

# HP-5 — AI as a partner

Plan references: WI-07 acceptance ("HP-5 human partner sessions recorded"); risk table "Baseline AI
is a poor partner" (`PairedEvaluationTest` plus HP-5); ledger GDN-057 (partner policy), GDN-064
(evaluation corpus). `PairedEvaluationTest` measures strength against random-legal play; it says
nothing about whether a person finds the AI a sensible partner.

Outcome: not run

## 1. What it proves

| Claim | Why a unit test cannot prove it |
|---|---|
| The AI partner does not overtake a leading human partner needlessly, and supports the partner's run | `PartnerPolicyTest` checks scripted spots, not real play |
| Players judge the AI partner acceptable | A human judgment |

## 2. Preconditions

1. `./tools/ci.sh` passed on the commit; default build installed. Diagnostics show the AI policy
   in use only indirectly: the baseline policy is `baseline-heuristic` 1.0.0
   (`BaselineHeuristicPolicy`); record the commit so the policy version is fixed.
2. At least three participants who play Guandan regularly; no personal data beyond "experience in
   years".
3. Any G5 phone (G5 unsigned; state the model and Android version).

## 3. Steps

1. Each participant plays a solo match (main menu → 单人练习): the AI is their partner (north) and
   both opponents. At least three deals each, including at least one tribute.
2. The observer records every moment the participant objects to the partner's play (deal, trick,
   what the AI played, what the participant expected).
3. After the session, ask: (a) did the partner ever take a trick from you needlessly? (b) did the
   partner help you finish? (c) rate the partner 1–5. Record answers verbatim.
4. Record diagnostics at the end: `host.ai_rejected`, `host.ai_cancelled`, `host.violations`
   (all expected 0).

## 4. Result record

Copy this file to `dev-docs/evidence/HP-5-result-<date>.md` and fill:

| Participant | Deals | Objections (count, examples) | Answers (a) (b) | Rating |
|---|---|---|---|---|
| | | | | |

There is no numeric pass threshold in the plan; the record is evidence for the owner. Any
objection that reproduces as a scripted spot becomes a `PartnerPolicyTest` case.

End the result file with a line `Outcome: RECORDED` (or `Outcome: FAIL — <reason>` if the session
could not be completed).
