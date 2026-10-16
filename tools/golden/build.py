"""Authoring script for core/src/test/resources/fixtures/rules/golden-v1.json (the single copy).

Every expected value below is written by hand from rules/base-v1.md.
Nothing here computes an expected result; the independent check lives in audit.py.
Usage: python3 -I tools/golden/build.py <repo-root>
"""
import json
import sys
from pathlib import Path

CASES = []
GOLDEN = "core/src/test/resources/fixtures/rules/golden-v1.json"


def cls(type_, rank=None, ranks=None, suit=None):
    d = {"type": type_}
    if rank is not None:
        d["rank"] = rank
    if ranks is not None:
        d["ranks"] = ranks
    if suit is not None:
        d["suit"] = suit
    return d


def table(cards, classification, declaration=None):
    return {"cards": cards, "declaration": declaration, "classification": classification}


def W(card, rank, suit=None):
    d = {"card": card, "as_rank": rank}
    if suit is not None:
        d["as_suit"] = suit
    return d


def play_case(id_, desc, level, play, *, rules, status, decisions, why,
              decl=None, tbl=None, legal, classification, exclusive=False,
              bomb_size=None, sf=False, beats=None, reject=None):
    wild = "H" + level
    CASES.append({
        "id": id_,
        "kind": "play",
        "description": desc,
        "status": status,
        "decisions": decisions,
        "rules": rules,
        "setup": {
            "level": level,
            "wildcards_held": [c for c in (play + (tbl["cards"] if tbl else [])) if c == wild],
            "hand": None,
            "table": tbl,
            "play": {"cards": play, "declaration": decl},
        },
        "expected": {
            "legal": legal,
            "classification": classification,
            "exclusive": exclusive,
            "bomb_size": bomb_size,
            "straight_flush": sf,
            "beats_table": beats,
            "rejected_reason": reject,
        },
        "why": why,
    })


S, D_ = "sourced", "default-dependent"

# ---------------------------------------------------------------- T: combination types (leads)
L9 = "9"
play_case("T01", "Single ordinary card led.", L9, ["S5"], rules=["R2.1"], status=S, decisions=[],
          legal=True, classification=cls("single", "5"), exclusive=True,
          why="R2.1: any one card is a single. One card fits no other type.")
play_case("T02", "Single big joker led.", L9, ["BJ"], rules=["R2.1"], status=S, decisions=[],
          legal=True, classification=cls("single", "BJ"), exclusive=True,
          why="R2.1: any one card, jokers included, is a single.")
play_case("T03", "Pair of 5s led.", L9, ["S5", "D5"], rules=["R2.2"], status=S, decisions=[],
          legal=True, classification=cls("pair", "5"), exclusive=True,
          why="R2.2: two cards of the same rank are a pair.")
play_case("T04", "Pair of big jokers led.", L9, ["BJ", "BJ"], rules=["R2.2"], status=S, decisions=[],
          legal=True, classification=cls("pair", "BJ"), exclusive=True,
          why="R2.2 names two big jokers as a pair.")
play_case("T05", "Pair of small jokers led.", L9, ["LJ", "LJ"], rules=["R2.2"], status=S, decisions=[],
          legal=True, classification=cls("pair", "LJ"), exclusive=True,
          why="R2.2 names two small jokers as a pair.")
play_case("T06", "Big joker + small joker is not a pair.", L9, ["LJ", "BJ"], rules=["R2.3", "R6.4", "R1.5"],
          status=D_, decisions=["D3"], legal=False, classification=None, reject="no_combination",
          why="R2.3: big and small joker are different ranks (R1.5), so not a pair; R6.4 lists no other two-joker play. Both rows are defaults under D3.")
play_case("T07", "Triple of 5s led.", L9, ["S5", "D5", "C5"], rules=["R2.4"], status=S, decisions=[],
          legal=True, classification=cls("triple", "5"), exclusive=True,
          why="R2.4: three cards of one rank.")
play_case("T08", "Triple with pair (555 + KK) led.", L9, ["S5", "D5", "C5", "SK", "DK"], rules=["R2.5", "R2.15"],
          status=S, decisions=[], legal=True, classification=cls("triple_with_pair", "5"), exclusive=True,
          why="R2.5: one triple plus one pair, five cards. Its rank is the triple's rank (R2.15 compares the triple only).")
play_case("T09", "Triple with one extra card (555 + K) is illegal.", L9, ["S5", "D5", "C5", "SK"],
          rules=["R2.5", "R4.1"], status=S, decisions=[], legal=False, classification=None,
          reject="no_combination",
          why="R2.5 (E14): a triple may not carry one card. Four cards of two ranks are not a bomb (R4.1 needs one rank); no ordinary type has four cards.")
play_case("T10", "Triple with two unmatched cards (555 + K + Q) is illegal.", L9,
          ["S5", "D5", "C5", "SK", "SQ"], rules=["R2.5"], status=S, decisions=[], legal=False,
          classification=None, reject="no_combination",
          why="R2.5 (E14): a triple may not carry two cards of different ranks. Five cards over three ranks fit no straight, bomb or straight flush either.")
play_case("T11", "Five 7s (777 + 77) read as triple-with-pair is not offered; only the 5-card bomb reading exists.",
          L9, ["S7", "S7", "D7", "C7", "H7"], rules=["R2.6", "R4.1"], status=D_, decisions=["D8"],
          legal=True, classification=cls("bomb", "7"), exclusive=True, bomb_size=5,
          why="R4.1: five cards of one rank are a bomb (sourced). R2.6 default (D8): the same cards are not also a triple-with-pair, so the bomb is the only reading.")
play_case("T12", "Straight of five mixed-suit cards 3-4-5-6-7.", L9, ["S3", "D4", "C5", "S6", "D7"],
          rules=["R2.8", "R5.1"], status=S, decisions=[], legal=True,
          classification=cls("straight", ranks=["3", "4", "5", "6", "7"]), exclusive=True,
          why="R2.8: exactly five single cards of consecutive ranks, any suits. Mixed suits, so not a straight flush (R5.1).")
play_case("T13", "Four consecutive singles 3-4-5-6 are illegal.", L9, ["S3", "D4", "C5", "S6"],
          rules=["R2.8"], status=S, decisions=[], legal=False, classification=None, reject="no_combination",
          why="R2.8: a straight is exactly five cards; four is illegal. No other type has four mixed ranks.")
play_case("T14", "Six consecutive singles 3-4-5-6-7-8 are illegal.", L9, ["S3", "D4", "C5", "S6", "D7", "C8"],
          rules=["R2.8"], status=S, decisions=[], legal=False, classification=None, reject="no_combination",
          why="R2.8 (E16 'five and only five'): six is illegal. Six singles are not pairs or triples.")
play_case("T15", "Three consecutive pairs 33-44-55.", L9, ["S3", "D3", "S4", "D4", "S5", "D5"],
          rules=["R2.9"], status=S, decisions=[], legal=True,
          classification=cls("consecutive_pairs", ranks=["3", "4", "5"]), exclusive=True,
          why="R2.9: exactly three pairs of consecutive ranks.")
play_case("T16", "Two consecutive pairs 33-44 are illegal.", L9, ["S3", "D3", "S4", "D4"], rules=["R2.9", "R4.1"],
          status=S, decisions=[], legal=False, classification=None, reject="no_combination",
          why="R2.9 (E19 names 33 44 as illegal). Not a bomb: two ranks (R4.1).")
play_case("T17", "Four consecutive pairs 33-44-55-66 are illegal.", L9,
          ["S3", "D3", "S4", "D4", "S5", "D5", "S6", "D6"], rules=["R2.9"], status=S, decisions=[],
          legal=False, classification=None, reject="no_combination",
          why="R2.9 (E19): four or more consecutive pairs are illegal.")
play_case("T18", "Two consecutive triples 333-444.", L9, ["S3", "D3", "C3", "S4", "D4", "C4"],
          rules=["R2.10"], status=S, decisions=[], legal=True,
          classification=cls("consecutive_triples", ranks=["3", "4"]), exclusive=True,
          why="R2.10: exactly two triples of consecutive ranks.")
play_case("T19", "Three consecutive triples JJJ-QQQ-KKK are illegal.", L9,
          ["SJ", "DJ", "CJ", "SQ", "DQ", "CQ", "SK", "DK", "CK"], rules=["R2.10"], status=S, decisions=[],
          legal=False, classification=None, reject="no_combination",
          why="R2.10 (E21 uses this exact example): three or more consecutive triples are illegal.")
play_case("T20", "Straight flush: spades 3-4-5-6-7.", L9, ["S3", "S4", "S5", "S6", "S7"], rules=["R5.1"],
          status=S, decisions=[], legal=True,
          classification=cls("straight_flush", ranks=["3", "4", "5", "6", "7"], suit="S"), sf=True,
          why="R5.1: a straight whose five cards share one suit. Whether it also has an ordinary-straight reading is D9 (see F20); this case does not claim exclusivity.")
play_case("T21", "Four jokers led.", L9, ["LJ", "LJ", "BJ", "BJ"], rules=["R6.1"], status=S, decisions=[],
          legal=True, classification=cls("four_jokers"), exclusive=True,
          why="R6.1: both big jokers and both small jokers played together.")
play_case("T22", "Three jokers (two small + one big) are illegal.", L9, ["LJ", "LJ", "BJ"], rules=["R6.4", "R1.5"],
          status=D_, decisions=["D3"], legal=False, classification=None, reject="no_combination",
          why="R6.4 default (D3): three jokers are not a play. Not a triple: small and big joker are different ranks (R1.5).")
play_case("T23", "Triple with a pair of big jokers (333 + BJ BJ).", L9, ["S3", "D3", "C3", "BJ", "BJ"],
          rules=["R2.7", "R2.5"], status=D_, decisions=["D3"], legal=True,
          classification=cls("triple_with_pair", "3"), exclusive=True,
          why="R2.7 default (D3): a same-kind joker pair may be the attached pair. Rank is the triple's (3).")
play_case("T24", "A joker inside a straight (10-J-Q-K + big joker) is illegal.", L9,
          ["S10", "DJ", "CQ", "SK", "BJ"], rules=["R2.11"], status=D_, decisions=["D4"], legal=False,
          classification=None, reject="no_combination",
          why="R2.11 default (D4): jokers may not be part of a straight.")

# ---------------------------------------------------------------- BS: bomb sizes
play_case("BS01", "4-card bomb of 6s.", L9, ["S6", "D6", "C6", "H6"], rules=["R4.1"], status=S, decisions=[],
          legal=True, classification=cls("bomb", "6"), exclusive=True, bomb_size=4,
          why="R4.1: four cards of one rank.")
play_case("BS02", "5-card bomb of 8s.", L9, ["S8", "S8", "D8", "C8", "H8"], rules=["R4.1"], status=S,
          decisions=[], legal=True, classification=cls("bomb", "8"), bomb_size=5,
          why="R4.1: five cards of one rank are a bomb. Not claimed exclusive: whether 888+88 is also a triple-with-pair is D8 (see T11).")
play_case("BS03", "6-card bomb of Js.", L9, ["SJ", "SJ", "DJ", "DJ", "CJ", "HJ"], rules=["R4.1"], status=S,
          decisions=[], legal=True, classification=cls("bomb", "J"), exclusive=True, bomb_size=6,
          why="R4.1: six cards of one rank. Six same-rank cards are not consecutive pairs or triples (ranks must differ).")
play_case("BS04", "7-card bomb of Qs.", L9, ["SQ", "SQ", "DQ", "DQ", "CQ", "CQ", "HQ"], rules=["R4.1"], status=S,
          decisions=[], legal=True, classification=cls("bomb", "Q"), exclusive=True, bomb_size=7,
          why="R4.1: seven cards of one rank.")
play_case("BS05", "8-card bomb of Ks (all eight physical Ks).", L9,
          ["SK", "SK", "DK", "DK", "CK", "CK", "HK", "HK"], rules=["R4.1", "R4.3", "R1.2"], status=S, decisions=[],
          legal=True, classification=cls("bomb", "K"), exclusive=True, bomb_size=8,
          why="R4.1 with R4.3: eight physical cards, the most any rank has (R1.2). At level 9, heart K is not a wildcard.")
play_case("BS06", "Wildcard completes a 4-card bomb (666 + heart 2 at level 2).", "2",
          ["S6", "D6", "C6", "H2"], decl=[W("H2", "6")], rules=["R4.2", "R7.2"], status=S, decisions=[],
          legal=True, classification=cls("bomb", "6"), bomb_size=4,
          why="R4.2 gives this exact example (E35): three 6s + one wildcard are a four-card bomb of 6s.")
play_case("BS07", "Two wildcards complete a 4-card bomb (66 + both heart 2s).", "2",
          ["S6", "D6", "H2", "H2"], decl=[W("H2", "6"), W("H2", "6")], rules=["R4.2", "R7.2"], status=S,
          decisions=[], legal=True, classification=cls("bomb", "6"), bomb_size=4,
          why="R7.2: each wildcard may stand for any non-joker card; R4.2: wildcards count toward size. 2 + 2 = 4 cards of 6.")
play_case("BS08", "Level-rank bomb using a wildcard as its natural self (999 + heart 9 at level 9).", L9,
          ["S9", "D9", "C9", "H9"], decl=[W("H9", "9")], rules=["R4.1", "R4.2", "R7.5"], status=S, decisions=[],
          legal=True, classification=cls("bomb", "9"), bomb_size=4,
          why="R7.5: the wildcard is also a level card and may count as itself; four 9s are a bomb (R4.1, R4.2).")
play_case("BS09", "9-card bomb: eight physical 7s + one wildcard (level 2).", "2",
          ["S7", "S7", "D7", "D7", "C7", "C7", "H7", "H7", "H2"], decl=[W("H2", "7")],
          rules=["R4.5"], status=D_, decisions=["D2"], legal=True, classification=cls("bomb", "7"),
          bomb_size=9, why="R4.5 default (D2): 8 physical + 1 wildcard is a legal 9-card bomb. No source names a 9-card bomb.")
play_case("BS10", "9-card bomb: seven physical 7s + both wildcards (level 2).", "2",
          ["S7", "S7", "D7", "D7", "C7", "C7", "H7", "H2", "H2"], decl=[W("H2", "7"), W("H2", "7")],
          rules=["R4.5"], status=D_, decisions=["D2"], legal=True, classification=cls("bomb", "7"),
          bomb_size=9, why="R4.5 default (D2): 7 physical + 2 wildcards is a legal 9-card bomb.")
play_case("BS11", "10-card bomb: eight physical 7s + both wildcards (level 2).", "2",
          ["S7", "S7", "D7", "D7", "C7", "C7", "H7", "H7", "H2", "H2"], decl=[W("H2", "7"), W("H2", "7")],
          rules=["R4.4", "R4.6"], status=D_, decisions=["D2"], legal=True, classification=cls("bomb", "7"),
          bomb_size=10, why="R4.4 (template only, D2): 8 + both wildcards = 10 cards, the largest bomb (R4.6, D2).")

# ---------------------------------------------------------------- SQ: sequence bounds
play_case("SQ01", "A-low straight A-2-3-4-5 (level 9).", L9, ["SA", "D2", "C3", "S4", "D5"],
          rules=["R2.8", "R3.1", "R3.2"], status=S, decisions=[], legal=True,
          classification=cls("straight", ranks=["A", "2", "3", "4", "5"]), exclusive=True,
          why="R3.1: A may count as 1; R3.2 names A-2-3-4-5 as legal.")
play_case("SQ02", "A-high straight 10-J-Q-K-A (level 9).", L9, ["S10", "DJ", "CQ", "SK", "DA"],
          rules=["R2.8", "R3.1", "R3.2"], status=S, decisions=[], legal=True,
          classification=cls("straight", ranks=["10", "J", "Q", "K", "A"]), exclusive=True,
          why="R3.2 names 10-J-Q-K-A as legal.")
play_case("SQ03", "A-low consecutive pairs AA-22-33 (level 9).", L9, ["SA", "DA", "S2", "D2", "S3", "D3"],
          rules=["R2.9", "R3.1", "R3.2"], status=S, decisions=[], legal=True,
          classification=cls("consecutive_pairs", ranks=["A", "2", "3"]), exclusive=True,
          why="R3.2 names AA2233 as legal.")
play_case("SQ04", "A-high consecutive pairs QQ-KK-AA (level 9).", L9, ["SQ", "DQ", "SK", "DK", "SA", "DA"],
          rules=["R2.9", "R3.2"], status=S, decisions=[], legal=True,
          classification=cls("consecutive_pairs", ranks=["Q", "K", "A"]), exclusive=True,
          why="R3.2 names QQKKAA as legal.")
play_case("SQ05", "A-low consecutive triples AAA-222 (level 9).", L9, ["SA", "DA", "CA", "S2", "D2", "C2"],
          rules=["R2.10", "R3.1", "R3.2"], status=S, decisions=[], legal=True,
          classification=cls("consecutive_triples", ranks=["A", "2"]), exclusive=True,
          why="R3.2 names AAA222 as legal.")
play_case("SQ06", "A-high consecutive triples KKK-AAA (level 9).", L9, ["SK", "DK", "CK", "SA", "DA", "CA"],
          rules=["R2.10", "R3.2"], status=S, decisions=[], legal=True,
          classification=cls("consecutive_triples", ranks=["K", "A"]), exclusive=True,
          why="R3.2 names KKKAAA as legal.")
play_case("SQ07", "J-Q-K-A-2 at level 2 is illegal.", "2", ["SJ", "DQ", "CK", "SA", "D2"], rules=["R3.3"],
          status=S, decisions=[], legal=False, classification=None, reject="no_combination",
          why="R3.3 (E29): when 2 is the level card, J-Q-K-A-2 is not a straight. D2 is a level card but not a wildcard (only heart 2s are).")
play_case("SQ08", "KK-AA-22 at level 2 is illegal.", "2", ["SK", "DK", "SA", "DA", "S2", "D2"], rules=["R3.3"],
          status=S, decisions=[], legal=False, classification=None, reject="no_combination",
          why="R3.3 (E28): when 2 is the level card, KK AA 22 cannot be played.")
play_case("SQ09", "22-33-44 at level 2 is legal (level card at natural rank).", "2",
          ["S2", "D2", "S3", "D3", "S4", "D4"], rules=["R3.5", "R2.9"], status=S, decisions=[], legal=True,
          classification=cls("consecutive_pairs", ranks=["2", "3", "4"]), exclusive=True,
          why="R3.5 (E28: 'only 22 33 44'): a level card in a sequence counts at its natural rank.")
play_case("SQ10", "AA-22-33 at level 2 is legal.", "2", ["SA", "DA", "S2", "D2", "S3", "D3"],
          rules=["R3.2", "R3.5", "R2.9"], status=S, decisions=[], legal=True,
          classification=cls("consecutive_pairs", ranks=["A", "2", "3"]), exclusive=True,
          why="E28 (cited by R3.2/R3.5): at level 2, AA may be played as AA 22 33. A counts as 1, the 2s at natural rank.")
play_case("SQ11", "Wrap-around K-A-2-3-4 (level 9) is illegal by default.", L9, ["SK", "DA", "C2", "S3", "D4"],
          rules=["R3.4"], status=D_, decisions=["D1"], legal=False, classification=None,
          reject="no_combination", why="R3.4 default (D1): A is either 1 or above K, never both; no source states it.")
play_case("SQ12", "Wrap-around Q-K-A-2-3 at level 2 is illegal by default.", "2", ["SQ", "DK", "CA", "S2", "D3"],
          rules=["R3.4", "R3.3"], status=D_, decisions=["D1"], legal=False, classification=None,
          reject="no_combination",
          why="R3.3 only covers J-Q-K-A-2 and KK-AA-22 at level 2; Q-K-A-2-3 falls under R3.4 default (D1).")
play_case("SQ13", "Wrap-around KK-AA-22 at level 9 is illegal by default.", L9,
          ["SK", "DK", "SA", "DA", "S2", "D2"], rules=["R3.4", "R3.3"], status=D_, decisions=["D1"], legal=False,
          classification=None, reject="no_combination",
          why="R3.3 states KK-AA-22 only for level 2; at other levels R3.4 default (D1) applies.")
play_case("SQ14", "Wrap-around straight flush spades K-A-2-3-4 (level 9) is illegal by default.", L9,
          ["SK", "SA", "S2", "S3", "S4"], rules=["R3.4"], status=D_, decisions=["D1"], legal=False,
          classification=None, reject="no_combination",
          why="R3.4 default (D1) applies to straight flushes too (section 3 covers straight flushes).")
play_case("SQ15", "Level card inside a straight at its natural rank: 5-6-7-8-9 at level 7.", "7",
          ["S5", "D6", "C7", "S8", "D9"], rules=["R3.5", "R2.8"], status=S, decisions=[], legal=True,
          classification=cls("straight", ranks=["5", "6", "7", "8", "9"]), exclusive=True,
          why="R3.5: a level card in a sequence counts at its natural rank, so club 7 fills the 7 slot.")
play_case("SQ16", "Level card used above A in a straight: J-Q-K-A-7 at level 7 is illegal.", "7",
          ["SJ", "DQ", "CK", "SA", "D7"], rules=["R3.5"], status=S, decisions=[], legal=False,
          classification=None, reject="no_combination",
          why="R3.5 (E30: only at its own rank): the 7 cannot take its raised rank above A inside a sequence.")
play_case("SQ17", "Level cards in consecutive pairs at natural rank: 66-77-88 at level 7.", "7",
          ["S6", "D6", "S7", "D7", "S8", "D8"], rules=["R3.5", "R2.9"], status=S, decisions=[], legal=True,
          classification=cls("consecutive_pairs", ranks=["6", "7", "8"]), exclusive=True,
          why="R3.5 (E32): a pair of level cards may join consecutive pairs in natural order.")
play_case("SQ18", "Level cards used above A in consecutive pairs: KK-AA-77 at level 7 is illegal.", "7",
          ["SK", "DK", "SA", "DA", "S7", "D7"], rules=["R3.5"], status=S, decisions=[], legal=False,
          classification=None, reject="no_combination",
          why="R3.5: inside a sequence the level card counts only at its natural rank, so 77 is not next to AA.")
play_case("SQ19", "Wildcard standing for the level rank in a straight flush: spade A-[2]-3-4-5 at level 2.", "2",
          ["SA", "S3", "S4", "S5", "H2"], decl=[W("H2", "2", "S")], rules=["R3.6", "R7.4", "R5.1", "R3.1"],
          status=S, decisions=[], legal=True,
          classification=cls("straight_flush", ranks=["A", "2", "3", "4", "5"], suit="S"), sf=True,
          why="R7.4: a wildcard may complete a straight flush in any suit; R3.6: standing for a level card it takes the natural rank 2; R3.1: A counts as 1.")
play_case("SQ20", "Wildcard declared as the level rank above A: J-Q-K-A-[2] at level 2 is illegal.", "2",
          ["SJ", "DQ", "CK", "SA", "H2"], decl=[W("H2", "2")], rules=["R3.3", "R3.6", "R7.8"], status=D_, decisions=["D6"],
          legal=False, classification=None, reject="declared_reading_illegal",
          why="R3.6: a wildcard standing for a level card takes the natural rank; R3.3: J-Q-K-A-2 is illegal at level 2. The cards have another legal reading (wildcard as 10), so rejecting rather than re-reading the play is R7.8 (D6).")
play_case("SQ21", "Wildcard at the low end of a straight: [3]-4-5-6-7 (level 2).", "2",
          ["D4", "C5", "S6", "D7", "H2"], decl=[W("H2", "3")], rules=["R7.6", "R7.4", "R2.8"], status=S,
          decisions=[], legal=True, classification=cls("straight", ranks=["3", "4", "5", "6", "7"]),
          why="R7.6 (E57): a wildcard at either end of a straight may be the low end.")
play_case("SQ22", "Same cards, wildcard at the high end: 4-5-6-7-[8] (level 2).", "2",
          ["D4", "C5", "S6", "D7", "H2"], decl=[W("H2", "8")], rules=["R7.6", "R7.4", "R2.8"], status=S,
          decisions=[], legal=True, classification=cls("straight", ranks=["4", "5", "6", "7", "8"]),
          why="R7.6 (E57) / R7.4 (E53: 4567 + heart 2 = 45678): the wildcard may be the high end.")

# ---------------------------------------------------------------- BR: bomb ranking
P = lambda r: cls("pair", r)
play_case("BR01", "4-card bomb beats a single ace (audit A07).", "2", ["S7", "D7", "C7", "H7"],
          tbl=table(["SA"], cls("single", "A")), rules=["R4.7"], status=S, decisions=[], legal=True,
          classification=cls("bomb", "7"), bomb_size=4, beats=True,
          why="R4.7: a bomb beats every ordinary play. At level 2, heart 7 is an ordinary card.")
play_case("BR02", "4-card bomb of 3s beats an A-high straight.", L9, ["S3", "D3", "C3", "H3"],
          tbl=table(["S10", "DJ", "CQ", "SK", "DA"], cls("straight", ranks=["10", "J", "Q", "K", "A"])),
          rules=["R4.7"], status=S, decisions=[], legal=True, classification=cls("bomb", "3"), bomb_size=4,
          beats=True, why="R4.7: card count and rank of the ordinary play do not matter.")
play_case("BR03", "4-card bomb beats a triple-with-pair (fewer cards still beats).", L9, ["S4", "D4", "C4", "H4"],
          tbl=table(["SA", "DA", "CA", "SK", "DK"], cls("triple_with_pair", "A")), rules=["R4.7"], status=S,
          decisions=[], legal=True, classification=cls("bomb", "4"), bomb_size=4, beats=True,
          why="R4.7: a bomb beats every ordinary play regardless of type.")
play_case("BR04", "5-card bomb of 5s beats a 4-card bomb of As.", L9, ["S5", "S5", "D5", "C5", "H5"],
          tbl=table(["SA", "DA", "CA", "HA"], cls("bomb", "A")), rules=["R4.8"], status=S, decisions=[],
          legal=True, classification=cls("bomb", "5"), bomb_size=5, beats=True,
          why="R4.8: between bombs, more cards wins before rank is looked at.")
play_case("BR05", "4-card bomb of Ks beats a 4-card bomb of Qs.", L9, ["SK", "DK", "CK", "HK"],
          tbl=table(["SQ", "DQ", "CQ", "HQ"], cls("bomb", "Q")), rules=["R4.8"], status=S, decisions=[],
          legal=True, classification=cls("bomb", "K"), bomb_size=4, beats=True,
          why="R4.8: equal card counts, higher rank wins.")
play_case("BR06", "Must not beat: 4-card bomb of Qs on a 4-card bomb of Ks.", L9, ["SQ", "DQ", "CQ", "HQ"],
          tbl=table(["SK", "DK", "CK", "HK"], cls("bomb", "K")), rules=["R4.8"], status=S, decisions=[],
          legal=False, classification=cls("bomb", "Q"), bomb_size=4, beats=False,
          why="R4.8: equal counts, lower rank loses.")
play_case("BR07", "Must not beat: equal 4-card bombs of 8s.", L9, ["S8", "D8", "C8", "H8"],
          tbl=table(["S8", "D8", "C8", "H8"], cls("bomb", "8")), rules=["R4.8", "R2.13"], status=S,
          decisions=[], legal=False, classification=cls("bomb", "8"), bomb_size=4, beats=False,
          why="R4.8 gives a winner only for more cards or higher rank; R2.13 (E25) requires each play to be bigger. Equal is not bigger.")
play_case("BR08", "Level-card bomb beats an A bomb of the same size (level 9).", L9, ["S9", "S9", "D9", "C9"],
          tbl=table(["SA", "DA", "CA", "HA"], cls("bomb", "A")), rules=["R4.8", "R1.5"], status=S,
          decisions=[], legal=True, classification=cls("bomb", "9"), bomb_size=4, beats=True,
          why="R4.8 states this case: a bomb of level cards beats a same-size bomb of As (R1.5 order).")
play_case("BR09", "8-card bomb of 3s beats a 7-card bomb of As.", L9,
          ["S3", "S3", "D3", "D3", "C3", "C3", "H3", "H3"],
          tbl=table(["SA", "SA", "DA", "DA", "CA", "CA", "HA"], cls("bomb", "A")), rules=["R4.8"], status=S,
          decisions=[], legal=True, classification=cls("bomb", "3"), bomb_size=8, beats=True,
          why="R4.8: more cards wins.")
play_case("BR10", "Wildcard-built bomb compares by size and rank like any bomb: 999+wildcard beats 4 As (level 9).",
          L9, ["S9", "D9", "C9", "H9"], decl=[W("H9", "9")],
          tbl=table(["SA", "DA", "CA", "HA"], cls("bomb", "A")), rules=["R4.8", "R4.2", "R7.5"], status=S,
          decisions=[], legal=True, classification=cls("bomb", "9"), bomb_size=4, beats=True,
          why="R4.2: the wildcard counts toward size (4); R7.5: as itself it is a 9, the level rank; R4.8: level beats A at equal size.")
play_case("BR11", "9-card bomb (8 threes + wildcard) beats an 8-card bomb of As (level 2).", "2",
          ["S3", "S3", "D3", "D3", "C3", "C3", "H3", "H3", "H2"], decl=[W("H2", "3")],
          tbl=table(["SA", "SA", "DA", "DA", "CA", "CA", "HA", "HA"], cls("bomb", "A")),
          rules=["R4.5", "R4.8"], status=D_, decisions=["D2"], legal=True, classification=cls("bomb", "3"),
          bomb_size=9, beats=True, why="R4.5 default (D2) makes the 9-card bomb legal; R4.8: more cards wins.")
play_case("BR12", "9-card bomb of As beats a 9-card bomb of Ks (one wildcard each, level 2).", "2",
          ["SA", "SA", "DA", "DA", "CA", "CA", "HA", "HA", "H2"], decl=[W("H2", "A")],
          tbl=table(["SK", "SK", "DK", "DK", "CK", "CK", "HK", "HK", "H2"], cls("bomb", "K"),
                    declaration=[W("H2", "K")]),
          rules=["R4.5", "R4.8"], status=D_, decisions=["D2"], legal=True, classification=cls("bomb", "A"),
          bomb_size=9, beats=True,
          why="R4.5 default (D2): both are 9-card bombs; R4.8: equal size, A above K at level 2. Each side uses one of the two heart 2s.")
play_case("BR13", "10-card bomb of 7s beats an 8-card bomb of As (level 2).", "2",
          ["S7", "S7", "D7", "D7", "C7", "C7", "H7", "H7", "H2", "H2"], decl=[W("H2", "7"), W("H2", "7")],
          tbl=table(["SA", "SA", "DA", "DA", "CA", "CA", "HA", "HA"], cls("bomb", "A")),
          rules=["R4.4", "R4.8"], status=D_, decisions=["D2"], legal=True, classification=cls("bomb", "7"),
          bomb_size=10, beats=True,
          why="R4.4 (D2) makes the 10-card bomb legal; R4.8: more cards wins. A 10-card bomb can never meet a 9-card bomb in one deal: both would need a wildcard and there are only two.")
play_case("BR14", "Four jokers beat an 8-card bomb.", L9, ["LJ", "LJ", "BJ", "BJ"],
          tbl=table(["SK", "SK", "DK", "DK", "CK", "CK", "HK", "HK"], cls("bomb", "K")), rules=["R6.2", "R4.9"],
          status=S, decisions=[], legal=True, classification=cls("four_jokers"), beats=True,
          why="R6.2: four jokers beat every other play.")
play_case("BR15", "Must not beat: 8-card bomb on four jokers.", L9, ["SA", "SA", "DA", "DA", "CA", "CA", "HA", "HA"],
          tbl=table(["LJ", "LJ", "BJ", "BJ"], cls("four_jokers")), rules=["R6.2", "R4.9"], status=S,
          decisions=[], legal=False, classification=cls("bomb", "A"), bomb_size=8, beats=False,
          why="R6.2 / R4.9: four jokers are at the top of the order; nothing beats them.")
play_case("BR16", "Four jokers beat a 10-card bomb (level 2).", "2", ["LJ", "LJ", "BJ", "BJ"],
          tbl=table(["S7", "S7", "D7", "D7", "C7", "C7", "H7", "H7", "H2", "H2"], cls("bomb", "7"),
                    declaration=[W("H2", "7"), W("H2", "7")]),
          rules=["R6.2", "R4.4"], status=D_, decisions=["D2"], legal=True, classification=cls("four_jokers"),
          beats=True, why="R6.2: four jokers beat everything; the 10-card bomb on the table exists only under R4.4 (D2).")
play_case("BR17", "Must not beat: 10-card bomb on four jokers (level 2).", "2",
          ["S7", "S7", "D7", "D7", "C7", "C7", "H7", "H7", "H2", "H2"], decl=[W("H2", "7"), W("H2", "7")],
          tbl=table(["LJ", "LJ", "BJ", "BJ"], cls("four_jokers")), rules=["R6.2", "R4.4"], status=D_,
          decisions=["D2"], legal=False, classification=cls("bomb", "7"), bomb_size=10, beats=False,
          why="R6.2: four jokers are never beaten, even by the largest bomb (R4.4, D2).")

# ---------------------------------------------------------------- SF: straight flush vs bombs
SF37 = ["S3", "S4", "S5", "S6", "S7"]
SF37c = cls("straight_flush", ranks=["3", "4", "5", "6", "7"], suit="S")
play_case("SF01", "Straight flush beats a 5-card bomb.", L9, SF37,
          tbl=table(["SA", "SA", "DA", "CA", "HA"], cls("bomb", "A")), rules=["R5.3"], status=S, decisions=[],
          legal=True, classification=SF37c, sf=True, beats=True,
          why="R5.3: a straight flush beats any bomb of 5 or fewer cards, whatever its rank.")
play_case("SF02", "Straight flush beats a 4-card bomb.", L9, SF37,
          tbl=table(["SK", "DK", "CK", "HK"], cls("bomb", "K")), rules=["R5.3"], status=S, decisions=[],
          legal=True, classification=SF37c, sf=True, beats=True, why="R5.3.")
play_case("SF03", "Must not beat: 5-card bomb on a straight flush.", L9, ["SA", "SA", "DA", "CA", "HA"],
          tbl=table(SF37, SF37c), rules=["R5.3", "R4.9"], status=S, decisions=[], legal=False,
          classification=cls("bomb", "A"), bomb_size=5, beats=False,
          why="R5.3 / R4.9: straight flush ranks above every 5-card bomb.")
play_case("SF04", "Must not beat: 4-card bomb on a straight flush.", L9, ["SK", "DK", "CK", "HK"],
          tbl=table(SF37, SF37c), rules=["R5.3", "R4.9"], status=S, decisions=[], legal=False,
          classification=cls("bomb", "K"), bomb_size=4, beats=False, why="R4.9: straight flush > 4-card bomb.")
play_case("SF05", "6-card bomb beats a straight flush.", L9, ["S3", "S3", "D3", "D3", "C3", "H3"],
          tbl=table(["H10", "HJ", "HQ", "HK", "HA"], cls("straight_flush", ranks=["10", "J", "Q", "K", "A"], suit="H")),
          rules=["R5.3", "R4.9"], status=S, decisions=[], legal=True, classification=cls("bomb", "3"),
          bomb_size=6, beats=True, why="R5.3: a straight flush loses to any same-rank bomb of 6 or more cards.")
play_case("SF06", "Must not beat: straight flush on a 6-card bomb.", L9, ["H10", "HJ", "HQ", "HK", "HA"],
          tbl=table(["S3", "S3", "D3", "D3", "C3", "H3"], cls("bomb", "3")), rules=["R5.3", "R4.9"], status=S,
          decisions=[], legal=False,
          classification=cls("straight_flush", ranks=["10", "J", "Q", "K", "A"], suit="H"), sf=True,
          beats=False, why="R5.3: even the A-high straight flush loses to a 6-card bomb.")
play_case("SF07", "6-card bomb built with two wildcards beats a straight flush (level 2).", "2",
          ["S5", "S5", "D5", "C5", "H2", "H2"], decl=[W("H2", "5"), W("H2", "5")],
          tbl=table(["S7", "S8", "S9", "S10", "SJ"], cls("straight_flush", ranks=["7", "8", "9", "10", "J"], suit="S")),
          rules=["R4.2", "R7.2", "R5.3"], status=S, decisions=[], legal=True, classification=cls("bomb", "5"),
          bomb_size=6, beats=True, why="R4.2: wildcards count, 4 + 2 = 6 cards; R5.3: 6-card bomb beats a straight flush.")
play_case("SF08", "Straight flush beats an ordinary straight.", L9, SF37,
          tbl=table(["S10", "DJ", "CQ", "SK", "DA"], cls("straight", ranks=["10", "J", "Q", "K", "A"])),
          rules=["R5.3"], status=S, decisions=[], legal=True, classification=SF37c, sf=True, beats=True,
          why="R5.3: a straight flush beats every ordinary play.")
play_case("SF09", "Higher straight flush beats lower: clubs 4-8 on spades 3-7.", L9,
          ["C4", "C5", "C6", "C7", "C8"], tbl=table(SF37, SF37c), rules=["R5.4", "R2.16"], status=D_,
          decisions=["D5"], legal=True,
          classification=cls("straight_flush", ranks=["4", "5", "6", "7", "8"], suit="C"), sf=True, beats=True,
          why="R5.4 / R2.16 default (D5): compare the highest card, 8 > 7.")
play_case("SF10", "Must not beat: straight flush of equal rank in another suit.", L9,
          ["C3", "C4", "C5", "C6", "C7"], tbl=table(SF37, SF37c), rules=["R5.4", "R2.16", "R1.7"], status=D_,
          decisions=["D5"], legal=False,
          classification=cls("straight_flush", ranks=["3", "4", "5", "6", "7"], suit="C"), sf=True, beats=False,
          why="R1.7: suit never decides; R5.4 / R2.16 (D5): equal highest card, so not bigger.")
play_case("SF11", "Four jokers beat a straight flush.", L9, ["LJ", "LJ", "BJ", "BJ"],
          tbl=table(["H10", "HJ", "HQ", "HK", "HA"], cls("straight_flush", ranks=["10", "J", "Q", "K", "A"], suit="H")),
          rules=["R6.2", "R5.3"], status=S, decisions=[], legal=True, classification=cls("four_jokers"),
          beats=True, why="R5.3 / R6.2: a straight flush loses to four jokers.")
play_case("SF12", "A-low straight flush (spades A-2-3-4-5, level 9).", L9, ["SA", "S2", "S3", "S4", "S5"],
          rules=["R5.1", "R3.1"], status=S, decisions=[], legal=True,
          classification=cls("straight_flush", ranks=["A", "2", "3", "4", "5"], suit="S"), sf=True,
          why="R5.1 with R3.1: A counts as 1 in a straight flush (E27 names 同花顺).")
play_case("SF13", "A-high straight flush (hearts 10-J-Q-K-A, level 9).", L9, ["H10", "HJ", "HQ", "HK", "HA"],
          rules=["R5.1", "R3.1"], status=S, decisions=[], legal=True,
          classification=cls("straight_flush", ranks=["10", "J", "Q", "K", "A"], suit="H"), sf=True,
          why="R5.1 with R3.1. At level 9, heart A etc. are ordinary cards.")

# ---------------------------------------------------------------- WC: wildcards
play_case("WC01", "Heart 2 + seven makes a pair of 7s that beats a pair of 6s (audit A06, level 2).", "2",
          ["S7", "H2"], decl=[W("H2", "7")], tbl=table(["S6", "D6"], P("6")),
          rules=["R7.2", "R7.4", "R2.2", "R2.13"], status=S, decisions=[], legal=True,
          classification=cls("pair", "7"), beats=True,
          why="R7.2/R7.4 (E50): a wildcard plus a 7 is a pair of 7s; R2.13: same type, higher rank.")
play_case("WC02", "One 6 + both wildcards make a triple of 6s (level 2).", "2", ["S6", "H2", "H2"], decl=[W("H2", "6"), W("H2", "6")], rules=["R7.2", "R2.4"], status=S,
          decisions=[], legal=True, classification=cls("triple", "6"),
          why="R7.2 (E48 'any type'): each wildcard stands for a 6; three 6s are a triple (R2.4).")
play_case("WC03", "A wildcard cannot stand for a joker: big joker + heart 2 declared as big joker (level 2).", "2",
          ["BJ", "H2"], decl=[W("H2", "BJ")], rules=["R7.3"], status=S, decisions=[], legal=False,
          classification=None, reject="wildcard_as_joker",
          why="R7.3: a wildcard can never stand for a joker. Read as its own self the cards are a joker and a 2, which is no type.")
play_case("WC04", "Four jokers cannot be completed by a wildcard (two small + one big + heart 2).", "2",
          ["LJ", "LJ", "BJ", "H2"], decl=[W("H2", "BJ")], rules=["R6.3", "R7.3"], status=S, decisions=[],
          legal=False, classification=None, reject="wildcard_as_joker",
          why="R6.3: four jokers always needs all four physical jokers. No other reading: four cards fit only a bomb or four jokers.")
play_case("WC05", "KK + 66 + wildcard read as three Ks with a pair of 6s (level 2).", "2",
          ["SK", "DK", "S6", "D6", "H2"], decl=[W("H2", "K")], rules=["R7.6", "R2.5"], status=S, decisions=[],
          legal=True, classification=cls("triple_with_pair", "K"),
          why="R7.6 (E51): the player chooses; three Ks + pair of 6s has rank K.")
play_case("WC06", "Same cards read as three 6s with a pair of Ks (level 2).", "2",
          ["SK", "DK", "S6", "D6", "H2"], decl=[W("H2", "6")], rules=["R7.6", "R2.5"], status=S, decisions=[],
          legal=True, classification=cls("triple_with_pair", "6"),
          why="R7.6 (E51): the alternative reading; rank is the triple's, 6.")
TWP9 = table(["S9", "D9", "C9", "S4", "D4"], cls("triple_with_pair", "9"))
play_case("WC07", "The reading decides the result: KK+66+wildcard as KKK+66 beats 999+44 (level 2).", "2",
          ["SK", "DK", "S6", "D6", "H2"], decl=[W("H2", "K")], tbl=TWP9, rules=["R7.6", "R2.15"], status=S,
          decisions=[], legal=True, classification=cls("triple_with_pair", "K"), beats=True,
          why="R2.15: only the triple counts; K > 9.")
play_case("WC08", "Must not beat: same cards as 666+KK on 999+44 (level 2).", "2",
          ["SK", "DK", "S6", "D6", "H2"], decl=[W("H2", "6")], tbl=TWP9, rules=["R7.6", "R2.15"], status=S,
          decisions=[], legal=False, classification=cls("triple_with_pair", "6"), beats=False,
          why="R2.15: only the triple counts; 6 < 9, and the pair of Ks does not help.")
C7788 = ["C7", "D7", "C8", "D8", "H2", "H2"]
play_case("WC09", "7788 + both wildcards declared as consecutive triples 777-888 (level 2).", "2", C7788,
          decl=[W("H2", "7"), W("H2", "8")], rules=["R7.6", "R2.10"], status=S, decisions=[], legal=True,
          classification=cls("consecutive_triples", ranks=["7", "8"]),
          why="R7.6 (E56 uses this example): the player may read it as consecutive triples.")
play_case("WC10", "Same cards declared as consecutive pairs 77-88-99 (level 2).", "2", C7788,
          decl=[W("H2", "9"), W("H2", "9")], rules=["R7.6", "R2.9"], status=S, decisions=[], legal=True,
          classification=cls("consecutive_pairs", ranks=["7", "8", "9"]),
          why="R7.6 (E56): or as consecutive pairs; two wildcards as 9s give 778899.")
play_case("WC11", "Same cards declared as consecutive pairs 66-77-88 (level 2).", "2", C7788,
          decl=[W("H2", "6"), W("H2", "6")], rules=["R7.6", "R7.2", "R2.9"], status=S, decisions=[], legal=True,
          classification=cls("consecutive_pairs", ranks=["6", "7", "8"]),
          why="R7.2: the wildcards may stand for any rank, so 667788 is also a consecutive-pairs reading.")
play_case("WC12", "Wildcard completes a diamond straight flush 7-8-9-[10]-J (level 2).", "2",
          ["D7", "D8", "D9", "DJ", "H2"], decl=[W("H2", "10", "D")], rules=["R7.4", "R5.1"], status=S,
          decisions=[], legal=True,
          classification=cls("straight_flush", ranks=["7", "8", "9", "10", "J"], suit="D"), sf=True,
          why="R7.4 (E54 uses this example): the wildcard stands for diamond 10.")
play_case("WC13", "Same cards declared without a suit, as an ordinary straight 7-8-9-10-J (level 2).", "2",
          ["D7", "D8", "D9", "DJ", "H2"], decl=[W("H2", "10")], rules=["R7.11", "R7.10"], status=D_,
          decisions=["D6", "D9"], legal=True, classification=cls("straight", ranks=["7", "8", "9", "10", "J"]),
          why="R7.11 default (D9): a wildcard-completed same-suit run may be declared as an ordinary straight; R7.10 (D6): a declaration without a suit is not a straight flush.")
play_case("WC14", "55-66-7 + wildcard as consecutive pairs 55-66-77 (level 2).", "2",
          ["S5", "D5", "S6", "D6", "S7", "H2"], decl=[W("H2", "7")], rules=["R7.4", "R2.9"], status=S,
          decisions=[], legal=True, classification=cls("consecutive_pairs", ranks=["5", "6", "7"]),
          why="R7.4 (E52 uses this example).")
play_case("WC15", "333-44 + wildcard as consecutive triples 333-444 (level 2).", "2",
          ["S3", "D3", "C3", "S4", "D4", "H2"], decl=[W("H2", "4")], rules=["R7.4", "R2.10"], status=S,
          decisions=[], legal=True, classification=cls("consecutive_triples", ranks=["3", "4"]),
          why="R7.4 (E55 uses this example).")
play_case("WC16", "Forged declaration: 4-5-6-8 + wildcard declared as 9 is rejected (level 2).", "2",
          ["D4", "C5", "S6", "D8", "H2"], decl=[W("H2", "9")], rules=["R7.8", "R2.8"], status=D_, decisions=["D6"],
          legal=False, classification=None, reject="declared_reading_illegal",
          why="R7.8 (D6): a declaration that does not describe a legal play is rejected. 4-5-6-8-9 is not consecutive (R2.8); only 7 would work (E53).")
play_case("WC17", "Forged declaration: 666 + wildcard declared as A is rejected (level 2).", "2",
          ["S6", "D6", "C6", "H2"], decl=[W("H2", "A")], rules=["R7.8"], status=D_, decisions=["D6"],
          legal=False, classification=None, reject="declared_reading_illegal",
          why="R7.8 (D6): 666A is no legal play, so the declaration is rejected even though declaring 6 would make a bomb.")
play_case("WC18", "Missing declaration: 666 + wildcard with no declaration is rejected (level 2).", "2",
          ["S6", "D6", "C6", "H2"], decl=None, rules=["R7.8"], status=D_, decisions=["D6"], legal=False,
          classification=None, reject="missing_declaration",
          why="R7.8 default (D6): every play containing a wildcard carries an explicit declaration. The game texts only require one 'when necessary' (E58), so this is a product rule.")
play_case("WC19", "Lone wildcard played as itself beats a single A (level 9).", L9, ["H9"], decl=[W("H9", "9")],
          tbl=table(["SA"], cls("single", "A")), rules=["R7.9", "R1.5"], status=D_, decisions=["D7"],
          legal=True, classification=cls("single", "9"), beats=True,
          why="R7.9 default (D7): alone it counts as a level card; R1.5: level card > A.")
play_case("WC20", "Lone wildcard declared as a 3 is rejected (level 9).", L9, ["H9"], decl=[W("H9", "3")],
          rules=["R7.9"], status=D_, decisions=["D7"], legal=False, classification=None,
          reject="lone_wildcard_redeclared",
          why="R7.9 default (D7): a lone wildcard is not declared as another rank.")
play_case("WC21", "Must not beat: lone wildcard on a small joker (level 9).", L9, ["H9"], decl=[W("H9", "9")],
          tbl=table(["LJ"], cls("single", "LJ")), rules=["R7.9", "R7.3", "R1.5"], status=D_, decisions=["D7"],
          legal=False, classification=cls("single", "9"), beats=False,
          why="R7.9 (D7): alone it is a level card; R7.3: it cannot be a joker; R1.5: small joker > level card.")
play_case("WC22", "Both wildcards alone are a pair of level cards and beat a pair of As (level 9).", L9,
          ["H9", "H9"], decl=[W("H9", "9"), W("H9", "9")], tbl=table(["SA", "DA"], P("A")),
          rules=["R7.9", "R1.5", "R2.15"], status=D_, decisions=["D7"], legal=True,
          classification=cls("pair", "9"), beats=True,
          why="R7.9 (D7): together alone they are a pair of level cards; R1.5: level > A.")
play_case("WC23", "Both wildcards alone declared as a pair of 3s are rejected (level 9).", L9, ["H9", "H9"],
          decl=[W("H9", "3"), W("H9", "3")], rules=["R7.9"], status=D_, decisions=["D7"], legal=False,
          classification=None, reject="lone_wildcard_redeclared",
          why="R7.9 default (D7): the two wildcards alone count as their natural selves only.")
play_case("WC24", "Wildcard as itself with another level card: spade 9 + heart 9 beat a pair of As (level 9).", L9,
          ["S9", "H9"], decl=[W("H9", "9")], tbl=table(["SA", "DA"], P("A")), rules=["R7.5", "R2.2", "R1.5"],
          status=S, decisions=[], legal=True, classification=cls("pair", "9"), beats=True,
          why="R7.5: the wildcard may count as itself (a 9); with spade 9 it is a pair of level cards. The lone-wildcard default does not apply: it is played with another card.")
play_case("WC25", "Wildcard as its natural self inside a heart straight flush 7-8-9-10-J (level 9).", L9,
          ["H7", "H8", "H9", "H10", "HJ"], decl=[W("H9", "9", "H")], rules=["R7.5", "R3.5", "R5.1"], status=S,
          decisions=[], legal=True,
          classification=cls("straight_flush", ranks=["7", "8", "9", "10", "J"], suit="H"), sf=True,
          why="R7.5: as itself it is heart 9; R3.5: a level card in a sequence takes its natural rank; five consecutive hearts (R5.1).")

# ---------------------------------------------------------------- FO: following
play_case("FO01", "Must not beat: triple on a pair.", L9, ["S6", "D6", "C6"], tbl=table(["S5", "D5"], P("5")),
          rules=["R2.14", "R2.13"], status=S, decisions=[], legal=False, classification=cls("triple", "6"),
          beats=False, why="R2.14: different ordinary types never beat each other.")
play_case("FO02", "Must not beat: straight on a triple-with-pair (both five cards).", L9,
          ["S4", "D5", "C6", "S7", "D8"], tbl=table(["S3", "D3", "C3", "SK", "DK"], cls("triple_with_pair", "3")),
          rules=["R2.14", "R2.13"], status=S, decisions=[], legal=False,
          classification=cls("straight", ranks=["4", "5", "6", "7", "8"]), beats=False,
          why="R2.14: same card count does not make the same type.")
play_case("FO03", "Must not beat: consecutive pairs on consecutive triples (both six cards).", L9,
          ["S5", "D5", "S6", "D6", "S7", "D7"], tbl=table(["S3", "D3", "C3", "S4", "D4", "C4"],
                                                           cls("consecutive_triples", ranks=["3", "4"])),
          rules=["R2.14", "R2.13"], status=S, decisions=[], legal=False,
          classification=cls("consecutive_pairs", ranks=["5", "6", "7"]), beats=False, why="R2.14.")
play_case("FO04", "Must not beat: single A on a single A of another suit.", L9, ["DA"],
          tbl=table(["SA"], cls("single", "A")), rules=["R1.7", "R2.13"], status=S, decisions=[], legal=False,
          classification=cls("single", "A"), beats=False,
          why="R1.7: suit never decides; R2.13 needs a higher rank.")
play_case("FO05", "Single level card beats a single A (level 9).", L9, ["S9"], tbl=table(["SA"], cls("single", "A")),
          rules=["R1.5", "R2.15"], status=S, decisions=[], legal=True, classification=cls("single", "9"),
          beats=True, why="R1.5: level card ranks just below the small joker, above A.")
play_case("FO06", "Small joker beats a single level card (level 9).", L9, ["LJ"],
          tbl=table(["S9"], cls("single", "9")), rules=["R1.5", "R2.15"], status=S, decisions=[], legal=True,
          classification=cls("single", "LJ"), beats=True, why="R1.5.")
play_case("FO07", "Big joker beats small joker.", L9, ["BJ"], tbl=table(["LJ"], cls("single", "LJ")),
          rules=["R1.5", "R2.15"], status=S, decisions=[], legal=True, classification=cls("single", "BJ"),
          beats=True, why="R1.5.")
play_case("FO08", "Pair of big jokers beats pair of small jokers.", L9, ["BJ", "BJ"],
          tbl=table(["LJ", "LJ"], P("LJ")), rules=["R2.2", "R1.5", "R2.15"], status=S, decisions=[], legal=True,
          classification=P("BJ"), beats=True, why="R2.2 makes both pairs; R1.5 orders them.")
play_case("FO09", "Pair of small jokers beats pair of level cards (level 9).", L9, ["LJ", "LJ"],
          tbl=table(["S9", "D9"], P("9")), rules=["R2.2", "R1.5", "R2.15"], status=S, decisions=[], legal=True,
          classification=P("LJ"), beats=True, why="R1.5.")
play_case("FO10", "Must not beat: lower pair on higher pair.", L9, ["SQ", "DQ"], tbl=table(["SK", "DK"], P("K")),
          rules=["R2.15", "R2.13"], status=S, decisions=[], legal=False, classification=P("Q"), beats=False,
          why="R2.15: Q < K.")
play_case("FO11", "Triple-with-pair compares the triple only: 333+44 beats 222+AA (level 9).", L9,
          ["S3", "D3", "C3", "S4", "D4"], tbl=table(["S2", "D2", "C2", "SA", "DA"], cls("triple_with_pair", "2")),
          rules=["R2.15"], status=S, decisions=[], legal=True, classification=cls("triple_with_pair", "3"),
          beats=True, why="R2.15: 3 > 2; the attached AA does not count.")
play_case("FO12", "Must not beat: equal triple, better pair (555+AA on 555+KK).", L9,
          ["S5", "D5", "C5", "SA", "DA"], tbl=table(["S5", "D5", "C5", "SK", "DK"], cls("triple_with_pair", "5")),
          rules=["R2.15", "R2.13"], status=S, decisions=[], legal=False,
          classification=cls("triple_with_pair", "5"), beats=False,
          why="R2.15: the pair does not matter; equal triples are not bigger (R2.13).")
play_case("FO13", "Triple of level cards beats a triple of As (level 9).", L9, ["S9", "D9", "C9"],
          tbl=table(["SA", "DA", "CA"], cls("triple", "A")), rules=["R2.15", "R1.5"], status=S, decisions=[],
          legal=True, classification=cls("triple", "9"), beats=True, why="R1.5: level > A.")
play_case("FO14", "Higher straight beats lower: 4-8 on 3-7.", L9, ["S4", "D5", "C6", "S7", "D8"],
          tbl=table(["S3", "D4", "C5", "S6", "D7"], cls("straight", ranks=["3", "4", "5", "6", "7"])),
          rules=["R2.16", "R2.13"], status=D_, decisions=["D5"], legal=True,
          classification=cls("straight", ranks=["4", "5", "6", "7", "8"]), beats=True,
          why="R2.16 default (D5): compare the highest card, 8 > 7.")
play_case("FO15", "A-low straight is the lowest: 2-6 beats A-5 (level 9).", L9, ["S2", "D3", "C4", "S5", "D6"],
          tbl=table(["SA", "D2", "C3", "S4", "D5"], cls("straight", ranks=["A", "2", "3", "4", "5"])),
          rules=["R2.16", "R3.1"], status=D_, decisions=["D5"], legal=True,
          classification=cls("straight", ranks=["2", "3", "4", "5", "6"]), beats=True,
          why="R2.16 (D5): A-2-3-4-5 ranks as 5-high, below 6-high.")
play_case("FO16", "Must not beat: equal straight in other suits.", L9, ["D3", "C4", "S5", "D6", "C7"],
          tbl=table(["S3", "D4", "C5", "S6", "D7"], cls("straight", ranks=["3", "4", "5", "6", "7"])),
          rules=["R2.16", "R1.7", "R2.13"], status=D_, decisions=["D5"], legal=False,
          classification=cls("straight", ranks=["3", "4", "5", "6", "7"]), beats=False,
          why="R1.7: suits do not count; equal highest card (R2.16, D5) is not bigger.")
play_case("FO17", "A-high straight beats K-high (level 2).", "2", ["S10", "DJ", "CQ", "SK", "DA"],
          tbl=table(["S9", "D10", "CJ", "SQ", "DK"], cls("straight", ranks=["9", "10", "J", "Q", "K"])),
          rules=["R2.16"], status=D_, decisions=["D5"], legal=True,
          classification=cls("straight", ranks=["10", "J", "Q", "K", "A"]), beats=True,
          why="R2.16 (D5): A > K at the top.")
play_case("FO18", "AA-22-33 is the lowest consecutive pairs: 22-33-44 beats it (level 9).", L9,
          ["C2", "H2", "C3", "H3", "S4", "D4"],
          tbl=table(["SA", "DA", "S2", "D2", "S3", "D3"], cls("consecutive_pairs", ranks=["A", "2", "3"])),
          rules=["R2.16", "R3.1"], status=D_, decisions=["D5"], legal=True,
          classification=cls("consecutive_pairs", ranks=["2", "3", "4"]), beats=True,
          why="R2.16 (D5): AA2233 ranks as 3-high; 223344 is 4-high. At level 9, heart 2 is ordinary.")
play_case("FO19", "KKK-AAA beats AAA-222 (level 9).", L9, ["SK", "DK", "CK", "SA", "DA", "CA"],
          tbl=table(["HA", "SA", "DA", "S2", "D2", "C2"], cls("consecutive_triples", ranks=["A", "2"])),
          rules=["R2.16", "R3.2"], status=D_, decisions=["D5"], legal=True,
          classification=cls("consecutive_triples", ranks=["K", "A"]), beats=True,
          why="R2.16 (D5): A-low AAA222 is 2-high; KKKAAA is A-high.")
play_case("FO20", "Must not beat: an ordinary straight cannot follow five same-suit cards (level 9).", L9,
          ["S4", "D5", "C6", "S7", "D8"], tbl=table(SF37, SF37c), rules=["R5.5", "R5.3"], status=D_,
          decisions=["D9"], legal=False, classification=cls("straight", ranks=["4", "5", "6", "7", "8"]),
          beats=False,
          why="R5.5 default (D9): five physical same-suit cards are only a straight flush, so the table is a bomb-class play an ordinary straight cannot beat (R5.3).")
play_case("FO21", "Triple compared against a joker-pair triple-with-pair: 444+55 beats 333+BJ BJ.", L9,
          ["S4", "D4", "C4", "S5", "D5"], tbl=table(["S3", "D3", "C3", "BJ", "BJ"], cls("triple_with_pair", "3")),
          rules=["R2.15", "R2.7"], status=D_, decisions=["D3"], legal=True,
          classification=cls("triple_with_pair", "4"), beats=True,
          why="R2.7 (D3) makes the table play legal; R2.15: only the triple counts, so the big-joker pair does not help.")

# ---------------------------------------------------------------- LA: level A (R1.6; review F16)
LA = "A"
play_case("LA01", "At level A, heart A + a seven makes a pair of 7s that beats a pair of 6s.", LA,
          ["S7", "HA"], decl=[W("HA", "7")], tbl=table(["S6", "D6"], P("6")),
          rules=["R1.6", "R1.8", "R7.2", "R7.4", "R2.2", "R2.13"], status=S, decisions=[], legal=True,
          classification=P("7"), exclusive=True, beats=True,
          why="R1.6: at level A the As are the level cards, so R1.8 makes the two heart As the wildcards. R7.2/R7.4: the heart A stands for a 7, giving a pair of 7s (R2.2). As any other rank it pairs with nothing, so this is the only reading. R2.13: same type, 7 > 6.")
play_case("LA02", "At level A, three 7s + heart A make a 4-card bomb of 7s.", LA,
          ["S7", "D7", "C7", "HA"], decl=[W("HA", "7")], rules=["R1.6", "R1.8", "R4.1", "R4.2", "R7.4"],
          status=S, decisions=[], legal=True, classification=cls("bomb", "7"), exclusive=True, bomb_size=4,
          why="R1.6 + R1.8: heart A is a wildcard. R4.2/R7.4: three 7s + a wildcard is a four-card bomb of 7s (R4.1). No other type has four cards of at most two ranks here.")
play_case("LA03", "At level A, heart A declared as a small joker is rejected.", LA,
          ["LJ", "HA"], decl=[W("HA", "LJ")], rules=["R1.6", "R1.8", "R7.3"], status=S, decisions=[],
          legal=False, classification=None, reject="wildcard_as_joker",
          why="R1.6 + R1.8: heart A is a wildcard; R7.3: a wildcard can never stand for a joker.")
play_case("LA04", "At level A, a lone heart A declared as a K is rejected.", LA,
          ["HA"], decl=[W("HA", "K")], rules=["R1.6", "R1.8", "R7.9"], status=D_, decisions=["D7"],
          legal=False, classification=None, reject="lone_wildcard_redeclared",
          why="R1.6 + R1.8: heart A is a wildcard. R7.9 default (D7): played alone it counts as its natural self, a level A, and is not declared as another rank.")
play_case("LA05", "At level A, heart A completes the hearts A-2-3-4-5 straight flush as the heart A.", LA,
          ["HA", "H2", "H3", "H4", "H5"], decl=[W("HA", "A", "H")],
          rules=["R1.6", "R1.8", "R3.1", "R5.1", "R7.4", "R7.10"], status=D_, decisions=["D6"], legal=True,
          classification=cls("straight_flush", ranks=["A", "2", "3", "4", "5"], suit="H"), sf=True,
          why="R1.6 + R1.8: heart A is a wildcard. R7.4: a wildcard may complete a straight flush; declared as the heart A (R7.10 default, D6: a suit is named for a straight flush), all five cards are hearts (R5.1) and A counts as 1 (R3.1).")
play_case("LA06", "At level A, four As (level cards) beat four Ks.", LA,
          ["SA", "SA", "DA", "CA"], tbl=table(["SK", "DK", "CK", "HK"], cls("bomb", "K")),
          rules=["R1.6", "R1.5", "R4.1", "R4.8"], status=S, decisions=[], legal=True,
          classification=cls("bomb", "A"), exclusive=True, bomb_size=4, beats=True,
          why="R4.1: four physical As (no heart A, so no wildcard) are a bomb. R4.8: equal size, higher rank wins; R1.6 + R1.5: A is the level card and outranks K.")
play_case("LA07", "Must not beat: at level A, a single A (level card) does not beat a small joker.", LA,
          ["SA"], tbl=table(["LJ"], cls("single", "LJ")), rules=["R1.6", "R1.5", "R2.15", "R2.13"],
          status=S, decisions=[], legal=False, classification=cls("single", "A"), beats=False,
          why="R1.6: A is the level card; R1.5: a level card sits just below the small joker, so R2.15 says it is lower and R2.13 does not let it follow.")
play_case("LA08", "At level A, A-2-3-4-5 is a legal straight with A as 1.", LA,
          ["SA", "D2", "C3", "S4", "D5"], rules=["R1.6", "R2.8", "R3.1", "R3.2", "R3.5"], status=S,
          decisions=[], legal=True, classification=cls("straight", ranks=["A", "2", "3", "4", "5"]), exclusive=True,
          why="R3.5: a level card inside a sequence counts at its natural rank, so the level A (R1.6) is still A; R3.1/R3.2: A may count as 1, so A-2-3-4-5 is a straight (R2.8). Mixed suits, no wildcard: one reading.")
play_case("LA09", "At level A, 10-J-Q-K-A is a legal straight with A above K.", LA,
          ["S10", "DJ", "CQ", "SK", "DA"], rules=["R1.6", "R2.8", "R3.1", "R3.2", "R3.5"], status=S,
          decisions=[], legal=True, classification=cls("straight", ranks=["10", "J", "Q", "K", "A"]), exclusive=True,
          why="R3.5: the level A (R1.6) counts at its natural rank in a sequence; R3.1/R3.2: A may sit above K, so 10-J-Q-K-A is a straight (R2.8).")
play_case("LA10", "At level A, 2-3-4-5-6 beats A-2-3-4-5.", LA,
          ["S2", "D3", "C4", "S5", "D6"], tbl=table(["SA", "D2", "C3", "S4", "D5"], cls("straight", ranks=["A", "2", "3", "4", "5"])),
          rules=["R1.6", "R2.16", "R3.1", "R3.5"], status=D_, decisions=["D5"], legal=True,
          classification=cls("straight", ranks=["2", "3", "4", "5", "6"]), beats=True,
          why="R3.5: the level A (R1.6) is a natural A inside the run; R3.1: here it counts as 1. R2.16 default (D5): A-2-3-4-5 ranks as 5-high, below 6-high.")
play_case("LA11", "At level A, 10-J-Q-K-A beats 9-10-J-Q-K.", LA,
          ["S10", "DJ", "CQ", "SK", "DA"], tbl=table(["S9", "D10", "CJ", "SQ", "DK"], cls("straight", ranks=["9", "10", "J", "Q", "K"])),
          rules=["R1.6", "R2.16", "R3.5"], status=D_, decisions=["D5"], legal=True,
          classification=cls("straight", ranks=["10", "J", "Q", "K", "A"]), beats=True,
          why="R3.5: the level A (R1.6) sits at its natural place above K; R2.16 default (D5): A-high beats K-high.")
play_case("LA12", "At level A, AA-22-3 + heart A as a 3 makes consecutive pairs AA2233.", LA,
          ["SA", "DA", "S2", "D2", "S3", "HA"], decl=[W("HA", "3")],
          rules=["R1.6", "R1.8", "R2.9", "R3.1", "R3.2", "R3.6", "R7.2"], status=S, decisions=[], legal=True,
          classification=cls("consecutive_pairs", ranks=["A", "2", "3"]),
          why="R1.6 + R1.8: heart A is a wildcard; R7.2/R3.6: it stands for a 3. R3.1/R3.2: A counts as 1, so AA2233 is three consecutive pairs (R2.9).")
play_case("LA13", "At level A, heart A as itself is the A of an A-2-3-4-5 straight.", LA,
          ["HA", "D2", "C3", "S4", "D5"], decl=[W("HA", "A")],
          rules=["R1.6", "R1.8", "R7.5", "R3.6", "R3.5", "R2.8", "R3.1"], status=S, decisions=[], legal=True,
          classification=cls("straight", ranks=["A", "2", "3", "4", "5"]),
          why="R1.6 + R1.8: heart A is a wildcard and (R7.5) also a level card that may count as itself. R3.6/R3.5: inside a run it takes the natural rank A, and R3.1 lets A be 1: a straight (R2.8). Not exclusive: declared as a 6 it makes 2-3-4-5-6.")
play_case("LA14", "At level A, KK-AA-22 is not consecutive pairs.", LA,
          ["SK", "DK", "SA", "DA", "S2", "D2"], rules=["R1.6", "R3.4", "R3.5"], status=D_, decisions=["D1"],
          legal=False, classification=None, reject="no_combination",
          why="R3.5: the level A (R1.6) counts at its natural rank, so A being the level card adds nothing. R3.4 default (D1): a run may not pass through A into 2. Six cards of three ranks fit no other type.")

# ---------------------------------------------------------------- GE: generator (legal-action) cases
SEVENS = ["S7", "S7", "D7", "D7", "C7", "C7", "H7", "H7"]
CASES.append({
    "id": "GE01", "kind": "generator",
    "description": "Hand with all eight physical 7s and both heart 2s at level 2: the seven-bombs offered (SevenBomb27CardTest).",
    "status": D_, "decisions": ["D2", "D6"], "rules": ["R4.1", "R4.2", "R4.4", "R4.5", "R7.8"],
    "setup": {"level": "2", "wildcards_held": ["H2", "H2"], "hand": SEVENS + ["H2", "H2"],
              "table": None, "play": None},
    "expected": {"bomb_rank": "7", "bomb_sizes_offered": [4, 5, 6, 7, 8, 9, 10],
                 "must_include": [{"physical": 8, "wildcards": 1, "size": 9},
                                  {"physical": 7, "wildcards": 2, "size": 9},
                                  {"physical": 8, "wildcards": 2, "size": 10}]},
    "why": "R4.1/R4.2: 4-8 from physical cards and wildcards; R4.5/R4.4 defaults (D2): 9 from 8+1 or 7+2, 10 from 8+2. R7.8 (D6): each distinct reading is its own action. The test pads the hand to 27 cards; the extra cards must not include 7s or heart 2s.",
})
CASES.append({
    "id": "GE02", "kind": "generator",
    "description": "Hand holding all four jokers and no wildcard: the four-joker bomb is offered (FourJokerBombTest).",
    "status": S, "decisions": [], "rules": ["R6.1", "R6.3"],
    "setup": {"level": "2", "wildcards_held": [], "hand": ["LJ", "LJ", "BJ", "BJ"], "table": None, "play": None},
    "expected": {"must_include_classification": cls("four_jokers")},
    "why": "R6.1: the four physical jokers together are the four-joker bomb; R6.3: no wildcard is needed or usable. The test pads the hand to 27 cards without heart 2s.",
})
CASES.append({
    "id": "GE03", "kind": "generator",
    "description": "7788 + both wildcards at level 2: every reading that uses all six cards is offered as its own action.",
    "status": D_, "decisions": ["D6"], "rules": ["R7.6", "R7.8", "R7.10", "R2.9", "R2.10"],
    "setup": {"level": "2", "wildcards_held": ["H2", "H2"], "hand": C7788, "table": None, "play": None},
    "expected": {"readings_using_all_cards": [
        {"declaration": [W("H2", "7"), W("H2", "8")], "classification": cls("consecutive_triples", ranks=["7", "8"])},
        {"declaration": [W("H2", "6"), W("H2", "6")], "classification": cls("consecutive_pairs", ranks=["6", "7", "8"])},
        {"declaration": [W("H2", "9"), W("H2", "9")], "classification": cls("consecutive_pairs", ranks=["7", "8", "9"])},
    ], "complete": True},
    "why": "Six cards fit only consecutive pairs or consecutive triples. Triples: only 777888. Pairs containing 77 and 88: 667788 and 778899. R7.8 (D6) makes each a separate action; R7.10 (D6) means no suit variants.",
})

# ---------------------------------------------------------------- PR: profile
CASES.append({
    "id": "PR01", "kind": "profile",
    "description": "base-v1 rejects any option key.",
    "status": D_, "decisions": ["D10"], "rules": ["R8.1"],
    "setup": {"profile": "base-v1", "options": {"allowWrapAround": True}},
    "expected": {"accepted": False, "rejected_reason": "unknown_option"},
    "why": "R8.1 default (D10): base-v1 has no options; any key is unknown and rejected.",
})

# ---------------------------------------------------------------- UN: unresolved (no expected result)
CASES.append({
    "id": "UN01", "kind": "play",
    "description": "Lone wildcard played with no declaration at all (level 9).",
    "status": "unsourced", "decisions": [], "rules": ["R7.8", "R7.9"],
    "setup": {"level": "9", "wildcards_held": ["H9"], "hand": None, "table": None,
              "play": {"cards": ["H9"], "declaration": None}},
    "expected": None,
    "unresolved": "R7.8 says every play with a wildcard carries a declaration; R7.9 says a lone wildcard counts as its natural self and is 'not declared as another rank'. The draft does not say whether the natural-self play needs a declaration, or how one is written. The golden set writes natural self as as_rank = level rank (BS08, BR10, SQ20, WC19, WC21, WC22, WC24, WC25); outside a straight flush that form cannot tell 'itself' from 'standing for a level card of another suit', which gives the same result. That is a format assumption, not a stated rule.",
    "needs": "Extend D6 (owner): state whether a natural-self wildcard is declared, and in what form.",
    "why": "No rule row answers it.",
})
CASES.append({
    "id": "UN02", "kind": "play",
    "description": "Declaration that names a suit outside a straight flush: 666 + heart 2 declared as club 6 (level 2).",
    "status": "unsourced", "decisions": [], "rules": ["R7.10"],
    "setup": {"level": "2", "wildcards_held": ["H2"], "hand": None, "table": None,
              "play": {"cards": ["S6", "D6", "C6", "H2"], "declaration": [W("H2", "6", "C")]}},
    "expected": None,
    "unresolved": "R7.10 says a declaration names a suit only for a straight flush. It does not say whether a declaration carrying an unneeded suit is rejected or the suit ignored.",
    "needs": "Extend D6 (owner): reject, or ignore the suit.",
    "why": "No rule row answers it.",
})


def main():
    repo = Path(sys.argv[1])
    out = repo / GOLDEN
    out.parent.mkdir(parents=True, exist_ok=True)
    ids = [c["id"] for c in CASES]
    assert len(ids) == len(set(ids)), "duplicate ids"
    out.write_text(json.dumps(CASES, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(CASES)} cases to {out}")


if __name__ == "__main__":
    main()
