#!/usr/bin/env python3
"""Floating-areas layout: the app positions everything, from facts alone.

Usage: python3 islands.py zork1_graph.json [runs]

Rooms live in areas. Each area has its own grid; areas are never drawn on top
of each other, and a passage between two areas is shown as a "(to Room)" label
at each end, the way published maps link their separate sheets.

  - A compass move to a new room places it next to where you came from, in the
    same area (opening a row/column inside that area if the cell is taken).
  - An up/down/in/out move to a new room starts a new area: such a move says
    nothing about where the room belongs on the page.
  - With maze_sheet on, stepping between a room the player has flagged as maze
    and a normal room is treated the same way, so the maze gets its own area.
  - A compass move that arrives in a known room of ANOTHER area joins the two
    areas, if the smaller one can be set down so the passage is a clear
    straight line, nothing overlaps and no existing straight line gets blocked.
    Otherwise they stay separate and the passage is a pair of labels.
  - A compass move that contradicts the drawing inside one area slides the
    small branch hanging off one end, if that makes more passages line up.

With dock on, up/down/in/out is a weak hint instead of no hint: the new room
is set beside the room it hangs off (above for up, below for down) when a cell
is free there. It, and whatever grows from it, stays "docked" only while that
causes no trouble. It is released into its own area as soon as it would
collide with something, block a straight line, grow past DOCK_CAP rooms, or a
compass passage says it belongs somewhere else.

With tight on, stretched straight passages are shortened after each move by
sliding whatever hangs off their far end one cell closer, when that causes no
overlap and loses no straight passage.
"""
import collections
import json
import random
import sys

from layout_sim import VEC, clear, straight_lines
from replay import explore


N, S, E, W = (0, -1), (0, 1), (1, 0), (-1, 0)
DOCK_CELLS = {"UP": [N, (1, -1), (-1, -1), E, W], "DOWN": [S, (1, 1), (-1, 1), E, W]}
DOCK_ANY = [E, W, S, N, (1, 1), (-1, 1), (1, -1), (-1, -1)]
DOCK_CAP = 6


class Islands:
    def __init__(self, start, rooms, maze_sheet=True, max_gap=3, dock=False, tight=False):
        self.tight = tight
        self.tethers = []               # up/down/in/out links, for keeping drawn order
        self.tightens = self.tight_rooms = 0
        self.rooms, self.maze_sheet, self.max_gap, self.dock = rooms, maze_sheet, max_gap, dock
        self.docked = set()             # rooms set down by a weak hint (roots of docked groups)
        self.ejects = 0
        self.pos = {start: (0, 0)}      # position inside the room's own area
        self.isl = {start: 0}
        self.parent = {start: None}
        self.known = []                 # compass passages that constrain layout
        self.next_id = 1
        self.merges = self.merge_rooms = self.jolts = self.jolt_rooms = self.inserts = 0

    # -- helpers -----------------------------------------------------------
    def members(self, i):
        return [r for r, k in self.isl.items() if k == i]

    def soft(self, frm, d, to):
        """Moves that carry no information about position on the page."""
        if d not in VEC:
            return True
        return self.maze_sheet and self.rooms[frm]["maze"] != self.rooms[to]["maze"]

    def score(self, pos, isl):
        taken = collections.defaultdict(set)
        for r, p in pos.items():
            taken[isl[r]].add(p)
        return sum(1 for a, d, b in self.known
                   if isl[a] == isl[b] and clear(pos, taken[isl[a]], a, b, VEC[d]))

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

    # -- docking -----------------------------------------------------------
    def dock_root(self, r):
        while r is not None:
            if r in self.docked:
                return r
            r = self.parent[r]
        return None

    def dock_group(self, root):
        return self.subtree(root) & set(self.members(self.isl[root]))

    def try_dock(self, frm, d, to):
        i = self.isl[frm]
        taken = {self.pos[r] for r in self.members(i)}
        before = self.score(self.pos, self.isl)
        for v in DOCK_CELLS.get(d, DOCK_ANY):
            c = (self.pos[frm][0] + v[0], self.pos[frm][1] + v[1])
            if c in taken:
                continue
            self.pos[to], self.isl[to] = c, i
            if self.score(self.pos, self.isl) < before:     # it would sit on someone's line
                del self.pos[to], self.isl[to]
                continue
            self.parent[to] = frm
            self.docked.add(to)
            return True
        return False

    def eject(self, root):
        """Release a docked group into its own area, keeping its shape."""
        for r in self.dock_group(root):
            self.isl[r] = self.next_id
        self.next_id += 1
        self.parent[root] = None
        self.docked.discard(root)
        self.ejects += 1

    # -- operations --------------------------------------------------------
    def open_space(self, i, want, v):
        self.inserts += 1
        for r in self.members(i):
            x, y = self.pos[r]
            if v[0]:
                if (x >= want[0]) if v[0] > 0 else (x <= want[0]):
                    self.pos[r] = (x + v[0], y)
            elif (y >= want[1]) if v[1] > 0 else (y <= want[1]):
                self.pos[r] = (x, y + v[1])

    def try_merge(self, frm, d, to):
        v = VEC[d]
        a, b = self.isl[frm], self.isl[to]
        ma, mb = self.members(a), self.members(b)
        before = self.score(self.pos, self.isl)
        # the smaller area is the one that gets picked up and set down
        if len(mb) <= len(ma):
            group, anchor, mover, sign, into = mb, frm, to, 1, a
        else:
            group, anchor, mover, sign, into = ma, to, frm, -1, b
        others = {self.pos[r] for r in self.members(into)}
        for k in range(1, self.max_gap + 1):
            tx = self.pos[anchor][0] + sign * v[0] * k - self.pos[mover][0]
            ty = self.pos[anchor][1] + sign * v[1] * k - self.pos[mover][1]
            trial, tisl = dict(self.pos), dict(self.isl)
            for r in group:
                trial[r] = (self.pos[r][0] + tx, self.pos[r][1] + ty)
                tisl[r] = into
            if any(trial[r] in others for r in group):
                continue
            if self.score(trial, tisl) > before:      # the new link is clear, nothing else broke
                self.pos, self.isl = trial, tisl
                self.merges += 1
                self.merge_rooms += len(group)
                return

    def try_rehang(self, frm, d, to):
        v, i = VEC[d], self.isl[frm]
        before, best = self.score(self.pos, self.isl), None
        for anchor, mover, sign in ((frm, to, 1), (to, frm, -1)):
            group = self.subtree(mover) & set(self.members(i))
            if anchor in group:
                continue
            others = {self.pos[r] for r in self.members(i) if r not in group}
            for k in range(1, 8):
                tx = self.pos[anchor][0] + sign * v[0] * k - self.pos[mover][0]
                ty = self.pos[anchor][1] + sign * v[1] * k - self.pos[mover][1]
                trial = dict(self.pos)
                for r in group:
                    trial[r] = (self.pos[r][0] + tx, self.pos[r][1] + ty)
                if any(trial[r] in others for r in group):
                    continue
                gain = self.score(trial, self.isl) - before
                if gain > 0 and (best is None or (gain, -len(group)) > best[0]):
                    best = ((gain, -len(group)), trial, group, mover, anchor)
                break
        if best:
            _, self.pos, group, mover, anchor = best
            self.parent[mover] = anchor
            self.jolts += 1
            self.jolt_rooms += len(group)

    # -- tightening --------------------------------------------------------
    def tighten(self, i):
        """Shorten stretched straight passages in area i, one step at a time.

        For a straight passage longer than one cell, everything rigidly attached
        to its far end (through other straight passages and up/down tethers) is
        slid one cell toward the near end. It is applied only if nothing
        overlaps and no straight passage is lost. Nothing is ever stretched.
        """
        for _ in range(40):
            mem = set(self.members(i))
            if len(mem) < 3:
                return
            taken = {self.pos[r] for r in mem}
            links = [(a, b, VEC[d]) for a, d, b in self.known
                     if a in mem and b in mem and clear(self.pos, taken, a, b, VEC[d])]
            rigid = collections.defaultdict(set)
            for a, b, _ in links:
                rigid[a].add(b)
                rigid[b].add(a)
            for a, b in self.tethers:
                if a in mem and b in mem:
                    rigid[a].add(b)
                    rigid[b].add(a)
            before = self.score(self.pos, self.isl)
            done = False
            for a, b, v in links:
                if max(abs(self.pos[b][0] - self.pos[a][0]), abs(self.pos[b][1] - self.pos[a][1])) < 2:
                    continue
                group, stack = {b}, [b]                 # what must travel with b
                while stack:
                    r = stack.pop()
                    for q in rigid[r]:
                        if q not in group and {r, q} != {a, b}:
                            group.add(q)
                            stack.append(q)
                if a in group:
                    continue                            # held open by a loop of passages
                trial = dict(self.pos)
                for r in group:
                    trial[r] = (self.pos[r][0] - v[0], self.pos[r][1] - v[1])
                rest = {self.pos[r] for r in mem - group}
                if any(trial[r] in rest for r in group):
                    continue
                if self.score(trial, self.isl) < before:
                    continue
                self.pos = trial
                self.tightens += 1
                self.tight_rooms += min(len(group), len(mem) - len(group))
                done = True
                break
            if not done:
                return

    def move(self, frm, d, to, is_new):
        if to == frm:
            return
        if d == "JUMP":                 # arrived by means no exit describes: a fresh area
            self.isl[to], self.pos[to], self.parent[to] = self.next_id, (0, 0), None
            self.next_id += 1
            return
        if d not in VEC:
            self.tethers.append((frm, to))
        self._move(frm, d, to, is_new)
        if self.tight:
            for i in {self.isl[frm], self.isl[to]}:
                self.tighten(i)

    def _move(self, frm, d, to, is_new):
        soft = self.soft(frm, d, to)
        taken = lambda i: {self.pos[r] for r in self.members(i)}
        if is_new:
            if soft:
                if self.dock and d not in VEC and self.try_dock(frm, d, to):
                    return
                self.isl[to], self.pos[to], self.parent[to] = self.next_id, (0, 0), None
                self.next_id += 1
                return
            v = VEC[d]
            want = (self.pos[frm][0] + v[0], self.pos[frm][1] + v[1])
            if want in taken(self.isl[frm]):
                root = self.dock_root(frm) if self.dock else None
                if root is not None:
                    self.eject(root)            # no room to grow here: go and be your own area
                if want in taken(self.isl[frm]):
                    self.open_space(self.isl[frm], want, v)
                    want = (self.pos[frm][0] + v[0], self.pos[frm][1] + v[1])
            before = self.score(self.pos, self.isl)
            self.isl[to], self.pos[to], self.parent[to] = self.isl[frm], want, frm
            self.known.append((frm, d, to))
            root = self.dock_root(to) if self.dock else None
            if root is not None and (len(self.dock_group(root)) > DOCK_CAP
                                     or self.score(self.pos, self.isl) < before + 1):
                self.eject(root)
            return
        if soft:
            return
        self.known.append((frm, d, to))
        rf = self.dock_root(frm) if self.dock else None
        rt = self.dock_root(to) if self.dock else None
        if self.isl[frm] != self.isl[to]:
            for r in {rf, rt} - {None}:
                self.eject(r)                   # a docked group joins on its own, not with its host
            self.try_merge(frm, d, to)
            return
        if clear(self.pos, taken(self.isl[frm]), frm, to, VEC[d]):
            return
        self.try_rehang(frm, d, to)
        if rf != rt and not clear(self.pos, taken(self.isl[frm]), frm, to, VEC[d]):
            # a compass passage outranks the weak hint: release and set down properly
            for r in {rf, rt} - {None}:
                self.eject(r)
            if self.isl[frm] != self.isl[to]:
                self.try_merge(frm, d, to)

    def absorb(self, keep, drop):
        """Two boxes turn out to be the same thing: fold `drop` into `keep`."""
        swap = lambda r: keep if r == drop else r
        self.known = list(dict.fromkeys((swap(a), d, swap(b)) for a, d, b in self.known if swap(a) != swap(b)))
        self.tethers = [(swap(a), swap(b)) for a, b in self.tethers if swap(a) != swap(b)]
        for r, p in self.parent.items():
            if p == drop:
                self.parent[r] = None                  # (re-hanging them on keep could make a loop)
        self.docked.discard(drop)
        del self.pos[drop], self.isl[drop], self.parent[drop]
        for a, d, b in list(self.known):               # passages that now join two areas
            if keep in (a, b) and self.isl[a] != self.isl[b]:
                self.try_merge(a, d, b)

    def is_clear(self, a, d, b):
        if a not in self.pos or b not in self.pos or self.isl[a] != self.isl[b]:
            return False
        return clear(self.pos, {self.pos[r] for r in self.members(self.isl[a])}, a, b, VEC[d])


def main(path, runs):
    g = json.load(open(path))
    rooms = g["rooms"]
    lines = straight_lines(rooms)
    nonmaze = [l for l in lines if not (rooms[l[0]]["maze"] or rooms[l[2]]["maze"])]
    print("straight two-way compass lines in the true map: %d (%d outside the maze)"
          % (len(lines), len(nonmaze)))
    print("%-22s %7s %9s %6s %6s %7s %6s %7s %8s %8s %6s %8s %8s %6s" % (
        "variant", "clear", "clear", "areas", "1-room", "label", "joins", "branch", "inserts", "released", "cells", "linklen", "tighten", "rooms"))
    print("%-22s %7s %9s %6s %6s %7s %6s %7s" % ("", "all", "non-maze", "", "areas", "links", "", "moves"))
    pairs = {tuple(sorted((r, e["to"]))) for r, i in rooms.items() for e in i["exits"].values()
             if "to" in e and e["to"] != r}
    for name, kw in (("weak hint (previous)", dict(dock=True)),
                     ("weak hint + tightening", dict(dock=True, tight=True))):
        t = collections.Counter()
        for seed in range(runs):
            L = Islands(g["start"], rooms, **kw)
            for frm, d, to, is_new, _ in explore(rooms, g["start"], random.Random(seed)):
                L.move(frm, d, to, is_new)
            t["all"] += sum(1 for a, d, b in lines if L.is_clear(a, d, b))
            t["nm"] += sum(1 for a, d, b in nonmaze if L.is_clear(a, d, b))
            sizes = collections.Counter(L.isl.values())
            t["areas"] += len(sizes)
            t["one"] += sum(1 for s in sizes.values() if s == 1)
            t["cut"] += sum(1 for a, b in pairs if a in L.isl and b in L.isl and L.isl[a] != L.isl[b])
            t["ej"] += L.ejects
            t["tg"] += L.tightens
            t["tr"] += L.tight_rooms
            for a, d, b in lines:
                if L.is_clear(a, d, b):
                    t["len"] += max(abs(L.pos[a][0] - L.pos[b][0]), abs(L.pos[a][1] - L.pos[b][1]))
                    t["nlen"] += 1
            t["merges"] += L.merges
            t["jolts"] += L.jolts
            t["ins"] += L.inserts
            for i in sizes:
                ps = [L.pos[r] for r in L.members(i)]
                t["cells"] += ((max(p[0] for p in ps) - min(p[0] for p in ps) + 1)
                               * (max(p[1] for p in ps) - min(p[1] for p in ps) + 1))
        n = runs
        print("%-22s %6.0f%% %8.0f%% %6.1f %6.1f %7.1f %6.1f %7.1f %8.1f %8.1f %6.0f %8.2f %8.1f %6.1f" % (
            name, 100 * t["all"] / n / len(lines), 100 * t["nm"] / n / len(nonmaze),
            t["areas"] / n, t["one"] / n, t["cut"] / n, t["merges"] / n, t["jolts"] / n,
            t["ins"] / n, t["ej"] / n, t["cells"] / n, t["len"] / max(1, t["nlen"]),
            t["tg"] / n, t["tr"] / max(1, t["tg"])))


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 200)
