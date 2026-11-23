#!/usr/bin/env python3
"""Can the app lay the map out by itself, from facts alone?

Usage: python3 layout_sim.py zork1_graph.json [runs]

The player only reports facts, in discovery order: "from room A, going D,
I arrived in room B". The app chooses every position. Several placement
strategies are replayed over many random exploration orders and scored on:

  quality  how many straight two-way passages end up drawn as a straight line
           in the direction travelled with no other room in the way ("clear");
           "aligned" also counts lines that run behind other rooms
  churn    how often, and how much, the existing map moves under the player
  size     grid area used

Strategies
  stretch   never move anything; if the neighbouring cell is taken, put the
            new room further along the same direction
  insert    if the cell is taken, open a new row/column there (everything on
            one side slides over by one; relative arrangement is preserved)
  stretch+rehang  the same branch-sliding, on top of stretch
  rehang    insert, plus: when a move contradicts the drawing, slide the
            branch hanging off the arrival room so the passage lines up, but
            only if that makes more known passages line up than before
"""
import collections
import json
import math
import random
import sys

from replay import VEC, OPP, explore


def aligned(pa, pb, v):
    dx, dy = pb[0] - pa[0], pb[1] - pa[1]
    return dx * v[1] == dy * v[0] and dx * v[0] + dy * v[1] > 0


def clear(pos, taken, a, b, v):
    """Aligned AND no other room sits on the line between the two.
    A line running behind another room reads as a connection to that room,
    so it does not count as drawn correctly."""
    if not aligned(pos[a], pos[b], v):
        return False
    p = (pos[a][0] + v[0], pos[a][1] + v[1])
    while p != pos[b]:
        if p in taken:
            return False
        p = (p[0] + v[0], p[1] + v[1])
    return True


def roughly(pa, pb, v):
    dx, dy = pb[0] - pa[0], pb[1] - pa[1]
    n = math.hypot(dx, dy) * math.hypot(*v)
    return n > 0 and (dx * v[0] + dy * v[1]) / n >= math.cos(math.radians(30)) - 1e-9


class Layout:
    def __init__(self, start, strategy):
        self.s = strategy
        self.pos = {start: (0, 0)}
        self.parent = {start: None}
        self.known = []            # compass passages the player has reported
        self.inserts = 0           # uniform row/column openings
        self.jolts = 0             # events where a branch was moved
        self.jolt_rooms = 0

    def cells(self):
        return {p: r for r, p in self.pos.items()}

    def score(self, pos):
        taken = set(pos.values())
        return sum(1 for a, d, b in self.known if clear(pos, taken, a, b, VEC[d]))

    def free_near(self, p):
        taken = set(self.pos.values())
        for k in range(1, 60):
            ring = [(p[0] + dx, p[1] + dy) for dx in range(-k, k + 1) for dy in range(-k, k + 1)
                    if max(abs(dx), abs(dy)) == k]
            ring = [c for c in ring if c not in taken]
            if ring:
                return min(ring, key=lambda c: (abs(c[0] - p[0]) + abs(c[1] - p[1]), c))

    def open_space(self, want, v):
        self.inserts += 1
        if v[0]:
            for r, (x, y) in self.pos.items():
                if (x >= want[0]) if v[0] > 0 else (x <= want[0]):
                    self.pos[r] = (x + v[0], y)
        else:
            for r, (x, y) in self.pos.items():
                if (y >= want[1]) if v[1] > 0 else (y <= want[1]):
                    self.pos[r] = (x, y + v[1])

    def subtree(self, root):
        kids = collections.defaultdict(list)
        for r, p in self.parent.items():
            kids[p].append(r)
        out, stack = set(), [root]
        while stack:
            r = stack.pop()
            out.add(r)
            stack.extend(kids[r])
        return out

    def try_rehang(self, frm, d, to):
        v = VEC[d]
        best = None
        # either slide the arrival room's branch, or the departure room's
        for anchor, mover, sign in ((frm, to, 1), (to, frm, -1)):
            group = self.subtree(mover)
            if anchor in group:
                continue
            others = {p for r, p in self.pos.items() if r not in group}
            before = self.score(self.pos)
            for k in range(1, 8):
                tx = self.pos[anchor][0] + sign * v[0] * k - self.pos[mover][0]
                ty = self.pos[anchor][1] + sign * v[1] * k - self.pos[mover][1]
                trial = dict(self.pos)
                for r in group:
                    trial[r] = (self.pos[r][0] + tx, self.pos[r][1] + ty)
                if any(trial[r] in others for r in group):
                    continue
                gain = self.score(trial) - before
                if gain > 0 and (best is None or (gain, -len(group)) > best[0]):
                    best = ((gain, -len(group)), trial, group, mover, anchor)
                break
        if best:
            _, trial, group, mover, anchor = best
            self.pos = trial
            self.parent[mover] = anchor
            self.jolts += 1
            self.jolt_rooms += len(group)

    def move(self, frm, d, to, is_new):
        if to == frm:
            return
        v = VEC.get(d)
        if is_new:
            if v is None:
                self.pos[to] = self.free_near(self.pos[frm])
            else:
                want = (self.pos[frm][0] + v[0], self.pos[frm][1] + v[1])
                if want in self.cells():
                    if self.s.startswith("stretch"):
                        taken = set(self.pos.values())
                        while want in taken:
                            want = (want[0] + v[0], want[1] + v[1])
                    else:
                        self.open_space(want, v)
                        want = (self.pos[frm][0] + v[0], self.pos[frm][1] + v[1])
                self.pos[to] = want
            self.parent[to] = frm
        if v is not None:
            self.known.append((frm, d, to))
            if not is_new and self.s.endswith("rehang") and not clear(
                    self.pos, set(self.pos.values()), frm, to, v):
                self.try_rehang(frm, d, to)


def straight_lines(rooms):
    """Two-way compass passages where the return is the opposite direction:
    the only ones a drawing could ever show as a plain straight line."""
    out = []
    for a, info in rooms.items():
        for d, e in info["exits"].items():
            b = e.get("to")
            if d in VEC and b and b != a and a < b and rooms[b]["exits"].get(OPP[d], {}).get("to") == a:
                out.append((a, d, b))
    return out


def main(path, runs):
    g = json.load(open(path))
    rooms = g["rooms"]
    lines = straight_lines(rooms)
    nonmaze = [l for l in lines if not (rooms[l[0]]["maze"] or rooms[l[2]]["maze"])]
    print("straight two-way compass lines in the true map: %d (%d outside the maze)"
          % (len(lines), len(nonmaze)))
    print("%-14s %9s %9s %9s %8s %7s %11s %6s" % (
        "strategy", "clear", "clear", "aligned", "inserts", "jolts", "rooms/jolt", "area"))
    print("%-14s %9s %9s %9s" % ("", "all", "non-maze", "non-maze"))
    for strat in ("stretch", "insert", "rehang", "stretch+rehang"):
        t = collections.Counter()
        for seed in range(runs):
            L = Layout(g["start"], strat)
            for frm, d, to, is_new, _ in explore(rooms, g["start"], random.Random(seed)):
                L.move(frm, d, to, is_new)
            P = L.pos
            taken = set(P.values())
            ok = lambda ls: sum(1 for a, d, b in ls if a in P and b in P and clear(P, taken, a, b, VEC[d]))
            t["all"] += ok(lines)
            t["nm"] += ok(nonmaze)
            t["nmr"] += sum(1 for a, d, b in nonmaze if a in P and b in P and aligned(P[a], P[b], VEC[d]))
            t["ins"] += L.inserts
            t["jolts"] += L.jolts
            t["jr"] += L.jolt_rooms
            xs, ys = [p[0] for p in P.values()], [p[1] for p in P.values()]
            t["area"] += (max(xs) - min(xs) + 1) * (max(ys) - min(ys) + 1)
        n = runs
        print("%-14s %8.0f%% %8.0f%% %8.0f%% %8.1f %7.1f %11.1f %6.0f" % (
            strat, 100 * t["all"] / n / len(lines), 100 * t["nm"] / n / len(nonmaze),
            100 * t["nmr"] / n / len(nonmaze), t["ins"] / n, t["jolts"] / n,
            t["jr"] / max(1, t["jolts"]), t["area"] / n))


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 200)
