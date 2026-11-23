#!/usr/bin/env python3
"""Render automatically laid-out maps as a step-through HTML page.

Usage: python3 render.py map_view.html

Each playthrough is replayed through the flat layout (areas + weak hint +
tightening) with tangles (mazes and the like) folded into one box, areas held steady on the page,
and bent passages drawn as curves only where they cross nothing.
"""
import collections
import json
import os
import random
import sys

from fold import TangleFold
from overview import Overview, draw_kinds
from replay import explore

RUNS = [("Zork I", "zork1_graph.json", 227, False),
        ("Zork I, another playthrough", "zork1_graph.json", 225, False),
        ("Zork I, a third playthrough", "zork1_graph.json", 65, False),
        ("Zork II", "zork2_graph.json", 1, True),
        ("Enchanter", "enchanter_graph.json", 1, True),
        ("Planetfall", "planetfall_graph.json", 1, True),
        ("The Lurking Horror", "lurkinghorror_graph.json", 1, True)]


def labels(rooms):
    """Number rooms that share a name, the way published maps do."""
    groups = collections.defaultdict(list)
    for r, info in rooms.items():
        groups[info["desc"]].append(r)
    out = {}
    for name, rs in groups.items():
        for i, r in enumerate(rs, 1):
            out[r] = name if len(rs) == 1 else "%s (%d)" % (name, i)
    return out


def record(rooms, start, seed, jumps):
    F, O = TangleFold(start, rooms, dock=True, tight=True), Overview()
    L = F.L
    age, walked, names = {start: 0}, [], {}
    snap = lambda: {r: (L.isl[r],) + tuple(p) for r, p in O.update(L, age, came).items()}
    came = None
    init = prev = snap()
    steps = []
    for frm, d, to, is_new, _ in explore(rooms, start, random.Random(seed), jumps=jumps):
        boxes_before = set(L.pos)
        m = F.move(frm, d, to, is_new)
        step = {"f": F.fold(frm), "d": d if m else "INSIDE", "t": F.fold(to), "n": bool(m and m[3]),
                "c": d != "JUMP" and rooms[frm]["exits"][d]["kind"] == "cond"}
        gone = boxes_before - set(L.pos)
        if gone:                                         # rooms folded into a tangle box
            step["m"] = {g: F.fold(g) for g in gone}
            walked = [(F.fold(a), dd, F.fold(b)) for a, dd, b in walked if F.fold(a) != F.fold(b)]
            step["f"], step["t"] = F.fold(frm), F.fold(to)
        if m:
            age.setdefault(m[2], len(age))
            if d != "JUMP":
                walked.append(m[:3])
        came = F.fold(frm)
        cur = snap()
        step["p"] = {r: p for r, p in cur.items() if prev.get(r) != p}
        step["k"] = sorted("|".join(sorted(k)) for k, kind in draw_kinds(L, walked).items() if kind == "stub")
        steps.append(step)
        prev = cur
        fresh = {b: F.label(b) for b in F.boxes() if names.get(b) != F.label(b)}
        if fresh:
            step["l"] = fresh                            # a tangle box formed or grew
            names.update(fresh)
    return init, steps, names, F


def main(out):
    here = os.path.dirname(os.path.abspath(__file__))
    runs = []
    for name, graph, seed, jumps in RUNS:
        g = json.load(open(os.path.join(here, graph)))
        rooms = g["rooms"]
        init, steps, boxes, F = record(rooms, g["start"], seed, jumps)
        lab = labels(rooms)
        exits = {r: sum(1 for e in i["exits"].values() if "to" in e) for r, i in rooms.items()}
        runs.append({
            "name": name, "start": g["start"], "init": init, "labels": lab,
            "exits": exits, "steps": steps,
            # exits a player would read about on arrival: the ones that need no puzzle
            "open": {r: [d for d, e in i["exits"].items() if e.get("kind") == "to" and e["to"] != r]
                     for r, i in rooms.items()},
        })
    html = open(os.path.join(here, "map_view.template.html")).read()
    open(out, "w").write(html.replace("/*DATA*/", json.dumps({"runs": runs}, separators=(",", ":"))))
    print("wrote", out)


if __name__ == "__main__":
    main(sys.argv[1])
