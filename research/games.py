#!/usr/bin/env python3
"""Run the final layout (areas + weak hint + tightening) across several games.

Usage: python3 games.py [runs] graph1.json graph2.json ...
"""
import collections
import json
import os
import random
import sys

from islands import Islands
from layout_sim import straight_lines
from replay import explore


def main(runs, paths):
    print("%-12s %6s %8s %9s %7s %6s %7s %7s %9s" % (
        "game", "rooms", "reached", "straight", "clean", "areas", "1-room", "label", "app moves"))
    print("%-12s %6s %8s %9s %7s %6s %7s %7s %9s" % (
        "", "", "", "passages", "", "", "areas", "links", "per game"))
    for path in paths:
        g = json.load(open(path))
        rooms = g["rooms"]
        lines = straight_lines(rooms)
        pairs = {tuple(sorted((r, e["to"]))) for r, i in rooms.items() for e in i["exits"].values()
                 if "to" in e and e["to"] != r}
        t = collections.Counter()
        for seed in range(runs):
            L = Islands(g["start"], rooms, dock=True, tight=True)
            for frm, d, to, is_new, _ in explore(rooms, g["start"], random.Random(seed), jumps=True):
                L.move(frm, d, to, is_new)
            seen = [l for l in lines if l[0] in L.pos and l[2] in L.pos]
            t["reached"] += len(L.pos)
            t["lines"] += len(seen)
            t["clear"] += sum(1 for a, d, b in seen if L.is_clear(a, d, b))
            sizes = collections.Counter(L.isl.values())
            t["areas"] += len(sizes)
            t["one"] += sum(1 for s in sizes.values() if s == 1)
            t["cut"] += sum(1 for a, b in pairs if a in L.isl and b in L.isl and L.isl[a] != L.isl[b])
            t["moves"] += L.merges + L.jolts + L.ejects + L.inserts + L.tightens
        n = runs
        print("%-12s %6d %8.0f %9.0f %6.0f%% %6.1f %7.1f %7.1f %9.1f" % (
            os.path.basename(path).replace("_graph.json", ""), len(rooms), t["reached"] / n,
            t["lines"] / n, 100 * t["clear"] / max(1, t["lines"]), t["areas"] / n, t["one"] / n,
            t["cut"] / n, t["moves"] / n))


if __name__ == "__main__":
    main(int(sys.argv[1]), sys.argv[2:])
