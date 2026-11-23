#!/usr/bin/env python3
"""Legibility of the map at every stage of exploration, not just at the end.

Usage: python3 stepwise.py [runs] graph1.json graph2.json ...

Replays many playthroughs through the whole flat-map pipeline (areas + weak
hint + tightening, tangles folded automatically, areas held steady, bent
passages drawn as curves only where they cross nothing) and measures the map
after every single move. Results are grouped into ten stages of exploration.

  straight  of the passages that ought to be plain straight lines and have been
            walked so far, the share drawn as one
  here      of the walked passages touching the room you are standing in, the
            share drawn as a plain straight line
  curves    bent passages drawn as a curve
  stubs     passages drawn as a "to Room" label at each end (same area / between areas)
  cross     pairs of drawn lines that cross, per 100 rooms
  to do     unexplored exits shown, and how many of those point straight at an
            unrelated neighbouring room (which reads as a connection)
  assumed   passages shown as two-way whose way back has not been walked or marked
            yet, and how many of those are wrong (really one-way, or the way back
            leads somewhere else)
  moved     share of moves on which rooms you had already seen shifted inside
            their area, and how many rooms shifted when it happened
  pushed    times per playthrough a whole area had to step aside in the overview
"""
import collections
import json
import os
import random
import sys

from fold import TangleFold
from layout_sim import VEC, straight_lines
from overview import Overview, draw_kinds
from replay import OPP, explore

STAGES = 10


def run(rooms, start, seed, jumps, stats, jolts, pushes):
    F, O = TangleFold(start, rooms, dock=True, tight=True), Overview()
    L = F.L
    truth = straight_lines(rooms)
    opens = {r: [d for d, e in i["exits"].items() if e.get("kind") == "to" and e["to"] != r]
             for r, i in rooms.items()}
    moves = list(explore(rooms, start, random.Random(seed), jumps=jumps))
    walked, done, age = [], set(), {start: 0}
    real = {r: {d: e["to"] for d, e in i["exits"].items() if "to" in e} for r, i in rooms.items()}
    for i, (frm, d, to, is_new, _) in enumerate(moves):
        before_pos, before_isl = dict(L.pos), dict(L.isl)
        m = F.move(frm, d, to, is_new)
        if d != "JUMP":
            done.add((frm, d))
        walked = [(F.fold(a), dd, F.fold(b)) for a, dd, b in walked if F.fold(a) != F.fold(b)]
        if m:
            age.setdefault(m[2], len(age))
            if d != "JUMP":
                walked.append(m[:3])
        O.update(L, age, F.fold(frm))
        kinds = draw_kinds(L, walked)
        s = stats[min(STAGES - 1, i * STAGES // len(moves))]
        s["n"] += 1

        shifted, by_area = 0, collections.defaultdict(list)
        for r in before_pos:
            if r in L.pos:
                by_area[L.isl[r]].append(r)
        for rs in by_area.values():
            delta = collections.Counter(
                (L.pos[r][0] - before_pos[r][0], L.pos[r][1] - before_pos[r][1], before_isl[r]) for r in rs)
            shifted += len(rs) - delta.most_common(1)[0][1]
        if shifted:
            s["jolt"] += 1
            s["jolt_rooms"] += shifted
            jolts.append(shifted)

        for a, dd, b in truth:
            fa, fb = F.fold(a), F.fold(b)
            if fa == fb or fa not in L.pos or fb not in L.pos:
                continue
            if (a, dd) in done or (b, OPP[dd]) in done:
                s["should"] += 1
                s["straight"] += kinds.get(frozenset((fa, fb))) == "straight"
        here = F.fold(to)
        mine = [k for k in kinds if here in k]
        s["here_all"] += len(mine)
        s["here_ok"] += sum(1 for k in mine if kinds[k] == "straight")
        c = collections.Counter(kinds.values())
        s["curves"] += c["curve"]
        s["stubs"] += c["stub"]
        s["labels"] += c["label"]

        sizes = collections.Counter(L.isl.values())
        s["areas"] += len(sizes)
        s["rooms"] += len(L.pos)
        cells = {(L.isl[r], p): r for r, p in L.pos.items()}
        # two-way is assumed for every walked passage; the simulated player marks one-way
        # once every exit of the far room has been tried and none came back
        assumed = set()
        for a, dd in done:
            b = real[a][dd]
            if a == b or dd not in OPP:
                continue
            if (b, OPP[dd]) in done:
                continue                                # the way back has been walked: no longer a guess
            if all((b, x) in done for x in real[b]):
                continue                                # every exit of the far room has been tried, so the
                                                        # guess is settled: marked one-way, or bent, by now
            assumed.add((b, OPP[dd]))
            s["assumed"] += 1
            s["wrong"] += real[b].get(OPP[dd]) != a
        for r in L.pos:
            if r in F.boxes():
                continue
            for dd in opens.get(r, []):
                if (r, dd) in done or (r, dd) in assumed:
                    continue
                s["todo"] += 1
                if dd in VEC:
                    nb = cells.get((L.isl[r], (L.pos[r][0] + VEC[dd][0], L.pos[r][1] + VEC[dd][1])))
                    if nb is not None:
                        s["mislead"] += 1
        if i % 10 == 0:
            segs = collections.defaultdict(list)
            for k, kind in kinds.items():
                if kind in ("straight", "curve"):
                    a, b = tuple(k)
                    segs[L.isl[a]].append((L.pos[a], L.pos[b]))
            n = 0
            for ss in segs.values():
                for x, (p, q) in enumerate(ss):
                    for r2, s2 in ss[x + 1:]:
                        if len({p, q, r2, s2}) < 4:
                            continue
                        o = lambda u, v, w: (v[0] - u[0]) * (w[1] - u[1]) - (v[1] - u[1]) * (w[0] - u[0])
                        if o(p, q, r2) * o(p, q, s2) < 0 and o(r2, s2, p) * o(r2, s2, q) < 0:
                            n += 1
            s["cross"] += n
            s["cross_rooms"] += len(L.pos)
    pushes.append(O.pushed)


def main(runs, paths):
    for path in paths:
        g = json.load(open(path))
        rooms = g["rooms"]
        name = os.path.basename(path).replace("_graph.json", "")
        stats = [collections.Counter() for _ in range(STAGES)]
        jolts, pushes = [], []
        for seed in range(runs):
            run(rooms, g["start"], seed, name != "zork1", stats, jolts, pushes)
        print("\n%s  (%d playthroughs)" % (name, runs))
        print("%-9s %5s %9s %5s %7s %11s %6s %6s %11s %11s %12s" % (
            "explored", "rooms", "straight", "here", "curves", "stubs", "cross", "areas", "to do", "assumed", "moved"))
        for k, s in enumerate(stats):
            n = max(1, s["n"])
            print("%3d-%3d%%  %5.0f %8.0f%% %4.0f%% %7.1f %5.1f/%-5.1f %6.1f %6.1f %5.1f (%3.1f) %5.1f (%3.1f) %5.0f%% x %.1f" % (
                k * 10, k * 10 + 10, s["rooms"] / n,
                100 * s["straight"] / max(1, s["should"]), 100 * s["here_ok"] / max(1, s["here_all"]),
                s["curves"] / n, s["stubs"] / n, s["labels"] / n,
                100 * s["cross"] / max(1, s["cross_rooms"]), s["areas"] / n,
                s["todo"] / n, s["mislead"] / n, s["assumed"] / n, s["wrong"] / n,
                100 * s["jolt"] / n, s["jolt_rooms"] / max(1, s["jolt"])))
        jolts.sort()
        if jolts:
            print("moves that shifted seen rooms: %.1f per playthrough; rooms shifted: median %d, "
                  "9 in 10 are %d or fewer, worst %d" % (
                      len(jolts) / runs, jolts[len(jolts) // 2], jolts[int(len(jolts) * 0.9)], jolts[-1]))
        print("whole areas pushed aside in the overview: %.1f per playthrough" % (sum(pushes) / runs))


if __name__ == "__main__":
    main(int(sys.argv[1]), sys.argv[2:])
