#!/usr/bin/env python3
"""Extract a room/exit graph from any Infocom ZIL source tree.

Usage: python3 -I extract_zil.py SOURCE_DIR START-ROOM out.json [overrides.json]

Generic version of extract_zork.py. Exits whose destination is computed by a
routine ("PER") are resolved automatically where the routine names exactly one
room, or maps the current room to a destination; the rest are left out and
counted, since their destination cannot be known without playing.
"""
import glob
import json
import os
import re
import sys

DIRS = ["NORTH", "SOUTH", "EAST", "WEST", "NE", "NW", "SE", "SW",
        "UP", "DOWN", "IN", "OUT", "LAND"]


def blocks(text, opener):
    """Yield (name, body) for every <OPENER NAME ...> form, matching brackets."""
    for m in re.finditer(r"^<%s ([A-Z0-9?-]+)" % opener, text, re.M):
        i, depth, in_str = m.start(), 0, False
        while i < len(text):
            ch = text[i]
            if in_str:
                if ch == "\\":
                    i += 1
                elif ch == '"':
                    in_str = False
            elif ch == '"':
                in_str = True
            elif ch == "<":
                depth += 1
            elif ch == ">":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        yield m.group(1), text[m.end():i]


def main(src, start, out, overrides=None):
    text = "\n".join(open(f, encoding="latin-1").read()
                     for f in sorted(glob.glob(os.path.join(src, "*.zil"))))
    raw = dict(blocks(text, "ROOM"))
    routines = dict(blocks(text, "ROUTINE"))
    rooms, unresolved = {}, 0
    for name, body in raw.items():
        desc = re.search(r'\(DESC "([^"]*)"\)', body)
        flags = re.search(r"\(FLAGS([^)]*)\)", body)
        flags = flags.group(1) if flags else ""
        exits = {}
        for d in DIRS:
            found = [x.group(1).strip() for x in re.finditer(r"^\s*\(%s\s+(.*)$" % d, body, re.M)
                     if x.group(1).strip() != "ROOMS)"]
            if not found:
                continue
            rest = found[0]
            if rest.startswith('"') or rest.startswith("SORRY") or " IF FALSE-FLAG" in rest:
                exits[d] = {"kind": "blocked"}
            elif rest.startswith("PER"):
                fn = re.match(r"PER ([A-Z0-9?-]+)", rest).group(1)
                rb = routines.get(fn, "")
                by_here = dict(re.findall(r"<EQUAL\? ,HERE ,([A-Z0-9-]+)>\s+,([A-Z0-9-]+)\)", rb))
                named = {a for a in re.findall(r",([A-Z0-9-]+)", rb) if a in raw and a != name}
                if by_here.get(name) in raw:
                    exits[d] = {"kind": "to", "to": by_here[name]}
                elif len(named) == 1:
                    exits[d] = {"kind": "cond", "to": named.pop()}
                elif len(named) >= 3:
                    # one exit, several possible destinations (an elevator): not a
                    # passage the map can draw as a line, but worth knowing about
                    exits[d] = {"kind": "varies", "any": sorted(named)}
                else:
                    unresolved += 1
            else:
                tm = re.match(r"TO ([A-Z0-9-]+)(\s+IF\b)?", rest)
                if tm and tm.group(1) in raw:
                    exits[d] = {"kind": "cond" if tm.group(2) else "to", "to": tm.group(1)}
                else:
                    unresolved += 1
        label = desc.group(1) if desc else name
        rooms[name] = {
            "desc": label,
            "dark": "ONBIT" not in flags,
            "maze": "MAZEBIT" in flags or bool(re.search(r"\bmaze\b", label, re.I)),
            "exits": exits,
        }
    # exits resolved by hand from the game's routines replace whatever was guessed
    if overrides:
        for room, exits in json.load(open(overrides)).items():
            if room.startswith("_"):
                continue
            for d, e in exits.items():
                if e.get("to") not in rooms:
                    raise SystemExit("override %s %s points at unknown room %s" % (room, d, e.get("to")))
                rooms[room]["exits"][d] = e
    # a name shared by many rooms is a maze whether or not the game calls it one
    # (a player would flag it after the third identical "Wet Tunnel")
    shared = {}
    for info in rooms.values():
        shared[info["desc"]] = shared.get(info["desc"], 0) + 1
    for info in rooms.values():
        if shared[info["desc"]] >= 8:
            info["maze"] = True
    if start not in rooms:
        raise SystemExit("start room %s not found" % start)
    json.dump({"start": start, "rooms": rooms}, open(out, "w"), indent=1)
    print("%s: rooms %d, exits %d, routine-driven exits left out %d" % (
        os.path.basename(out), len(rooms), sum(len(r["exits"]) for r in rooms.values()), unresolved))


if __name__ == "__main__":
    main(*sys.argv[1:5])
