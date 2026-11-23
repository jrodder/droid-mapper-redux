#!/usr/bin/env python3
"""Extract the room/exit graph from the Zork I ZIL source (1dungeon.zil).

Usage: python3 -I extract_zork.py path/to/1dungeon.zil out.json

Each exit is a one-directional fact: (room, direction) -> outcome.
Outcome kinds:
  to        unconditional passage to another room
  cond      passage that only works once something is true (door open, flag set)
  blocked   the game prints a message; you never get anywhere
"""
import json
import re
import sys

DIRS = ["NORTH", "SOUTH", "EAST", "WEST", "NE", "NW", "SE", "SW",
        "UP", "DOWN", "IN", "OUT", "LAND"]

# Exits whose destination is computed by a routine in 1actions.zil.
# Resolved by reading those routines: (room, dir) -> (dest, conditional?)
PER = {
    ("GRATING-CLEARING", "DOWN"): ("GRATING-ROOM", True),   # grating must be open
    ("LIVING-ROOM", "DOWN"): ("CELLAR", True),              # rug moved, trap door open
    ("STUDIO", "UP"): ("KITCHEN", True),                    # chimney: carry <= 2 items + lamp
    ("MAZE-2", "DOWN"): ("MAZE-4", False),                  # MAZE-DIODES: one-way drops
    ("MAZE-7", "DOWN"): ("DEAD-END-1", False),
    ("MAZE-9", "DOWN"): ("MAZE-11", False),
    ("MAZE-12", "DOWN"): ("MAZE-5", False),
}


def main(src, out):
    text = open(src, encoding="latin-1").read()
    rooms = {}
    for m in re.finditer(r"^<ROOM ([A-Z0-9-]+)(.*?)^\s*\(FLAGS([^)]*)\)", text, re.S | re.M):
        name, body, flags = m.group(1), m.group(2), m.group(3)
        desc = re.search(r'\(DESC "([^"]*)"\)', body)
        exits = {}
        for d in DIRS:
            # (IN ROOMS) is the object-tree parent, not an exit
            found = [x.group(1).strip()
                     for x in re.finditer(r"^\s*\(%s\s+(.*)$" % d, body, re.M)
                     if x.group(1).strip() != "ROOMS)"]
            if not found:
                continue
            rest = found[0]
            # "IF FALSE-FLAG" can never be true: the exit only exists to print its message
            if rest.startswith('"') or rest.startswith("SORRY") or " IF FALSE-FLAG" in rest:
                exits[d] = {"kind": "blocked"}
            elif rest.startswith("PER"):
                if (name, d) not in PER:
                    raise SystemExit("unresolved PER exit: %s %s" % (name, d))
                dest, cond = PER[(name, d)]
                exits[d] = {"kind": "cond" if cond else "to", "to": dest}
            else:
                tm = re.match(r"TO ([A-Z0-9-]+)(\s+IF\b)?", rest)
                if not tm:
                    raise SystemExit("unparsed exit: %s %s %r" % (name, d, rest))
                exits[d] = {"kind": "cond" if tm.group(2) else "to", "to": tm.group(1)}
        rooms[name] = {
            "desc": desc.group(1) if desc else name,
            "dark": "ONBIT" not in flags,
            "maze": "MAZEBIT" in flags,
            "exits": exits,
        }
    for r, info in rooms.items():
        for d, e in info["exits"].items():
            if "to" in e and e["to"] not in rooms:
                raise SystemExit("exit to unknown room: %s %s -> %s" % (r, d, e["to"]))
    json.dump({"start": "WEST-OF-HOUSE", "rooms": rooms}, open(out, "w"), indent=1)
    print("rooms:", len(rooms), "exits:", sum(len(r["exits"]) for r in rooms.values()))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
