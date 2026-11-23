#!/usr/bin/env python3
"""Replay exploration of a room graph as a player mapping by hand on a grid.

Usage: python3 -I replay.py zork1_graph.json [runs]

The simulated player explores in a random order (untried exit here, else walk
to the nearest room with one). Exits that need a puzzle solved are only tried
after everything else, modelling "you learn this much later".

The player maps with the most naive rule possible: a new room goes in the
neighbouring grid cell in the direction travelled. Every move is classified by
what that rule would have required, so the counts show which situations a
mapping UI has to make cheap. The player is assumed to always recognise a room
they have been in before (in mazes that is not true; see static report).
"""
import collections
import json
import random
import sys

VEC = {"NORTH": (0, -1), "SOUTH": (0, 1), "EAST": (1, 0), "WEST": (-1, 0),
       "NE": (1, -1), "NW": (-1, -1), "SE": (1, 1), "SW": (-1, 1)}
OPP = {"NORTH": "SOUTH", "SOUTH": "NORTH", "EAST": "WEST", "WEST": "EAST",
       "NE": "SW", "SW": "NE", "NW": "SE", "SE": "NW",
       "UP": "DOWN", "DOWN": "UP", "IN": "OUT", "OUT": "IN"}


def static_report(rooms):
    c = collections.Counter()
    for r, info in rooms.items():
        for d, e in info["exits"].items():
            if "to" not in e:
                c["exit that is described but blocked"] += 1
                continue
            c["passages (one direction each)"] += 1
            if e["kind"] == "cond":
                c["  only opens after a puzzle"] += 1
            if d not in VEC:
                c["  not a compass direction (up/down/in/out/land)"] += 1
            dest = e["to"]
            if dest == r:
                c["  leads back to the same room"] += 1
                continue
            back = [bd for bd, be in rooms[dest]["exits"].items() if be.get("to") == r]
            if not back:
                c["  one-way: no passage back at all"] += 1
            elif OPP.get(d) in back:
                c["  two-way, straight (east out, west back)"] += 1
            else:
                c["  two-way but bent (east out, not west back)"] += 1
    names = collections.Counter(i["desc"] for i in rooms.values())
    dup = {n: k for n, k in names.items() if k > 1}
    multi = sum(1 for i in rooms.values()
                if len({e["to"] for e in i["exits"].values() if "to" in e})
                < sum(1 for e in i["exits"].values() if "to" in e))
    print("STATIC FACTS ABOUT THE TRUE MAP")
    print("  rooms: %d" % len(rooms))
    for k, v in c.items():
        print("  %-52s %4d" % (k, v))
    print("  rooms sharing a name with another room: %d %s" % (sum(dup.values()), dup))
    print("  rooms where two different exits reach the same place: %d" % multi)
    print("  dark rooms: %d" % sum(1 for i in rooms.values() if i["dark"]))


def explore(rooms, start, rng, jumps=False):
    """Yield (frm, dir, to, is_new, late) for every first use of a passage.

    With jumps on, once nothing more can be reached on foot the player is
    dropped into the next unvisited room (direction "JUMP"), standing in for
    arrivals the exits don't describe: vehicles, teleports, story events.
    """
    here, tried, seen = start, set(), {start}

    def untried(r, allow_cond):
        return [d for d, e in rooms[r]["exits"].items()
                if "to" in e and (r, d) not in tried and (allow_cond or e["kind"] == "to")]

    def nearest(allow_cond):
        # walk only along passages the player has already used
        q, prev = collections.deque([here]), {here: None}
        while q:
            r = q.popleft()
            if untried(r, allow_cond):
                return r
            for d, e in rooms[r]["exits"].items():
                if (r, d) in tried and e["to"] not in prev:
                    prev[e["to"]] = r
                    q.append(e["to"])
        return None

    while True:
        for allow_cond in (False, True):
            while True:
                target = nearest(allow_cond)
                if target is None:
                    break
                here = target
                d = rng.choice(untried(here, allow_cond))
                tried.add((here, d))
                to = rooms[here]["exits"][d]["to"]
                yield here, d, to, to not in seen, allow_cond
                seen.add(to)
                here = to
        rest = [r for r in rooms if r not in seen]
        if not jumps or not rest:
            return
        yield here, "JUMP", rest[0], True, True
        seen.add(rest[0])
        here = rest[0]


def replay(rooms, start, rng):
    pos, cell, c = {start: (0, 0)}, {(0, 0): start}, collections.Counter()
    for frm, d, to, is_new, late in explore(rooms, start, rng):
        if to == frm:
            c["loop back to same room"] += 1
            continue
        v = VEC.get(d)
        if is_new:
            if v is None:
                c["NEW room, no compass direction to place it by"] += 1
                # player picks any free neighbour
                x, y = pos[frm]
                want = next(((x + dx, y + dy) for dx, dy in VEC.values()
                             if (x + dx, y + dy) not in cell), None)
                k = 2
                while want is None:
                    want = next(((x + dx * k, y + dy * k) for dx, dy in VEC.values()
                                 if (x + dx * k, y + dy * k) not in cell), None)
                    k += 1
            else:
                want = (pos[frm][0] + v[0], pos[frm][1] + v[1])
                if want not in cell:
                    c["NEW room, neighbouring cell free (grid just works)"] += 1
                else:
                    c["NEW room, but that cell is already taken"] += 1
                    while want in cell:
                        want = (want[0] + v[0], want[1] + v[1])
            pos[to], cell[want] = want, to
        else:
            if v is None:
                c["KNOWN room reached by up/down/in/out"] += 1
                continue
            dx, dy = pos[to][0] - pos[frm][0], pos[to][1] - pos[frm][1]
            if (dx, dy) == v:
                c["KNOWN room, already the correct neighbour"] += 1
            elif (dx * v[1] == dy * v[0]) and (dx * v[0] + dy * v[1]) > 0:
                c["KNOWN room, right direction but not adjacent"] += 1
            else:
                c["KNOWN room, drawn somewhere that contradicts the move"] += 1
    return c


def main(path, runs):
    g = json.load(open(path))
    rooms = g["rooms"]
    static_report(rooms)
    total = collections.Counter()
    for seed in range(runs):
        total.update(replay(rooms, g["start"], random.Random(seed)))
    print("\nWHAT HAPPENS WHILE MAPPING (average per playthrough, %d random orders)" % runs)
    for k, v in sorted(total.items(), key=lambda kv: -kv[1]):
        print("  %-62s %6.1f" % (k, v / runs))


if __name__ == "__main__":
    main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 200)
