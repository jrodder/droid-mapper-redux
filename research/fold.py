"""Tangles off the flat map, for any game, with nothing for the player to flag.

Some clusters of rooms cannot be drawn on a grid in any useful way: several
rooms with the same name whose exits do not come back the way they went.
Mazes are the famous case but the rule below does not know the word. It uses
only what every player enters anyway: room names and the moves they made.

  tangled room   at least two of its walked exits are known not to return the
                 way they went (or lead straight back to the room), and those
                 outnumber or equal its well-behaved ones
  tangle         three or more connected tangled rooms sharing one name

As soon as a tangle exists it is folded into one box on the flat map and has
its own web-and-table view. From then on every room of that name connected to
it belongs to it, whether already on the map or found later.
"""
import collections

from islands import Islands
from layout_sim import VEC
from replay import OPP

MIN_TANGLE = 3


def is_tangled(exits, back):
    """exits: {dir: dest} walked from this room; back(dest, dir) -> where that returns, or None if unknown."""
    good = bad = 0
    for d, t in exits.items():
        if t is None:
            continue
        if t == "SELF":
            bad += 1
        elif d in OPP:
            r = back(t, OPP[d])
            if r == "HERE":
                good += 1
            elif r is not None:
                bad += 1
    return bad >= 2 and bad >= good


def mark_tangles(rooms):
    """With the whole game known: set info["maze"] on every room in a tangle."""
    def tangled(r):
        exits = {d: ("SELF" if e["to"] == r else e["to"]) for d, e in rooms[r]["exits"].items() if "to" in e}
        back = lambda t, d: (lambda e: None if e is None else ("HERE" if e.get("to") == r else "ELSE"))(
            rooms[t]["exits"].get(d))
        return is_tangled(exits, back)
    T = {r for r in rooms if tangled(r)}
    near = collections.defaultdict(set)
    for r in T:
        for e in rooms[r]["exits"].values():
            t = e.get("to")
            if t in T and t != r and rooms[t]["desc"] == rooms[r]["desc"]:
                near[r].add(t)
                near[t].add(r)
    seen = set()
    for info in rooms.values():
        info["maze"] = False
    for r in T:
        if r in seen:
            continue
        comp, stack = {r}, [r]
        while stack:
            for y in near[stack.pop()]:
                if y not in comp:
                    comp.add(y)
                    stack.append(y)
        seen |= comp
        if len(comp) >= MIN_TANGLE:
            stack = list(comp)                           # same-named neighbours belong to it too
            while stack:
                x = stack.pop()
                nbrs = {e["to"] for e in rooms[x]["exits"].values() if "to" in e}
                nbrs |= {y for y, i in rooms.items() if any(e.get("to") == x for e in i["exits"].values())}
                for y in nbrs:
                    if y not in comp and rooms[y]["desc"] == rooms[x]["desc"]:
                        comp.add(y)
                        stack.append(y)
            for x in comp:
                rooms[x]["maze"] = True
    return rooms


class TangleFold:
    def __init__(self, start, rooms, **kw):
        self.rooms = rooms                              # used for names only
        self.L = Islands(start, rooms, maze_sheet=False, **kw)
        self.walked = collections.defaultdict(dict)     # room -> {dir: dest}
        self.into = collections.defaultdict(set)        # room -> rooms with a walked exit leading to it
        self.box = {}                                   # folded room -> the box that stands for it
        self.seen = [start]
        self.age = {start: 0}

    def fold(self, r):
        return self.box.get(r, r)

    def boxes(self):
        return set(self.box.values())

    def label(self, b):
        n = sum(1 for g in self.box.values() if g == b)
        return "%s x%d" % (self.rooms[b]["desc"], n)

    def _tangled(self, r):
        exits = {d: ("SELF" if t == r else t) for d, t in self.walked[r].items()}
        back = lambda t, d: (lambda x: None if x is None else ("HERE" if x == r else "ELSE"))(self.walked[t].get(d))
        return is_tangled(exits, back)

    def _cluster(self, r):
        """Connected rooms with r's name that are tangled or already folded."""
        name = self.rooms[r]["desc"]
        memo = {}

        def ok(x):
            if x not in memo:
                memo[x] = self.rooms[x]["desc"] == name and (x in self.box or self._tangled(x))
            return memo[x]
        if not ok(r):
            return set()
        comp, stack = {r}, [r]
        while stack:
            x = stack.pop()
            for y in set(self.walked[x].values()) | self.into[x]:
                if y not in comp and ok(y):
                    comp.add(y)
                    stack.append(y)
        if len(comp) >= MIN_TANGLE:                     # a real tangle: same-named neighbours belong to it
            stack = list(comp)
            while stack:
                x = stack.pop()
                for y in set(self.walked[x].values()) | self.into[x]:
                    if y not in comp and self.rooms[y]["desc"] == name:
                        comp.add(y)
                        stack.append(y)
        return comp

    def move(self, frm, d, to, is_new):
        """Returns the move as the flat map sees it, or None if it stayed inside a tangle."""
        if is_new:
            self.seen.append(to)
            self.age[to] = len(self.age)
        if d != "JUMP":
            self.walked[frm][d] = to
            self.into[to].add(frm)
        same = self.rooms[frm]["desc"] == self.rooms[to]["desc"]
        result = None
        if to not in self.L.pos and to not in self.box and frm in self.box and same and d != "JUMP":
            self.box[to] = self.box[frm]                # deeper into the same tangle
        else:
            f, t = self.fold(frm), self.fold(to)
            if f != t:
                new = t not in self.L.pos
                self.L.move(f, d, t, new)
                result = (f, d, t, new)
        check = [frm, to]                               # has a tangle formed or grown?
        for r in (frm, to):
            check += [y for y in set(self.walked[r].values()) | self.into[r]
                      if y in self.box and self.rooms[y]["desc"] == self.rooms[r]["desc"]]
        for r in check:
            comp = self._cluster(r)
            if len(comp) < MIN_TANGLE:
                continue
            if all(x in self.box for x in comp) and len({self.box[x] for x in comp}) == 1:
                continue                                # nothing new
            keep = min((self.fold(x) for x in comp), key=self.age.get)
            for x in sorted(comp, key=self.age.get):
                old = self.fold(x)
                self.box[x] = keep
                if old != keep and old in self.L.pos:
                    for y, g in self.box.items():
                        if g == old:
                            self.box[y] = keep
                    self.L.absorb(keep, old)
        return result
