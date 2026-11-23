#!/usr/bin/env python3
"""Stacked map: every level of the game drawn as a tilted plate.

Usage: python3 stack_view.py lurkinghorror_graph.json stack_view.html [seed] [moves between moments]

Built only from what a player would have entered (moves and room names), in
the oblique style of Infocom's own Lurking Horror map: east-west is
horizontal so names read normally, north is toward the back of a plate, and
up/down is vertical on the page.

  plate    rooms you can walk between on the level. Two rooms are level with
           each other if a compass passage joins them (even if "down" is an
           alias for it), or an in/out passage with no stairs beside it.
  level    "up" means higher and "down" means lower, by one level or more.
           Levels are chosen to satisfy as many up/down passages as possible
           and then pulled together so stairs are short.
  maze     a connected maze is one box; its tangle belongs on the flat map.
  elevator a room whose only exits lead somewhere different each time is not a
           room on the map: it is a marker beside every room it stops at.
"""
import collections
import html
import json
import random
import statistics
import sys

from fold import mark_tangles
from islands import Islands
from layout_sim import VEC, clear
from replay import OPP, explore

CW, CD, SH = 116, 46, 12              # cell width, cell depth, shear per row
TW, TD = 102, 34                      # room tile width and depth
GAP = 120                             # clear height between one level and the next
DZ = {"UP": 1, "DOWN": -1}


def build(graph, seed, upto=None):
    """Lay out the stacked map from what the player knows after `upto` moves (all moves if None)."""
    g = json.load(open(graph))
    all_rooms = g["rooms"]
    is_vehicle = lambda i: (any(e.get("kind") == "varies" for e in i["exits"].values())
                            and not any("to" in e for e in i["exits"].values()))
    vehicles = {r: set() for r, i in all_rooms.items() if is_vehicle(i)}
    for r, i in all_rooms.items():
        for e in i["exits"].values():
            if e.get("to") in vehicles:
                vehicles[e["to"]].add(r)
    for v in vehicles:
        for e in all_rooms[v]["exits"].values():
            vehicles[v].update(e.get("any", []))
    rooms = {r: dict(i, exits={d: e for d, e in i["exits"].items() if e.get("to") not in vehicles})
             for r, i in all_rooms.items() if r not in vehicles}

    # keep only what has been seen and walked so far; nothing later may leak in
    full = rooms
    walk = list(explore(full, g["start"], random.Random(seed), jumps=True))
    upto = len(walk) if upto is None else min(upto, len(walk))
    seen, walked = {g["start"]}, set()
    for frm, d, to, _, _ in walk[:upto]:
        seen.add(to)
        if d != "JUMP":
            walked.add((frm, d))
    rooms = mark_tangles({r: dict(full[r], exits={d: e for d, e in full[r]["exits"].items() if (r, d) in walked})
                          for r in seen})               # tangles found by rule, not by any game flag
    # Every passage is assumed to lead back the way it came, so that return is a known
    # line and not an unexplored exit.
    assumed = {(full[a]["exits"][d]["to"], OPP[d]) for a, d in walked if d in OPP}
    unwalked = {r: [d for d, e in full[r]["exits"].items()
                    if e.get("kind") == "to" and e["to"] != r and (r, d) not in walked and (r, d) not in assumed]
                for r in seen}
    # One-way is the player's mark, never inferred by the app. The simulated player marks a
    # passage one-way once every exit of the room it led to has been tried and none came back.
    marked = set()
    for a, d in walked:
        b = full[a]["exits"][d]["to"]
        real = [(dd, e["to"]) for dd, e in full[b]["exits"].items() if "to" in e]
        if a != b and all((b, dd) in walked for dd, _ in real) and all(t != a for _, t in real):
            marked.add((a, b))
    last = walk[upto - 1] if upto else None

    # the flat layout supplies positions inside each walkable area
    L = Islands(g["start"], rooms, dock=False, tight=True)
    order = [g["start"]]
    for frm, d, to, is_new, _ in explore(rooms, g["start"], random.Random(seed), jumps=True):
        L.move(frm, d, to, is_new)
        if is_new:
            order.append(to)
    names = collections.defaultdict(list)
    for r in order:
        names[rooms[r]["desc"]].append(r)
    label = {r: (n if len(rs) == 1 else "%s (%d)" % (n, rs.index(r) + 1))
             for n, rs in names.items() for r in rs}
    links = [(r, d, e["to"]) for r in order for d, e in rooms[r]["exits"].items()
             if e.get("to") and e["to"] != r and e["to"] in L.isl]

    def union_find(items):
        head = {x: x for x in items}

        def top(x):
            while head[x] != x:
                head[x] = head[head[x]]
                x = head[x]
            return x

        def join(a, b):
            a, b = sorted((top(a), top(b)), key=order.index)
            head[b] = a
        return top, join

    # each connected maze becomes one box
    top, join = union_find(order)
    for r, d, t in links:
        if rooms[r]["maze"] and rooms[t]["maze"]:
            join(r, t)
    size = collections.Counter(top(r) for r in order)
    for r in order:
        if size[r] > 1:
            label[r] = "%s x%d (maze)" % (rooms[r]["desc"], size[r])
    links = [(top(r), d, top(t)) for r, d, t in links if top(r) != top(t)]
    boxes = [r for r in order if top(r) == r]

    # which pairs of rooms are level with each other, and which are a climb
    dirs = collections.defaultdict(set)
    for r, d, t in links:
        dirs[frozenset((r, t))].add(d)
    level_pair = lambda r, t: (any(d in VEC for d in dirs[frozenset((r, t))])
                               or not any(d in DZ for d in dirs[frozenset((r, t))]))
    ptop, pjoin = union_find(boxes)
    for r, d, t in links:
        if level_pair(r, t):
            pjoin(r, t)
    plate = {r: ptop(r) for r in boxes}
    members = collections.defaultdict(list)
    for r in boxes:
        members[plate[r]].append(r)

    # positions inside a plate: the flat layout's, with any pieces it had kept
    # apart set down beside each other along the passage that joins them
    lpos = {}
    for p, rs in members.items():
        pieces = collections.defaultdict(list)
        for r in rs:
            pieces[L.isl[r]].append(r)
        first = max(pieces, key=lambda a: len(pieces[a]))
        shift = {first: (0, 0)}
        cells = {L.pos[r] for r in pieces[first]}
        while len(shift) < len(pieces):
            pick = None
            for r, d, t in links:
                if plate.get(r) != p or plate.get(t) != p or not level_pair(r, t):
                    continue
                v = VEC.get(d, (1, 0))
                if L.isl[r] in shift and L.isl[t] not in shift:
                    pick = (r, t, v)
                elif L.isl[t] in shift and L.isl[r] not in shift:
                    pick = (t, r, (-v[0], -v[1]))
                if pick:
                    break
            if not pick:                                   # cannot happen for a connected plate
                a = next(a for a in pieces if a not in shift)
                pick = (pieces[first][0], pieces[a][0], (1, 0))
            a, b, v = pick
            ax = L.pos[a][0] + shift[L.isl[a]][0], L.pos[a][1] + shift[L.isl[a]][1]
            for k in range(1, 60):
                dx, dy = ax[0] + v[0] * k - L.pos[b][0], ax[1] + v[1] * k - L.pos[b][1]
                new = {(L.pos[r][0] + dx, L.pos[r][1] + dy) for r in pieces[L.isl[b]]}
                if not new & cells:
                    shift[L.isl[b]] = (dx, dy)
                    cells |= new
                    break
        for r in rs:
            lpos[r] = (L.pos[r][0] + shift[L.isl[r]][0], L.pos[r][1] + shift[L.isl[r]][1])

    inner = [(r, d, t) for r, d, t in links if plate[r] == plate[t]]
    inner.sort(key=lambda l: l[1] not in VEC)              # prefer the compass passage of a pair
    climbs = [(r, d, t) for r, d, t in links if plate[r] != plate[t]]

    # levels: every climb says "upper plate is above lower plate". Keep each such
    # statement unless it contradicts the ones already kept.
    above = collections.defaultdict(set)                   # lower plate -> plates above it

    def reaches(a, b):
        seen, stack = set(), [a]
        while stack:
            x = stack.pop()
            if x == b:
                return True
            if x not in seen:
                seen.add(x)
                stack.extend(above[x])
        return False

    ignored = 0
    for r, d, t in climbs:
        lo, hi = (plate[r], plate[t]) if DZ.get(d, 0) > 0 else (plate[t], plate[r])
        if d not in DZ or hi in above[lo]:
            continue
        if reaches(hi, lo):
            ignored += 1
            continue
        above[lo].add(hi)
    below = collections.defaultdict(set)
    for lo, his in list(above.items()):
        for hi in his:
            below[hi].add(lo)
    z = {p: 0 for p in members}
    for _ in range(len(members) + 1):                      # lowest levels that satisfy everything
        for lo in list(above):
            for hi in above[lo]:
                z[hi] = max(z[hi], z[lo] + 1)
    # Then make the stairs as short as possible. A stairway longer than one level
    # is shortened by moving everything rigidly hanging off one of its ends
    # (plates joined by one-level stairs move together) a level toward the other.
    edges = [(lo, hi) for lo in above for hi in above[lo]]
    total = lambda: sum(z[hi] - z[lo] for lo, hi in edges)
    improved = True
    while improved:
        improved = False
        for lo, hi in edges:
            if z[hi] - z[lo] < 2:
                continue
            for start, step in ((lo, 1), (hi, -1)):
                comp, stack = {start}, [start]
                while stack:
                    x = stack.pop()
                    for a, b in edges:
                        if z[b] - z[a] == 1 and x in (a, b):
                            y = b if x == a else a
                            if y not in comp:
                                comp.add(y)
                                stack.append(y)
                if (hi if start == lo else lo) in comp:
                    continue
                before = total()
                for x in comp:
                    z[x] += step
                if all(z[b] - z[a] >= 1 for a, b in edges) and total() < before:
                    improved = True
                    break
                for x in comp:
                    z[x] -= step
            if improved:
                break

    # groups of plates joined by stairs; groups with no way in on foot are insets
    gtop, gjoin = union_find(boxes)
    for r, d, t in climbs:
        gjoin(plate[r], plate[t])
    group = {p: gtop(p) for p in members}
    main = group[plate[top(g["start"])]]
    for gid in set(group.values()):
        base = z[plate[top(g["start"])]] if gid == main else max(z[p] for p in group if group[p] == gid)
        for p in group:
            if group[p] == gid:
                z[p] -= base

    # where each plate sits: directly above or below the room it is reached from
    off = {}

    def box(p):
        xs = [lpos[r][0] + off[p][0] for r in members[p]]
        ys = [lpos[r][1] + off[p][1] for r in members[p]]
        return min(xs), min(ys), max(xs), max(ys)

    def settle(p):
        home = off[p]
        for step in range(600):
            off[p] = (home[0] + ((step + 1) // 2) * (1 if step % 2 else -1), home[1])
            x0, y0, x1, y1 = box(p)
            if not any(q != p and z[q] == z[p] and x0 <= box(q)[2] + 1 and box(q)[0] <= x1 + 1
                       and y0 <= box(q)[3] + 1 and box(q)[1] <= y1 + 1 for q in off):
                return

    gids = [main] + sorted(set(group.values()) - {main}, key=order.index)
    for gid in gids:
        todo = [p for p in members if group[p] == gid]
        seed_plate = plate[top(g["start"])] if gid == main else todo[0]
        far = max((box(q)[2] for q in off), default=-4) + 4
        off[seed_plate] = (0, 0) if gid == main else (far - min(lpos[r][0] for r in members[seed_plate]), 0)
        settle(seed_plate)
        while any(p not in off for p in todo):
            for r, d, t in climbs:
                a, b = plate[r], plate[t]
                if group[a] != gid or (a in off) == (b in off):
                    continue
                if b in off:
                    a, b, r, t = b, a, t, r
                off[b] = (lpos[r][0] + off[a][0] - lpos[t][0], lpos[r][1] + off[a][1] - lpos[t][1])
                settle(b)
                break
    gpos = {r: (lpos[r][0] + off[plate[r]][0], lpos[r][1] + off[plate[r]][1]) for r in boxes}
    here = top(last[2]) if last else g["start"]
    said = "Start: %s" % label[here]
    if last:
        said = ("Arrived in %s by other means" % label[here] if last[1] == "JUMP" else
                "%s \u2192 %s \u2192 %s" % (label[top(last[0])], last[1].lower(), label[here]))
    return dict(oneway={(top(a), top(b)) for a, b in marked}, todo=unwalked, here=here, said=said, upto=upto, total=len(walk),
                all_rooms=all_rooms, rooms=rooms, vehicles=vehicles, boxes=boxes, label=label,
                plate=plate, members=members, lpos=lpos, inner=inner, climbs=climbs, z=z,
                gpos=gpos, group=group, main=main, ignored=ignored, box=box, size=size)


def wrap(s, n=15):
    lines = [""]
    for w in s.split(" "):
        if lines[-1] and len(lines[-1]) + 1 + len(w) > n:
            lines.append(w)
        else:
            lines[-1] = (lines[-1] + " " + w).strip()
    return lines[:2]


def render(m, title):
    z, gpos, plate, lpos = m["z"], m["gpos"], m["plate"], m["lpos"]
    zr = lambda r: z[plate[r]]
    levels = sorted(set(z.values()), reverse=True)
    # each level gets a band just deep enough for its plates
    top_of, y = {}, 0
    span = {}
    for lv in levels:
        ys = [gpos[r][1] for r in m["boxes"] if zr(r) == lv]
        span[lv] = (min(ys), max(ys))
        top_of[lv] = y
        y += (max(ys) - min(ys)) * CD + TD + GAP

    def project(gx, gy, lv):
        return gx * CW - gy * SH, top_of[lv] + (gy - span[lv][0]) * CD

    centre = lambda r: project(gpos[r][0], gpos[r][1], zr(r))

    def tile(r):
        x, y = centre(r)
        k = SH * TD / CD / 2
        return [(x - TW / 2 + k, y - TD / 2), (x + TW / 2 + k, y - TD / 2),
                (x + TW / 2 - k, y + TD / 2), (x - TW / 2 - k, y + TD / 2)]

    poly = lambda ps: " ".join("%.1f,%.1f" % p for p in ps)
    plates, lines, tiles, dots, frames, stubs = [], [], [], [], [], []
    seen = set()
    for p, rs in m["members"].items():
        lv = z[p]
        x0, y0, x1, y1 = m["box"](p)
        pad = 0.6
        corners = [project(x0 - pad, y0 - pad, lv), project(x1 + pad, y0 - pad, lv),
                   project(x1 + pad, y1 + pad, lv), project(x0 - pad, y1 + pad, lv)]
        plates.append('<polygon class="plate" points="%s"/>' % poly(corners))
        taken = {lpos[r] for r in rs}
        for r, d, t in m["inner"]:
            key = frozenset((r, t))
            if plate[r] != p or key in seen:
                continue
            seen.add(key)
            a, b = centre(r), centre(t)
            straight = d in VEC and clear(lpos, taken, r, t, VEC[d])
            lines.append('<line class="%s" x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f"/>' % (
                "in" if straight else "in bent", a[0], a[1], b[0], b[1]))
        for r in rs:
            x, y = centre(r)
            info = m["rooms"][r]
            cls = "tile maze" if m["size"][r] > 1 else ("tile dark" if info["dark"] else "tile")
            if r == m["here"]:
                cls += " here"
            # exits read about but not walked yet: a green stub with a ring, or "U?" / "D?"
            for d in (m["todo"].get(r, []) if m["size"][r] == 1 else []):
                if d in VEC:
                    vx, vy = VEC[d]
                    sx, sy = vx * CW - vy * SH, vy * CD                  # that direction on the page
                    n = (sx * sx + sy * sy) ** 0.5
                    ex, ey = x + vx * TW / 2 - vy * SH * TD / CD / 2, y + vy * TD / 2
                    lines.append('<line class="todo" x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f"/>' % (
                        ex, ey, ex + sx / n * 9, ey + sy / n * 9))
                    dots.append('<circle class="todo ring" cx="%.1f" cy="%.1f" r="3.6"/>' % (
                        ex + sx / n * 12, ey + sy / n * 12))
                elif d in DZ:
                    up = d == "UP"
                    tiles.append('<text class="todo-t" x="%.1f" y="%.1f">%s?</text>' % (
                        x - TW / 2 + 4, y + (-TD / 2 - 3 if up else TD / 2 + 10), "U" if up else "D"))
            tiles.append('<polygon class="%s" points="%s"/>' % (cls, poly(tile(r))))
            ws = wrap(m["label"][r])
            for i, ln in enumerate(ws):
                tiles.append('<text class="name" x="%.1f" y="%.1f">%s</text>' % (
                    x, y + 3.5 + (i - (len(ws) - 1) / 2) * 11, html.escape(ln)))
    # stairs: from the bottom edge of the upper room to the top edge of the lower
    solid = [(centre(r)[0] - TW / 2 - 6, centre(r)[1] - TD / 2 - 2, centre(r)[0] + TW / 2 + 6,
              centre(r)[1] + TD / 2 + 2) for r in m["boxes"]]       # nothing may be written over these
    done = set()
    for r, d, t in m["climbs"]:
        key = frozenset((r, t))
        if key in done:
            continue
        done.add(key)
        a, b = centre(r), centre(t)
        if abs(a[0] - b[0]) > 2.5 * CW and zr(r) != zr(t):
            # too far sideways to draw: a short stub at each end naming the other,
            # like the "passage to another map section" arrows on Infocom's map
            for here, there, other in ((r, a, t), (t, b, r)):
                up = zr(other) > zr(here)
                text = "%s to %s" % ("up" if up else "down", m["label"][other])
                wide = len(text) * 6.3 + 6
                x, y = there
                # try the side matching the climb (above for up, below for down), then
                # left/right; take the first spot where the label touches no room or label
                # a "down" stub pointing up the page would mislead, so the far
                # side of the room is the last resort
                v = [(0, -1), (0, 1)] if up else [(0, 1), (0, -1)]
                for dx, dy in [v[0], (1, 0), (-1, 0), v[1]]:
                    if dy:
                        p0, p1 = (x, y + dy * TD / 2), (x, y + dy * (TD / 2 + 20))
                        bx = (p1[0] - wide / 2, p1[1] + (dy * 4 - 11 if dy < 0 else 3), p1[0] + wide / 2,
                              p1[1] + (dy * 4 + 3 if dy < 0 else 17))
                        anchor, tx, ty = "middle", p1[0], (p1[1] - 5 if dy < 0 else p1[1] + 14)
                    else:
                        p0, p1 = (x + dx * TW / 2, y), (x + dx * (TW / 2 + 18), y)
                        bx = (min(p1[0] + dx * 4, p1[0] + dx * (4 + wide)), y - 8,
                              max(p1[0] + dx * 4, p1[0] + dx * (4 + wide)), y + 7)
                        anchor, tx, ty = ("start" if dx > 0 else "end"), p1[0] + dx * 5, y + 4
                    if not any(bx[0] < o[2] and bx[2] > o[0] and bx[1] < o[3] and bx[3] > o[1] for o in solid):
                        break
                solid.append(bx)
                lines.append('<line class="stair" x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f"/>' % (p0 + p1))
                dots.append('<circle class="sdot" cx="%.1f" cy="%.1f" r="3.6"/>' % p0)
                stubs.append((tx, ty, anchor, text))
            continue
        # One-way passages get an arrowhead at the room they lead to. A passage
        # the levels could not honour (a "down" that had to be drawn level or
        # upward) is dashed and says which way it really goes.
        both = (r, t) not in m["oneway"] and (t, r) not in m["oneway"]   # two-way unless marked
        wrong = d in DZ and (zr(t) > zr(r)) - (zr(t) < zr(r)) != DZ[d]
        if zr(r) == zr(t):
            lf, rt = (a, b) if a[0] <= b[0] else (b, a)
            to_b = centre(t)[0] >= centre(r)[0]
            a, b = (lf[0] + TW / 2, lf[1]), (rt[0] - TW / 2, rt[1])
        else:
            hi, lo = (a, b) if zr(r) > zr(t) else (b, a)
            to_b = zr(t) < zr(r)
            a, b = (hi[0], hi[1] + TD / 2), (lo[0], lo[1] - TD / 2)
        arrow = "" if both else (' marker-end="url(#arr)"' if to_b else ' marker-start="url(#arr)"')
        lines.append('<line class="stair%s" x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f"%s/>' % (
            " wrong" if wrong else "", a[0], a[1], b[0], b[1], arrow))
        if wrong:
            note = 'really "%s", one-way' % d.lower() if not both else 'really "%s"' % d.lower()
            wide = len(note) * 6.3 + 6
            for f in (0.5, 0.4, 0.6, 0.3, 0.7, 0.2, 0.8, 0.12, 0.88):   # first spot along the line clear of rooms
                x, y = a[0] + (b[0] - a[0]) * f + 8, a[1] + (b[1] - a[1]) * f
                bx = (x, y - 11, x + wide, y + 4)
                if not any(bx[0] < o[2] and bx[2] > o[0] and bx[1] < o[3] and bx[3] > o[1] for o in solid):
                    break
            solid.append(bx)
            stubs.append((x, y, "start", note))
        dots += ['<circle class="sdot" cx="%.1f" cy="%.1f" r="3.6"/>' % c for c in (a, b)]
    # elevator: a marker beside each stop, joined by a dotted shaft
    key_notes = []
    for v, stops in m["vehicles"].items():
        stops = sorted((s for s in stops if s in gpos), key=lambda s: centre(s)[1])
        cell = {(gpos[r], zr(r)) for r in m["boxes"]}
        free = lambda dx: not any(((gpos[s][0] + dx, gpos[s][1]), zr(s)) in cell for s in stops)
        # one side for every stop, so the shaft is a single straight line
        if free(1):
            spot = lambda x, y: (x + TW / 2 + 17, y)
        elif free(-1):
            spot = lambda x, y: (x - TW / 2 - 17, y)
        else:
            spot = lambda x, y: (x + TW / 2 + 4, y - TD / 2 - 10)
        spots = [spot(*centre(s)) for s in stops]
        letter = m["all_rooms"][v]["desc"][:1]
        key_notes.append("%s = %s" % (letter, m["all_rooms"][v]["desc"]))
        for a, b in zip(spots, spots[1:]):
            lines.append('<line class="shaft" x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f"/>' % (a[0], a[1], b[0], b[1]))
        for x, y in spots:
            tiles.append('<rect class="car" x="%.1f" y="%.1f" width="22" height="18" rx="3"/>' % (x - 11, y - 9))
            tiles.append('<text class="cart" x="%.1f" y="%.1f">%s</text>' % (x, y + 4, html.escape(letter)))
    pts = [centre(r) for r in m["boxes"]]
    minx, maxx = min(p[0] for p in pts) - 210, max(p[0] for p in pts) + 130
    miny, maxy = min(p[1] for p in pts) - 80, max(p[1] for p in pts) + 80
    # groups you cannot walk to are boxed, like Infocom's "Dream" inset
    for gid in sorted(set(m["group"].values()) - {m["main"]}, key=str):
        ps = [centre(r) for r in m["boxes"] if m["group"][plate[r]] == gid]
        x0, x1 = min(p[0] for p in ps) - 88, max(p[0] for p in ps) + 88
        y0, y1 = min(p[1] for p in ps) - 58, max(p[1] for p in ps) + 48
        frames.append('<rect class="inset" x="%.1f" y="%.1f" width="%.1f" height="%.1f" rx="6"/>' % (x0, y0, x1 - x0, y1 - y0))
        frames.append('<text class="insetl" x="%.1f" y="%.1f">Not reached on foot</text>' % (x0 + 9, y0 + 16))
        maxx, miny, maxy = max(maxx, x1 + 12), min(miny, y0 - 12), max(maxy, y1 + 12)
    labels, rules = [], []
    main_boxes = [r for r in m["boxes"] if m["group"][plate[r]] == m["main"]]
    for lv in levels:
        here = [centre(r)[1] for r in main_boxes if zr(r) == lv]
        if not here:
            continue
        mid = (min(here) + max(here)) / 2
        name = "Start level" if lv == 0 else ("%d up" % lv if lv > 0 else "%d down" % -lv)
        labels.append('<text class="lv" x="%.1f" y="%.1f">%s</text>' % (minx + 14, mid + 5, name))
        rules.append('<line class="rule" x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f"/>' % (minx + 100, mid, maxx - 10, mid))

    w, h = maxx - minx, maxy - miny
    stats = "%d boxes on %d plates over %d levels · %d stairways" % (
        len(m["boxes"]), len(m["members"]), len(levels), len(done))
    if m["ignored"]:
        stats += " · %d up/down passages contradict the rest and are drawn level" % m["ignored"]
    for x, y, anchor, text in stubs:
        labels.append('<text class="stub" text-anchor="%s" x="%.1f" y="%.1f">%s</text>' % (
            anchor, x, y, html.escape(text)))
    body = "\n".join(rules + frames + plates + lines + tiles + dots + labels)
    todo_n = sum(len(v) for r, v in m["todo"].items() if r in m["plate"] and m["size"][r] == 1)
    stats += " · %d unexplored exits" % todo_n
    svg = SVG % dict(vb="%.0f %.0f %.0f %.0f" % (minx, miny, w, h), w=w, h=h, body=body)
    return dict(svg=svg, stats=stats, said=m["said"], move=m["upto"],
                cars=["%s: shown beside each room it stops at" % k for k in key_notes])


PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%(title)s stacked map</title>
<style>
  :root { --bg:#f4f1ea; --ink:#22201c; --muted:#6d675c; --box:#fffdf8; --plate:#ddd6c4; --edge:#978f7c;
          --stair:#c2410c; --car:#2f6f8f; --maze:#f3d9a0; --dark:#bdb5a1; --panel:#ebe6da; --rule:#e4dece; --todo:#1d7a4f; }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#1b1a17; --ink:#ece7dc; --muted:#a39c8e; --box:#3a372f; --plate:#2c2a25; --edge:#6f6959;
            --stair:#fb923c; --car:#7cc4e4; --maze:#6b5220; --dark:#14130f; --panel:#25231f; --rule:#26241f; --todo:#5fd39b; }
  }
  body { margin:0; background:var(--bg); color:var(--ink); font:14px/1.45 system-ui, sans-serif; }
  header { background:var(--panel); padding:10px 16px; border-bottom:1px solid var(--muted); }
  h1 { font-size:17px; margin:0 0 2px; }
  .legend { display:flex; flex-wrap:wrap; gap:4px 18px; color:var(--muted); font-size:12.5px; margin-top:4px; }
  .key { display:inline-block; width:22px; height:0; border-top:3px solid; vertical-align:middle; margin-right:5px; }
  .sw { display:inline-block; width:16px; height:11px; border:1px solid var(--ink); vertical-align:middle; margin-right:5px; }
  .car2 { background:var(--car); border-color:var(--car); }
  #wrap { overflow:auto; padding:8px 16px 24px; }
  .rule { stroke:var(--rule); stroke-width:1; }
  .plate { fill:var(--plate); stroke:var(--edge); stroke-width:1.2; }
  .tile { fill:var(--box); stroke:var(--ink); stroke-width:1.2; }
  .tile.maze { fill:var(--maze); }
  .tile.dark { fill:var(--dark); }
  .name { fill:var(--ink); font:600 10px system-ui, sans-serif; text-anchor:middle; }
  .in { stroke:var(--ink); stroke-width:1.6; }
  .in.bent { stroke:var(--muted); stroke-dasharray:2 3; }
  .stair { stroke:var(--stair); stroke-width:2.4; }
  .stair.wrong { stroke-dasharray:7 5; }
  .sdot { fill:var(--stair); }
  .shaft { stroke:var(--car); stroke-width:1.8; stroke-dasharray:3 4; }
  .car { fill:var(--car); }
  .cart { fill:var(--bg); font:700 11px system-ui, sans-serif; text-anchor:middle; }
  .inset { fill:none; stroke:var(--muted); stroke-width:1.4; stroke-dasharray:8 6; }
  .insetl { fill:var(--muted); font:italic 12.5px system-ui, sans-serif; }
  .stub { fill:var(--stair); font:italic 600 11px system-ui, sans-serif;
          paint-order:stroke; stroke:var(--bg); stroke-width:3.5px; stroke-linejoin:round; }
  .lv { fill:var(--muted); font:700 13px system-ui, sans-serif; }
  .tile.here { stroke:var(--stair); stroke-width:3.4; }
  .todo { stroke:var(--todo); stroke-width:2; fill:none; }
  .todo.ring { fill:var(--bg); }
  .todo-t { fill:var(--todo); font:700 10.5px system-ui, sans-serif; }
  .bar { display:flex; flex-wrap:wrap; gap:8px 12px; align-items:center; margin:6px 0 2px; }
  .bar input { width:min(460px, 60vw); }
  button { font:inherit; padding:3px 10px; color:var(--ink); background:var(--box); border:1px solid var(--muted); border-radius:4px; }
  #said { color:var(--muted); }
</style></head><body>
<header>
  <h1>%(title)s: stacked map</h1>
  <div class="bar"><button id="prev">&#9664;</button><input id="step" type="range" min="0" value="0">
    <button id="next">&#9654;</button><span id="where"></span></div>
  <div id="stats"></div><div id="said"></div>
  <div class="legend">
    <span><i class="key" style="border-color:var(--stair)"></i>up/down passage (assumed two-way; arrowhead: marked one-way)</span>
    <span><i class="key" style="border-color:var(--stair);border-top-style:dashed"></i>up/down passage that could not be drawn the way it goes</span>
    <span><i class="key" style="border-color:var(--ink);border-top-width:2px"></i>level passage</span>
    <span><i class="sw" style="background:var(--dark)"></i>dark room</span>
    <span><i class="sw" style="background:var(--maze)"></i>a tangle of same-named rooms as one box</span>
    <span style="color:var(--todo)">Green stub with a ring, or "U?" / "D?": an exit not walked yet</span>
    <span>Orange outline: where you are</span>
    %(cars)s
    <span>Each plate is a set of rooms on one level. Higher on the page is higher in the game; north is toward the back of a plate.</span>
  </div>
</header>
<div id="wrap"></div>
<script>
const F = %(frames)s;
const $ = id => document.getElementById(id);
function show(i) {
  const f = F[i];
  $("wrap").innerHTML = f.svg;
  $("stats").textContent = f.stats;
  $("said").textContent = f.said;
  $("where").textContent = "Move " + f.move + " of " + F[F.length - 1].move;
}
$("step").max = F.length - 1;
const start = location.hash ? Math.min(F.length - 1, +location.hash.slice(1)) : F.length - 1;
$("step").value = start;
$("step").oninput = e => show(+e.target.value);
const nudge = n => { const s = $("step"); s.value = Math.max(0, Math.min(+s.max, +s.value + n)); show(+s.value); };
$("prev").onclick = () => nudge(-1);
$("next").onclick = () => nudge(1);
document.addEventListener("keydown", e => { if (e.key === "ArrowLeft") nudge(-1); if (e.key === "ArrowRight") nudge(1); });
show(start);
</script>
</body></html>
"""

SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="%(vb)s" width="%(w).0f" height="%(h).0f">
<defs><marker id="arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
<path d="M0,0 L10,5 L0,10 z" fill="context-stroke"/></marker></defs>
%(body)s
</svg>"""

TITLES = {"lurkinghorror": "The Lurking Horror", "zork1": "Zork I", "zork2": "Zork II",
          "enchanter": "Enchanter", "planetfall": "Planetfall"}

if __name__ == "__main__":
    graph, out = sys.argv[1], sys.argv[2]
    seed = int(sys.argv[3]) if len(sys.argv) > 3 else 1
    every = int(sys.argv[4]) if len(sys.argv) > 4 else 6
    key = graph.split("/")[-1].replace("_graph.json", "")
    total = build(graph, seed, 0)["total"]
    frames = []
    for k in sorted(set(range(0, total, every)) | {total}):   # the map as it stood every few moves
        m = build(graph, seed, k)
        frames.append(render(m, TITLES.get(key, key)))
    cars = "".join('<span><i class="sw car2"></i>%s</span>' % html.escape(c) for c in frames[-1].pop("cars"))
    for f in frames:
        f.pop("cars", None)
    page = PAGE % dict(title=html.escape(TITLES.get(key, key)), cars=cars,
                       frames=json.dumps(frames).replace("</", "<\\/"))
    open(out, "w").write(page)
    print("wrote %s: %d moments, final %d plates on levels %s, ignored %d" % (
        out, len(frames), len(m["members"]), sorted(set(m["z"].values())), m["ignored"]))
