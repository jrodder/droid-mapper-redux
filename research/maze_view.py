#!/usr/bin/env python3
"""A tangle (a maze, or anything like one) drawn as a labelled web plus a lookup table.

Usage: python3 maze_view.py zork1_graph.json maze_view.html [seed]

Compass positions mean nothing in a maze, so rooms are not put on the grid.
Rooms are numbered in the order the player first enters them, spread out so
that connected rooms sit near each other, and every line carries the exit
direction at each end. The table underneath answers "I am in Maze 7 and go
west: where am I?" directly.
"""
import collections
import html
import json
import math
import random
import sys

from fold import mark_tangles
from replay import explore

SHORT = {"NORTH": "N", "SOUTH": "S", "EAST": "E", "WEST": "W", "NE": "NE", "NW": "NW",
         "SE": "SE", "SW": "SW", "UP": "U", "DOWN": "D", "IN": "In", "OUT": "Out"}
COLS = ["NORTH", "NE", "EAST", "SE", "SOUTH", "SW", "WEST", "NW", "UP", "DOWN"]
W, H, R = 1240, 900, 24


def build(graph, seed):
    g = json.load(open(graph))
    rooms = mark_tangles(g["rooms"])                  # tangles found by rule, not by any game flag
    maze = {r for r, i in rooms.items() if i["maze"]}
    if not maze:
        raise SystemExit("no tangle in this game")
    near = {e["to"] for r in maze for e in rooms[r]["exits"].values() if "to" in e} - maze
    near |= {r for r, i in rooms.items() if r not in maze
             and any(e.get("to") in maze for e in i["exits"].values())}
    # number rooms in the order a player walking in would meet them
    start = sorted(near)[0] if g["start"] not in near | maze else g["start"]
    sub = {r: dict(rooms[r], exits={d: e for d, e in rooms[r]["exits"].items()
                                    if "to" in e and e["to"] in maze | near and (r in maze or e["to"] in maze)})
           for r in maze | near}
    order = [start]
    for frm, d, to, is_new, _ in explore(sub, start, random.Random(seed), jumps=True):
        if is_new:
            order.append(to)
    count = collections.Counter()
    name, tag = {}, {}
    for r in order:
        desc = rooms[r]["desc"]
        if r in maze:
            count[desc] += 1
            name[r] = "%s %d" % (desc, count[desc])
            tag[r] = ("%d" if desc.lower() == "maze" else "".join(w[0] for w in desc.split()) + "%d") % count[desc]
        else:
            twins = [x for x in order if x not in maze and rooms[x]["desc"] == desc]
            name[r] = tag[r] = desc if len(twins) == 1 else "%s %d" % (desc, twins.index(r) + 1)
    return rooms, sub, maze, near, order, name, tag


def layout(sub, order, seed, width):
    """Spring layout: connected rooms pull together, all rooms push apart."""
    rng = random.Random(seed)
    pos = {r: [rng.uniform(0.2, 0.8) * W, rng.uniform(0.2, 0.8) * H] for r in order}
    pairs = {frozenset((r, e["to"])) for r in order for e in sub[r]["exits"].values() if e["to"] != r}
    k = math.sqrt(W * H / len(order)) * 0.95
    temp = W / 8
    for _ in range(700):
        move = {r: [0.0, 0.0] for r in order}
        for i, a in enumerate(order):
            for b in order[i + 1:]:
                dx, dy = pos[a][0] - pos[b][0], pos[a][1] - pos[b][1]
                d = max(1.0, math.hypot(dx, dy))
                f = k * k / d
                move[a][0] += dx / d * f; move[a][1] += dy / d * f
                move[b][0] -= dx / d * f; move[b][1] -= dy / d * f
        for p in pairs:
            a, b = tuple(p)
            dx, dy = pos[a][0] - pos[b][0], pos[a][1] - pos[b][1]
            d = max(1.0, math.hypot(dx, dy))
            f = d * d / k
            move[a][0] -= dx / d * f; move[a][1] -= dy / d * f
            move[b][0] += dx / d * f; move[b][1] += dy / d * f
        for r in order:
            d = max(1.0, math.hypot(*move[r]))
            pos[r][0] += move[r][0] / d * min(d, temp)
            pos[r][1] += move[r][1] / d * min(d, temp)
        temp *= 0.992
    # fit the spread to the page (rooms are never pinned to an edge, which would
    # line them up and hide one line behind another)
    xs, ys = [p[0] for p in pos.values()], [p[1] for p in pos.values()]
    for r in order:
        pos[r][0] = 110 + (pos[r][0] - min(xs)) / max(1, max(xs) - min(xs)) * (W - 220)
        pos[r][1] = 70 + (pos[r][1] - min(ys)) / max(1, max(ys) - min(ys)) * (H - 140)
    for _ in range(200):                                   # nothing closer than a clear gap
        for i, a in enumerate(order):
            for b in order[i + 1:]:
                dx, dy = pos[a][0] - pos[b][0], pos[a][1] - pos[b][1]
                d = math.hypot(dx, dy) or 1.0
                need = 100 + width[a] + width[b]           # room for a direction letter at each end
                if d < need:
                    push = (need - d) / 2
                    pos[a][0] += dx / d * push; pos[a][1] += dy / d * push
                    pos[b][0] -= dx / d * push; pos[b][1] -= dy / d * push
    return pos, pairs


def render(rooms, sub, maze, near, order, name, tag, pos, pairs, title):
    out, text, boxes = [], [], []
    half = {r: (R if r in maze else 8 + len(tag[r]) * 3.4) for r in order}   # half-width of a room's shape
    for r in order:
        tall = R if r in maze else R - 6
        boxes.append((pos[r][0] - half[r] - 2, pos[r][1] - tall - 2, pos[r][0] + half[r] + 2, pos[r][1] + tall + 2))

    def place(x, y, ux, uy, s, cls):
        w = len(s) * 6.6 + 6
        for step in range(0, 120, 4):
            cx, cy = x + ux * step, y + uy * step
            b = (cx - w / 2, cy - 8, cx + w / 2, cy + 7)
            if not any(b[0] < o[2] and b[2] > o[0] and b[1] < o[3] and b[3] > o[1] for o in boxes):
                break
        boxes.append(b)
        text.append('<text class="%s" x="%.1f" y="%.1f">%s</text>' % (cls, cx, cy + 4, html.escape(s)))

    for p in sorted(pairs, key=sorted):
        a, b = sorted(p, key=order.index)
        ab = [SHORT[d] for d, e in sub[a]["exits"].items() if e["to"] == b]
        ba = [SHORT[d] for d, e in sub[b]["exits"].items() if e["to"] == a]
        dx, dy = pos[b][0] - pos[a][0], pos[b][1] - pos[a][1]
        d = math.hypot(dx, dy)
        ux, uy = dx / d, dy / d
        # where the line meets each shape (circle for maze rooms, box for others)
        def rim(r, vx, vy):
            if r in maze:
                return pos[r][0] + vx * R, pos[r][1] + vy * R
            t = min(half[r] / abs(vx) if vx else 1e9, (R - 6) / abs(vy) if vy else 1e9)
            return pos[r][0] + vx * t, pos[r][1] + vy * t
        p1, p2 = rim(a, ux, uy), rim(b, -ux, -uy)
        one_way = not ab or not ba
        marker = ""
        if not ba:
            marker = ' marker-end="url(#arr)"'
        elif not ab:
            marker = ' marker-start="url(#arr)"'
        out.append('<line class="edge%s" x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f"%s/>' % (
            " one" if one_way else "", p1[0], p1[1], p2[0], p2[1], marker))
        if ab:
            place(p1[0] + ux * 12, p1[1] + uy * 12, ux, uy, "/".join(ab), "dir")
        if ba:
            place(p2[0] - ux * 12, p2[1] - uy * 12, -ux, -uy, "/".join(ba), "dir")
    shapes = []
    for r in order:
        x, y = pos[r]
        loops = [SHORT[d] for d, e in sub[r]["exits"].items() if e["to"] == r]
        if r in maze:
            dead = False
            shapes.append('<circle class="%s" cx="%.1f" cy="%.1f" r="%d"/>' % ("node dead" if dead else "node", x, y, R))
            shapes.append('<text class="num%s" x="%.1f" y="%.1f">%s</text>' % (" small" if dead else "", x, y + 6, html.escape(tag[r])))
        else:
            shapes.append('<rect class="outside" x="%.1f" y="%.1f" width="%.1f" height="%d" rx="4"/>' % (
                x - half[r], y - R + 6, 2 * half[r], 2 * R - 12))
            shapes.append('<text class="out" x="%.1f" y="%.1f">%s</text>' % (x, y + 4, html.escape(tag[r])))
        if loops:
            place(x, y + R + 12, 0, 1, "↺ " + "/".join(loops), "loop")

    rows = []
    for r in [r for r in order if r in maze]:
        cells = []
        for d in COLS:
            e = sub[r]["exits"].get(d)
            if not e:
                cells.append('<td class="none">·</td>')
            elif e["to"] == r:
                cells.append('<td class="self">↺</td>')
            else:
                cells.append('<td class="%s">%s</td>' % ("exit" if e["to"] not in maze else "", html.escape(tag[e["to"]])))
        rows.append("<tr><th>%s</th>%s</tr>" % (html.escape(name[r]), "".join(cells)))
    n_maze = sum(1 for r in order if r in maze)
    stats = "%d rooms, %d passages between different rooms, %d of them one-way, %d exits that loop back" % (
        n_maze, len(pairs),
        sum(1 for p in pairs if not all(any(e["to"] == y for e in sub[x]["exits"].values())
                                        for x, y in (tuple(p), tuple(p)[::-1]))),
        sum(1 for r in maze for e in sub[r]["exits"].values() if e["to"] == r))
    x0 = min(b[0] for b in boxes) - 24; y0 = min(b[1] for b in boxes) - 24
    x1 = max(b[2] for b in boxes) + 24; y1 = max(b[3] for b in boxes) + 24
    return PAGE % dict(title=html.escape(title), stats=stats, w=x1 - x0, h=y1 - y0,
                       vb="%.0f %.0f %.0f %.0f" % (x0, y0, x1 - x0, y1 - y0),
                       body="\n".join(out + shapes + text),
                       head="".join("<th>%s</th>" % SHORT[d] for d in COLS), rows="\n".join(rows))


PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%(title)s maze</title>
<style>
  :root { --bg:#f4f1ea; --ink:#22201c; --muted:#6d675c; --node:#f3d9a0; --dead:#d9d2c0; --out:#fffdf8;
          --edge:#4b463d; --one:#c2410c; --panel:#ebe6da; --line:#d8d1c0; }
  @media (prefers-color-scheme: dark) {
    :root { --bg:#1b1a17; --ink:#ece7dc; --muted:#a39c8e; --node:#6b5220; --dead:#3d3a33; --out:#34312b;
            --edge:#b9b2a3; --one:#fb923c; --panel:#25231f; --line:#3a372f; }
  }
  body { margin:0; background:var(--bg); color:var(--ink); font:14px/1.45 system-ui, sans-serif; }
  header { background:var(--panel); padding:10px 16px; border-bottom:1px solid var(--muted); }
  h1 { font-size:17px; margin:0 0 2px; } h2 { font-size:15px; margin:18px 16px 6px; }
  .legend { display:flex; flex-wrap:wrap; gap:4px 18px; color:var(--muted); font-size:12.5px; margin-top:4px; }
  #wrap { overflow:auto; padding:8px 16px; }
  .edge { stroke:var(--edge); stroke-width:1.8; }
  .edge.one { stroke:var(--one); stroke-width:2.2; }
  .node { fill:var(--node); stroke:var(--ink); stroke-width:1.5; }
  .node.dead { fill:var(--dead); }
  .outside { fill:var(--out); stroke:var(--ink); stroke-width:1.5; }
  .num { fill:var(--ink); font:700 17px system-ui, sans-serif; text-anchor:middle; }
  .num.small { font-size:12px; }
  .out { fill:var(--ink); font:600 12px system-ui, sans-serif; text-anchor:middle; }
  .dir, .loop { font:700 11.5px system-ui, sans-serif; text-anchor:middle; fill:var(--ink);
                paint-order:stroke; stroke:var(--bg); stroke-width:4px; stroke-linejoin:round; }
  .loop { fill:var(--muted); font-weight:600; }
  .scroll { overflow-x:auto; margin:0 16px 28px; }
  table { border-collapse:collapse; font-variant-numeric:tabular-nums; }
  th, td { border:1px solid var(--line); padding:4px 10px; text-align:center; white-space:nowrap; }
  thead th { background:var(--panel); position:sticky; top:0; }
  tbody th { text-align:left; background:var(--panel); }
  td.none { color:var(--line); } td.self { color:var(--muted); } td.exit { font-weight:700; color:var(--one); }
</style></head><body>
<header>
  <h1>%(title)s: the maze</h1>
  <div>%(stats)s</div>
  <div class="legend">
    <span>Numbers are the order you first entered each room. Position on the page means nothing.</span>
    <span>The letter at each end of a line is the exit to take from that room.</span>
    <span style="color:var(--one)">Orange arrow: one-way.</span>
    <span>&#8634; under a room: exits that bring you straight back to it.</span>
  </div>
</header>
<div id="wrap"><svg xmlns="http://www.w3.org/2000/svg" viewBox="%(vb)s" width="%(w)d" height="%(h)d">
<defs><marker id="arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
<path d="M0,0 L10,5 L0,10 z" fill="context-stroke"/></marker></defs>
%(body)s
</svg></div>
<h2>Where each exit leads</h2>
<div class="scroll"><table><thead><tr><th>From</th>%(head)s</tr></thead><tbody>
%(rows)s
</tbody></table></div>
</body></html>
"""

if __name__ == "__main__":
    graph, out = sys.argv[1], sys.argv[2]
    seed = int(sys.argv[3]) if len(sys.argv) > 3 else 3
    rooms, sub, maze, near, order, name, tag = build(graph, seed)
    best = None
    for s in range(80):                                    # keep the spread with the fewest crossed lines
        width = {r: (R if r in maze else 8 + len(tag[r]) * 3.4) for r in order}
        pos, pairs = layout(sub, order, s, width)
        segs = [tuple(sorted(p, key=order.index)) for p in pairs]

        def crosses(e, f):
            if set(e) & set(f):
                return False
            (a, b), (c, d) = (pos[e[0]], pos[e[1]]), (pos[f[0]], pos[f[1]])
            o = lambda p, q, r: (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0])
            return o(a, b, c) * o(a, b, d) < 0 and o(c, d, a) * o(c, d, b) < 0
        def near_line(e, r):                               # a room sitting on someone else's line
            (ax, ay), (bx, by), (px, py) = pos[e[0]], pos[e[1]], pos[r]
            t = ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / ((bx - ax) ** 2 + (by - ay) ** 2)
            return 0 < t < 1 and math.hypot(ax + t * (bx - ax) - px, ay + t * (by - ay) - py) < 46
        n = sum(crosses(e, f) for i, e in enumerate(segs) for f in segs[i + 1:])
        n += 4 * sum(near_line(e, r) for e in segs for r in order if r not in e)
        if best is None or n < best[0]:
            best = (n, pos, pairs)
    n, pos, pairs = best
    key = graph.split("/")[-1].replace("_graph.json", "")
    title = {"zork1": "Zork I", "lurkinghorror": "The Lurking Horror"}.get(key, key)
    open(out, "w").write(render(rooms, sub, maze, near, order, name, tag, pos, pairs, title))
    print("wrote %s: %d rooms, %d lines, %d crossings" % (out, len(order), len(pairs), n))
