"""Independent audit of core/src/test/resources/fixtures/rules/golden-v1.json against rules/base-v1.md.

Reads only: the golden JSON, base-v1.md, and rules/sources/text/*.txt.
The evaluator below is written from base-v1.md (with its section-9 defaults); it does not import build.py.
Usage: python3 -I tools/golden/audit.py <repo-root>   (writes rules/golden/audit-rows.md, prints a summary)
"""
import itertools
import json
import re
import sys
import unicodedata
from collections import Counter
from pathlib import Path

repo = Path(sys.argv[1])
spec = (repo / "rules/base-v1.md").read_text(encoding="utf-8")
cases = json.loads((repo / "core/src/test/resources/fixtures/rules/golden-v1.json").read_text(encoding="utf-8"))
textdir = repo / "rules/sources/text"

# ------------------------------------------------------------------ parse the spec
rules = {}  # id -> (row text, tag)
for line in spec.splitlines():
    m = re.match(r"\| (R\d+\.\d+) \|(.*)\|\s*(\[[^\]]*\])\s*\|\s*$", line)
    if m:
        rules[m.group(1)] = (line, m.group(3))
evidence = {}  # E -> (key, line, quote)
for line in spec.splitlines():
    m = re.match(r"\| (E\d+) \| (\w+) \| (\d+) \| (.*?) \| .* \|\s*$", line)
    if m:
        evidence[m.group(1)] = (m.group(2), int(m.group(3)), m.group(4))
decisions_defined = set(re.findall(r"^\| (D\d+) \|", spec, flags=re.M))


def tag_decisions(tag):
    return set(re.findall(r"\bD\d+\b", tag))


def tag_evidence(tag):
    ids = set()
    for a, b in re.findall(r"E(\d+)[–-]E(\d+)", tag):
        ids |= {f"E{i:02d}" for i in range(int(a), int(b) + 1)}
    ids |= set(re.findall(r"\bE\d+\b", tag))
    return ids


def norm(s):
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", s))


quote_cache = {}


def quote_ok(e):
    if e in quote_cache:
        return quote_cache[e]
    key, ln, q = evidence[e]
    lines = (textdir / f"{key}.txt").read_text(encoding="utf-8").split("\n")
    window = norm("".join(lines[ln - 1:ln + 2]))
    ok = norm(q) in window and window.find(norm(q)) < len(norm(lines[ln - 1])) + 1
    quote_cache[e] = (ok, f"{key}:{ln}")
    return quote_cache[e]


REPOST = {"HA17", "CG22a", "CG22b"}
AUTHORITATIVE = {"JS17", "WG25", "PD17"}

# ------------------------------------------------------------------ evaluator (from base-v1 text + defaults)
NAT = {str(i): i for i in range(2, 11)} | {"J": 11, "Q": 12, "K": 13, "A": 14}
JOKERS = {"LJ", "BJ"}
CARD_RE = re.compile(r"^(?:(LJ|BJ)|([SHCD])(10|[2-9JQKA]))$")


def parse(card):
    m = CARD_RE.match(card)
    if not m:
        raise ValueError(card)
    return (m.group(1), None) if m.group(1) else (m.group(3), m.group(2))


def power(rank, level):  # R1.5
    if rank == "BJ":
        return 17
    if rank == "LJ":
        return 16
    if rank == level:
        return 15
    return NAT[rank]


def effective(cards, decl, level):
    """Return (effective [(rank,suit)], reject_reason)."""
    wild = "H" + level
    nw = cards.count(wild)
    if nw == 0:
        return [parse(c) for c in cards], None
    if decl is None:
        return None, "missing_declaration"  # R7.8 (D6)
    if len(decl) != nw or any(d["card"] != wild for d in decl):
        return None, "declaration_mismatch"
    if any(d["as_rank"] in JOKERS for d in decl):
        return None, "wildcard_as_joker"  # R7.3
    if nw == len(cards) and any(d["as_rank"] != level for d in decl):
        return None, "lone_wildcard_redeclared"  # R7.9 (D7)
    eff = [parse(c) for c in cards if c != wild]
    eff += [(d["as_rank"], d.get("as_suit")) for d in decl]
    return eff, None


def seq_mappings(ranks):
    """Distinct non-joker ranks -> list of (values low..high, tokens) consecutive runs; A high or A low, no wrap (D1)."""
    out = []
    if any(r in JOKERS for r in ranks):
        return out  # R2.11 (D4)
    for alow in (False, True):
        if alow and "A" not in ranks:
            continue
        vals = sorted(((1 if (alow and r == "A") else NAT[r]), r) for r in ranks)
        v = [x[0] for x in vals]
        if len(set(v)) == len(v) and v == list(range(v[0], v[0] + len(v))):
            out.append(([x[0] for x in vals], [x[1] for x in vals]))
    return out


def classify(eff, level):
    n = len(eff)
    ranks = [r for r, _ in eff]
    cnt = Counter(ranks)
    res = []
    jok = sum(1 for r in ranks if r in JOKERS)
    if n == 1:
        res.append({"type": "single", "rank": ranks[0]})
    if n == 2 and len(cnt) == 1:
        res.append({"type": "pair", "rank": ranks[0]})  # R2.2 (incl. joker pairs); LJ+BJ not a pair (R2.3)
    if n == 3 and len(cnt) == 1 and jok == 0:
        res.append({"type": "triple", "rank": ranks[0]})
    if n >= 4 and len(cnt) == 1 and jok == 0 and n <= 10:
        res.append({"type": "bomb", "rank": ranks[0], "size": n})  # R4.1, R4.5/R4.4 (D2)
    if n == 4 and sorted(ranks) == ["BJ", "BJ", "LJ", "LJ"]:
        res.append({"type": "four_jokers"})  # R6.1
    if n == 5:
        sc = sorted(cnt.items(), key=lambda x: -x[1])
        if len(sc) == 2 and sc[0][1] == 3 and sc[1][1] == 2 and sc[0][0] not in JOKERS:
            res.append({"type": "triple_with_pair", "rank": sc[0][0]})  # R2.5, R2.7 (D3); 777+77 excluded (D8)
        if len(cnt) == 5:
            for vals, toks in seq_mappings(ranks):
                suits = [s for _, s in eff]
                if None not in suits and len(set(suits)) == 1:
                    res.append({"type": "straight_flush", "ranks": toks, "suit": suits[0], "high": vals[-1]})  # R5.1, R5.5 (D9)
                else:
                    res.append({"type": "straight", "ranks": toks, "high": vals[-1]})  # R2.8, R7.11 (D9)
    if n == 6:
        if len(cnt) == 3 and set(cnt.values()) == {2}:
            for vals, toks in seq_mappings(list(cnt)):
                res.append({"type": "consecutive_pairs", "ranks": toks, "high": vals[-1]})
        if len(cnt) == 2 and set(cnt.values()) == {3}:
            for vals, toks in seq_mappings(list(cnt)):
                res.append({"type": "consecutive_triples", "ranks": toks, "high": vals[-1]})
    return res


def key(r, level):
    t = r["type"]
    if t == "four_jokers":
        return (6,)
    if t == "bomb" and r["size"] >= 6:
        return (5, r["size"], power(r["rank"], level))
    if t == "straight_flush":
        return (4, r["high"])
    if t == "bomb" and r["size"] == 5:
        return (3, power(r["rank"], level))
    if t == "bomb":
        return (2, power(r["rank"], level))
    return (0,)


def beats(p, t, level):  # R2.13-R2.16, R4.7-R4.9, R5.3-R5.4, R6.2
    kp, kt = key(p, level), key(t, level)
    if kp[0] == 0 and kt[0] == 0:
        if p["type"] != t["type"]:
            return False
        if "high" in p:
            return p["high"] > t["high"]
        return power(p["rank"], level) > power(t["rank"], level)
    if kp[0] != kt[0]:
        return kp[0] > kt[0]
    return kp > kt


def public(r):
    return {k: v for k, v in r.items() if k not in ("high", "size")}


def evaluate(cards, decl, level):
    eff, rej = effective(cards, decl, level)
    if rej:
        return [], rej
    rs = classify(eff, level)
    if not rs:
        return [], ("declared_reading_illegal" if ("H" + level) in cards else "no_combination")
    return rs, None


# ------------------------------------------------------------------ per-case audit
rows, fails = [], []
d11_flags = {}


def check(c):
    probs, notes = [], []
    cid = c["id"]
    # 1. rule rows exist, re-read tags, recompute decisions
    comp_dec, sens = set(), []
    for r in c["rules"]:
        if r not in rules:
            probs.append(f"rule {r} not in base-v1.md")
            continue
        tag = rules[r][1]
        comp_dec |= tag_decisions(tag)
        evs = tag_evidence(tag)
        for e in sorted(evs):
            if e not in evidence:
                probs.append(f"{r} cites {e}, missing from Appendix A")
                continue
            ok, where = quote_ok(e)
            if not ok:
                probs.append(f"{e} quote not found at {where}")
        keys = {evidence[e][0] for e in evs if e in evidence}
        if evs and not (keys & AUTHORITATIVE) and (keys & REPOST):
            sens.append(r)
    d11_flags[cid] = sens
    for d in comp_dec:
        if d not in decisions_defined:
            probs.append(f"{d} not defined in section 9")
    if c["status"] != "unsourced":
        if set(c["decisions"]) != comp_dec:
            probs.append(f"decisions {sorted(c['decisions'])} != from cited tags {sorted(comp_dec)}")
        want = "default-dependent" if comp_dec else "sourced"
        if c["status"] != want:
            probs.append(f"status {c['status']} should be {want}")
        for d in c["decisions"]:
            if d not in c["why"]:
                probs.append(f"why does not name {d}")
    else:
        if c.get("expected") is not None or not c.get("unresolved") or not c.get("needs"):
            probs.append("unsourced case must have expected=null, unresolved and needs")
    # 2. every rule cited in 'why' is in rules
    for r in set(re.findall(r"\bR\d+\.\d+\b", c["why"])):
        if r not in c["rules"]:
            probs.append(f"why cites {r} but rules does not list it")
    # 3. deck feasibility and card syntax
    st = c["setup"]
    if c["kind"] in ("play", "generator"):
        level = st["level"]
        allc = list(st.get("hand") or [])
        if st.get("play"):
            allc += st["play"]["cards"]
        if st.get("table"):
            allc += st["table"]["cards"]
        try:
            [parse(x) for x in allc]
        except ValueError as ex:
            probs.append(f"bad card {ex}")
            return probs, notes
        over = [k for k, v in Counter(allc).items() if v > 2]
        if over:
            probs.append(f"more than 2 copies of {over} in one deal")
        held = [x for x in allc if x == "H" + level]
        if sorted(st["wildcards_held"]) != sorted(held):
            probs.append(f"wildcards_held {st['wildcards_held']} != {held}")
    # 4. independent evaluation
    exp = c.get("expected")
    if c["status"] == "unsourced":
        notes.append("no expected result (unresolved)")
        return probs, notes
    if c["kind"] == "play":
        level = st["level"]
        p = st["play"]
        rs, rej = evaluate(p["cards"], p["declaration"], level)
        trs = None
        if st["table"]:
            trs, trej = evaluate(st["table"]["cards"], st["table"]["declaration"], level)
            if st["table"]["classification"] not in [public(x) for x in trs]:
                probs.append(f"table classification not a valid reading ({[public(x) for x in trs]})")
        valid = bool(rs)
        if exp["classification"] is None:
            if valid:
                probs.append(f"expected no valid reading, evaluator found {[public(x) for x in rs]}")
            if exp["rejected_reason"] != rej:
                probs.append(f"rejected_reason {exp['rejected_reason']} != evaluator {rej}")
            if exp["legal"]:
                probs.append("legal=true with no classification")
            if exp["beats_table"] is not None:
                probs.append("beats_table must be null when there is no valid reading")
        else:
            match = [x for x in rs if public(x) == exp["classification"]]
            if not match:
                probs.append(f"classification {exp['classification']} not among {[public(x) for x in rs]} (reject={rej})")
            else:
                m = match[0]
                if exp["exclusive"] and len(rs) != 1:
                    probs.append(f"exclusive but evaluator has {len(rs)} readings")
                bs = m.get("size") if m["type"] == "bomb" else None
                if exp["bomb_size"] != bs:
                    probs.append(f"bomb_size {exp['bomb_size']} != {bs}")
                if exp["straight_flush"] != (m["type"] == "straight_flush"):
                    probs.append("straight_flush flag wrong")
                if st["table"]:
                    tcl = [x for x in trs if public(x) == st["table"]["classification"]]
                    if tcl:
                        b = beats(m, tcl[0], level)
                        anyb = any(beats(x, tcl[0], level) for x in rs)
                        if exp["beats_table"] != b:
                            probs.append(f"beats_table {exp['beats_table']} != evaluator {b}")
                        if exp["beats_table"] is False and anyb:
                            probs.append("another reading of the same play would beat the table")
                        if exp["legal"] != b:
                            probs.append("legal must equal beats_table when following")
                else:
                    if exp["beats_table"] is not None:
                        probs.append("beats_table must be null on a lead")
                    if not exp["legal"]:
                        probs.append("valid lead marked illegal")
            if exp["rejected_reason"] is not None:
                probs.append("rejected_reason set on a valid reading")
        notes.append("evaluator agrees" if not probs else "")
    elif c["kind"] == "generator":
        level, hand = st["level"], st["hand"]
        wild = "H" + level
        if c["id"] == "GE01":
            phys = [x for x in hand if x != wild]
            nw = hand.count(wild)
            sizes, comps = set(), set()
            for k in range(0, len(phys) + 1):
                for w in range(0, nw + 1):
                    cards = phys[:k] + [wild] * w
                    if not cards:
                        continue
                    rs, _ = evaluate(cards, [{"card": wild, "as_rank": exp["bomb_rank"]}] * w, level)
                    if any(x["type"] == "bomb" for x in rs):
                        sizes.add(k + w)
                        comps.add((k, w, k + w))
            if sorted(sizes) != exp["bomb_sizes_offered"]:
                probs.append(f"sizes {sorted(sizes)} != {exp['bomb_sizes_offered']}")
            for m in exp["must_include"]:
                if (m["physical"], m["wildcards"], m["size"]) not in comps:
                    probs.append(f"composition {m} not legal under evaluator")
        elif c["id"] == "GE02":
            rs, _ = evaluate(hand, None, level)
            if exp["must_include_classification"] not in [public(x) for x in rs]:
                probs.append("four jokers not found")
        elif c["id"] == "GE03":
            found = []
            toks = list(NAT)
            for a, b in itertools.combinations_with_replacement(toks, 2):
                decl = [{"card": wild, "as_rank": a}, {"card": wild, "as_rank": b}]
                rs, _ = evaluate(hand, decl, level)
                for x in rs:
                    found.append(public(x))
            uniq = []
            for f in found:
                if f not in uniq:
                    uniq.append(f)
            want = [r["classification"] for r in exp["readings_using_all_cards"]]
            if sorted(map(json.dumps, uniq)) != sorted(map(json.dumps, want)):
                probs.append(f"readings {uniq} != {want}")
            for r in exp["readings_using_all_cards"]:
                rs, _ = evaluate(hand, r["declaration"], level)
                if r["classification"] not in [public(x) for x in rs]:
                    probs.append(f"declaration {r['declaration']} does not give {r['classification']}")
        notes.append("evaluator agrees" if not probs else "")
    elif c["kind"] == "profile":
        if "rejected" not in rules["R8.1"][0]:
            probs.append("R8.1 does not say options are rejected")
        notes.append("R8.1 text says unknown option keys are rejected")
    return probs, notes


for c in cases:
    probs, notes = check(c)
    rows.append((c, probs, notes))
    if probs:
        fails.append((c["id"], probs))

# ------------------------------------------------------------------ cross-case consistency
cons = []
seen = {}
for c in cases:
    if c["kind"] != "play" or c["status"] == "unsourced":
        continue
    st = c["setup"]
    k = json.dumps([st["level"], sorted(st["play"]["cards"]), st["play"]["declaration"],
                    sorted(st["table"]["cards"]) if st["table"] else None,
                    st["table"]["classification"] if st["table"] else None], sort_keys=True)
    if k in seen and json.dumps(seen[k][1]) != json.dumps(c["expected"]):
        cons.append(f"{seen[k][0]} and {c['id']}: same setup, different expected")
    seen.setdefault(k, (c["id"], c["expected"]))
rel = {}
for c in cases:
    if c["kind"] != "play" or c["status"] == "unsourced" or not c["setup"]["table"]:
        continue
    e = c["expected"]
    if e["classification"] is None:
        continue
    lv = c["setup"]["level"]
    a = json.dumps([lv, e["classification"], e["bomb_size"]])
    t = c["setup"]["table"]["classification"]
    tsz = len(c["setup"]["table"]["cards"]) if t["type"] == "bomb" else None
    b = json.dumps([lv, t, tsz])
    pair = (a, b)
    if pair in rel and rel[pair][1] != e["beats_table"]:
        cons.append(f"{rel[pair][0]} and {c['id']}: same matchup, different result")
    rel.setdefault(pair, (c["id"], e["beats_table"]))
for (a, b), (cid, res) in rel.items():
    if res and (b, a) in rel and rel[(b, a)][1]:
        cons.append(f"{cid} and {rel[(b, a)][0]}: each beats the other")
# readings declared legal elsewhere must not be called illegal for the same cards+declaration
for c in cases:
    if c["status"] == "default-dependent" and not c["decisions"]:
        cons.append(f"{c['id']}: default-dependent without a decision")

# ------------------------------------------------------------------ output
out = ["| Case | Status | Decisions | Rules re-read | Result | Reason |", "|---|---|---|---|---|---|"]
for c, probs, notes in rows:
    rr = ", ".join(c["rules"])
    if probs:
        res, why = "FAIL", "; ".join(probs)
    else:
        res = "PASS"
        srcs = sorted({quote_ok(e)[1] for r in c["rules"] if r in rules for e in tag_evidence(rules[r][1]) if e in evidence})
        why = f"{notes[0] if notes else ''}; quotes found at {', '.join(srcs) if srcs else 'n/a (PRODUCT/UNSOURCED rows)'}"
    dec = ", ".join(c["decisions"]) or "—"
    out.append(f"| {c['id']} | {c['status']} | {dec} | {rr} | {res} | {why} |")
(repo / "rules/golden/audit-rows.md").write_text("\n".join(out) + "\n", encoding="utf-8")

st = Counter(c["status"] for c in cases)
print("cases", len(cases), dict(st))
print("FAIL", len(fails))
for cid, p in fails:
    print(" ", cid, "|", "; ".join(p))
print("consistency problems", len(cons))
for x in cons:
    print(" ", x)
print("D11-sensitive cases (a cited rule rests only on repost evidence):",
      sorted(k for k, v in d11_flags.items() if v))
print("rules by D11:", sorted({r for v in d11_flags.values() for r in v}))
print("quotes checked", len(quote_cache), "bad", [e for e, (ok, _) in quote_cache.items() if not ok])
print("decision counts", Counter(d for c in cases for d in c["decisions"]))

# Fail loud: any failed case, consistency problem or bad quote is a non-zero exit.
bad_quotes = [e for e, (ok, _) in quote_cache.items() if not ok]
if fails or cons or bad_quotes:
    sys.exit(1)
