package com.jrod.droidmapper.model

/**
 * The map with every tangle folded into one box.
 *
 * [map] is what the floors map draws; [boxOf] says which box each folded room went into;
 * [members] lists the real rooms inside each box, in the order they were found.
 */
class Folded(val map: GameMap, val boxOf: Map<String, String>, val members: Map<String, List<Room>>) {
    fun fold(roomId: String): String = boxOf[roomId] ?: roomId
}

private const val MIN_TANGLE = 3

/**
 * Which rooms are part of a tangle: a cluster that cannot be drawn on a grid in any useful
 * way. Mazes are the famous case, but nothing here knows the word, and the player flags
 * nothing. The rule uses only room names and walked moves:
 *
 *  - a room is tangled when at least two of its walked exits are known not to come back
 *    the way they went (or lead straight back to the room), and those are at least as
 *    many as its well-behaved exits;
 *  - three or more connected tangled rooms with the same name are a tangle;
 *  - once a tangle exists, every same-named room connected to it belongs to it.
 *
 * Returns each such room mapped to the first-found room of its tangle.
 */
fun tangleBoxes(map: GameMap): Map<String, String> {
    val walked = map.passages.filter { !it.assumed && it.to != null }
    val out = walked.groupBy { it.from }
    fun tangled(r: String): Boolean {
        var good = 0
        var bad = 0
        for (p in out[r].orEmpty()) {
            if (p.to == r) { bad++; continue }
            // what is known about coming straight back: walked, or tried and blocked
            val back = map.exit(p.to!!, p.dir.opposite)?.takeIf { !it.assumed } ?: continue
            if (back.to == r) good++ else bad++
        }
        return bad >= 2 && bad >= good
    }
    val near = HashMap<String, MutableSet<String>>()
    for (p in walked) if (p.to != p.from) {
        near.getOrPut(p.from) { HashSet() }.add(p.to!!)
        near.getOrPut(p.to) { HashSet() }.add(p.from)
    }
    fun key(id: String) = map.room(id)?.name?.trim()?.lowercase().orEmpty()
    val order = map.rooms.withIndex().associate { it.value.id to it.index }
    val tangledRooms = map.rooms.map { it.id }.filter { key(it).isNotEmpty() && tangled(it) }.toSet()
    val boxOf = HashMap<String, String>()
    for (seed in tangledRooms) {
        if (seed in boxOf) continue
        fun grow(from: Set<String>, ok: (String) -> Boolean): Set<String> {
            val seen = from.toHashSet()
            val stack = ArrayDeque(from)
            while (stack.isNotEmpty()) {
                for (y in near[stack.removeLast()].orEmpty()) {
                    if (y !in seen && key(y) == key(seed) && ok(y)) { seen.add(y); stack.add(y) }
                }
            }
            return seen
        }
        val core = grow(setOf(seed)) { it in tangledRooms }
        if (core.size < MIN_TANGLE) continue
        val all = grow(core) { true }
        val box = all.minBy { order.getValue(it) }
        for (r in all) boxOf[r] = box
    }
    return boxOf
}

/** [map] with every tangle replaced by one box named like "Maze x15". */
fun foldTangles(map: GameMap): Folded {
    val boxOf = tangleBoxes(map)
    if (boxOf.isEmpty()) return Folded(map, emptyMap(), emptyMap())
    val members = map.rooms.filter { it.id in boxOf }.groupBy { boxOf.getValue(it.id) }
    fun fold(id: String) = boxOf[id] ?: id
    val rooms = map.rooms.mapNotNull { r ->
        when {
            r.id !in boxOf -> r
            boxOf[r.id] == r.id -> Room(r.id, "${r.name.trim()} x${members.getValue(r.id).size}")
            else -> null
        }
    }
    val seen = HashSet<Triple<String, Dir, String?>>()
    val passages = map.passages.mapNotNull { p ->
        val from = fold(p.from)
        val to = p.to?.let(::fold)
        when {
            p.from in boxOf && (to == null || to == from) -> null      // inside the tangle: not the map's business
            !seen.add(Triple(from, p.dir, to)) -> null
            else -> p.copy(from = from, to = to)
        }
    }
    return Folded(map.copy(rooms = rooms, passages = passages, current = fold(map.current)), boxOf, members)
}
