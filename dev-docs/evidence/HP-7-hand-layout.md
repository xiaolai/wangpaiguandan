---
title: "HP-7 — older-player hand-layout test, layout A vs B (procedure)"
created: 2026-10-15
status: "Procedure only. Not run, and blocked: layout B is not built (HandLayout has only A), and the layout B decision is open (plan section 3, non-blocking design decisions)."
---

# HP-7 — hand layout A vs B

Plan references: section 3 non-blocking decision "Hand layout B" (default layout A until decided;
must be decided before the M1b human usability test); section 6 HP-7; WI-08 rollback ("layout A
stays the fallback"); `app/UI-STATE-COVERAGE.md` (L4 waits on the layout B decision).

Outcome: not run

## 1. What it proves

| Claim | Why a unit test cannot prove it |
|---|---|
| Older players find and select cards faster or with fewer errors in one layout | Human performance and preference |

## 2. Preconditions (blocking)

1. Layout B exists. Today `HandLayout` (`app/src/main/kotlin/cn/wpgd/app/data/Preferences.kt`) has
   only `A`; there is nothing to compare. This precondition fails until layout B is designed and
   built.
2. `./tools/ci.sh` passed on the commit; one G5 phone (G5 unsigned; state the model).
3. At least five participants aged 60 or over who play Guandan; no personal data beyond age band.

## 3. Steps (once layout B exists)

1. Counterbalance: half the participants start with A, half with B.
2. For each layout: 单人练习, one deal; tasks given by the observer: select a named pair, a straight,
   a bomb; play them. Record time per task and wrong selections.
3. Ask which layout they prefer and why. Record verbatim.

## 4. Result record

| Participant (age band) | Order | A: time / errors | B: time / errors | Preference |
|---|---|---|---|---|
| | | | | |

The owner decides the default layout from this record. End the result file with
`Outcome: RECORDED` or `Outcome: FAIL — <reason>`.
