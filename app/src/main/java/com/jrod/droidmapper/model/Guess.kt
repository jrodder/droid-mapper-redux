package com.jrod.droidmapper.model

/**
 * The player went [dir] from the current room and arrived somewhere they have been before.
 * Which room is it most likely to be? Best guess first.
 *
 * Three clues, all things the app already has:
 *  - a room drawn exactly where that exit points (or further along the same line),
 *  - a room whose opposite side is still free, so the way back could be the plain reverse,
 *  - how few known passages away the room is.
 */
fun guessArrival(map: GameMap, placed: Map<String, Placed>, dir: Dir, limit: Int = 3): List<Room> {
    val here = map.current
    val at = placed[here]
    val steps = HashMap<String, Int>().apply { put(here, 0) }
    val queue = ArrayDeque(listOf(here))
    while (queue.isNotEmpty()) {
        val r = queue.removeFirst()
        val near = map.passages.filter { it.to != null && (it.from == r || it.to == r) }
            .map { if (it.from == r) it.to!! else it.from }
        for (n in near) if (n !in steps) { steps[n] = steps.getValue(r) + 1; queue.add(n) }
    }

    fun score(r: Room): Int {
        var s = 0
        val p = placed[r.id]
        if (dir.isCompass && at != null && p != null && p.area == at.area) {
            val dx = p.x - at.x
            val dy = p.y - at.y
            if (dx == dir.dx && dy == dir.dy) s += 4                                  // sits where the exit points
            else if (dx * dir.dy == dy * dir.dx && dx * dir.dx + dy * dir.dy > 0) s += 2   // further along that line
        }
        val back = map.exit(r.id, dir.opposite)
        if (back == null || back.to == here) s += 3                                   // the way back could be the reverse
        return s
    }

    return map.rooms.filter { it.id != here }
        .sortedWith(compareByDescending<Room> { score(it) }.thenBy { steps[it.id] ?: 999 })
        .take(limit)
}
