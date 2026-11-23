"""Where each area sits when all areas are shown together, kept steady.

An area stays where it was first put. One room per area (its oldest) is held
fixed on the page, so the area grows around it. A group released from an area
stays where it was drawn. A new area goes beside the area it was reached
from. Only when two areas would overlap does one move, and it is always the
newer one, pushed just far enough.
"""
import collections

GAP = 2                                 # empty cells kept between two areas


class Overview:
    def __init__(self):
        self.ref = {}                   # area -> room held fixed
        self.gpos = {}                  # room -> page position at the last update
        self.born = {}                  # area -> order of creation
        self.pushed = 0                 # times an existing area had to move aside

    def update(self, L, age, came_from=None):
        areas = collections.defaultdict(list)
        for r, a in L.isl.items():
            areas[a].append(r)
        origin, fresh = {}, []
        for a, rs in areas.items():
            self.born.setdefault(a, len(self.born))
            ref = self.ref.get(a)
            if ref not in rs or ref not in self.gpos:
                old = [r for r in rs if r in self.gpos]
                ref = min(old, key=age.get) if old else None
            if ref is None:
                fresh.append(a)
                continue
            self.ref[a] = ref
            origin[a] = (self.gpos[ref][0] - L.pos[ref][0], self.gpos[ref][1] - L.pos[ref][1])

        def box(a):
            xs = [L.pos[r][0] + origin[a][0] for r in areas[a]]
            ys = [L.pos[r][1] + origin[a][1] for r in areas[a]]
            return min(xs), min(ys), max(xs), max(ys)

        for a in fresh:                                  # beside the area it was reached from
            self.ref[a] = min(areas[a], key=age.get)
            host = L.isl.get(came_from)
            if host in origin and host != a:
                x0, y0, x1, y1 = box(host)
                origin[a] = (x1 + GAP + 1 - min(L.pos[r][0] for r in areas[a]),
                             y0 - min(L.pos[r][1] for r in areas[a]))
            elif origin:
                right = max(box(b)[2] for b in origin)
                top = min(box(b)[1] for b in origin)
                origin[a] = (right + GAP + 1 - min(L.pos[r][0] for r in areas[a]),
                             top - min(L.pos[r][1] for r in areas[a]))
            else:
                origin[a] = (0, 0)
        moved = set()
        for _ in range(400):                             # newer area steps aside until nothing overlaps
            hit = None
            ids = sorted(origin, key=self.born.get)
            for i, a in enumerate(ids):
                ax0, ay0, ax1, ay1 = box(a)
                for b in ids[i + 1:]:
                    bx0, by0, bx1, by1 = box(b)
                    if ax0 <= bx1 + GAP and bx0 <= ax1 + GAP and ay0 <= by1 + GAP and by0 <= ay1 + GAP:
                        hit = (b, ax1 + GAP + 1 - bx0)
                        break
                if hit:
                    break
            if not hit:
                break
            b, dx = hit
            origin[b] = (origin[b][0] + dx, origin[b][1])
            if b not in fresh:
                moved.add(b)
        self.pushed += len(moved)
        self.gpos = {r: (L.pos[r][0] + origin[a][0], L.pos[r][1] + origin[a][1])
                     for a, rs in areas.items() for r in rs}
        return self.gpos


def draw_kinds(L, walked):
    """How each pair of connected rooms is drawn on the flat map.

    straight  one clean straight line
    curve     a bent line, allowed only between neighbouring rooms where it crosses nothing
    stub      a short labelled stub at each end ("to Room"), same area
    label     the same, between two different areas
    """
    from layout_sim import VEC, clear
    pairs = collections.OrderedDict()
    for a, d, b in walked:
        if a != b and a in L.pos and b in L.pos:
            pairs.setdefault(frozenset((a, b)), []).append((a, d, b))
    taken = collections.defaultdict(set)
    for r, p in L.pos.items():
        taken[L.isl[r]].add(p)
    kinds, drawn = {}, collections.defaultdict(list)
    bent = []
    for key, links in pairs.items():
        a, b = tuple(key)
        if L.isl[a] != L.isl[b]:
            kinds[key] = "label"
            continue
        area = L.isl[a]
        near = max(abs(L.pos[a][0] - L.pos[b][0]), abs(L.pos[a][1] - L.pos[b][1])) <= 1
        if any(d in VEC and clear(L.pos, taken[area], x, y, VEC[d]) for x, d, y in links) or (
                near and all(d not in VEC for _, d, _ in links)):
            kinds[key] = "straight"
            drawn[area].append((L.pos[a], L.pos[b]))
        else:
            bent.append(key)

    def crosses(p, q, r, s):
        o = lambda u, v, w: (v[0] - u[0]) * (w[1] - u[1]) - (v[1] - u[1]) * (w[0] - u[0])
        return o(p, q, r) * o(p, q, s) < 0 and o(r, s, p) * o(r, s, q) < 0

    def behind(p, q, cells):
        for c in cells:
            if c in (p, q):
                continue
            t = ((c[0] - p[0]) * (q[0] - p[0]) + (c[1] - p[1]) * (q[1] - p[1])) / (
                (q[0] - p[0]) ** 2 + (q[1] - p[1]) ** 2)
            if 0 < t < 1 and abs(p[0] + t * (q[0] - p[0]) - c[0]) < 0.5 and abs(p[1] + t * (q[1] - p[1]) - c[1]) < 0.45:
                return True
        return False

    for key in bent:
        a, b = tuple(key)
        area, p, q = L.isl[a], L.pos[a], L.pos[b]
        # a curve is only readable between neighbouring rooms; anything longer swoops
        # around the map, so it becomes a pair of labels
        far = max(abs(p[0] - q[0]), abs(p[1] - q[1])) > 1
        if far or behind(p, q, taken[area]) or any(crosses(p, q, r, s) for r, s in drawn[area]):
            kinds[key] = "stub"
        else:
            kinds[key] = "curve"
            drawn[area].append((p, q))
    return kinds
