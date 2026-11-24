package com.jrod.droidmapper.model

import kotlin.math.max
import kotlin.math.min

/**
 * Where a room is drawn on the floors map: which plate it stands on, how many levels that
 * plate is above (+) or below (-) the start, and its cell on that level's grid.
 */
data class Spot(val plate: Int, val level: Int, val x: Int, val y: Int)

/**
 * The map as stacked floors, the way Infocom drew The Lurking Horror: every set of rooms
 * you can walk between on the level is a plate, and going up or down leads to another
 * plate drawn above or below.
 *
 *  - Plates are the areas of the flat layout with stairs never set down beside a room, so
 *    a room reached only by up or down always starts a plate of its own.
 *  - "Up" means higher and "down" means lower, by one level or more. Each up/down passage
 *    is honoured unless it contradicts the ones before it; then stairways are shortened as
 *    far as they will go.
 *  - A plate is put directly above or below the room it is reached from when it fits, and
 *    slid sideways only as far as needed to keep two plates on one level apart.
 *
 * A port of research/stack_view.py.
 */
fun floorsOf(map: GameMap): Map<String, Spot> {
    val placed = layoutOf(map, dock = false)
    val plateOf = placed.mapValues { it.value.area }
    val members = placed.keys.groupBy { plateOf.getValue(it) }
    val cell = placed.mapValues { Cell(it.value.x, it.value.y) }
    val start = plateOf.getValue(map.rooms.first().id)
    val cross = map.passages.filter { p ->
        p.to != null && !p.assumed && p.from in placed && p.to in placed && plateOf[p.from] != plateOf[p.to]
    }
    fun climbs(p: Passage) = p.dir == Dir.UP || p.dir == Dir.DOWN

    // Plates joined by a level passage (compass, in or out) share a level.
    val head = HashMap<Int, Int>().apply { for (p in members.keys) put(p, p) }
    fun top(p: Int): Int { var x = p; while (head.getValue(x) != x) x = head.getValue(x); return x }
    for (p in cross) if (!climbs(p)) {
        val a = top(plateOf.getValue(p.from)); val b = top(plateOf.getValue(p.to!!))
        if (a != b) head[max(a, b)] = min(a, b)
    }

    // Each climb says "that group of plates is above this one", unless it contradicts earlier ones.
    val above = HashMap<Int, MutableSet<Int>>()
    fun reaches(from: Int, to: Int): Boolean {
        val seen = HashSet<Int>()
        val stack = ArrayDeque(listOf(from))
        while (stack.isNotEmpty()) {
            val x = stack.removeLast()
            if (x == to) return true
            if (seen.add(x)) above[x]?.let(stack::addAll)
        }
        return false
    }
    for (p in cross) if (climbs(p)) {
        val f = top(plateOf.getValue(p.from)); val t = top(plateOf.getValue(p.to!!))
        val lo = if (p.dir == Dir.UP) f else t
        val hi = if (p.dir == Dir.UP) t else f
        if (lo == hi || above[lo]?.contains(hi) == true || reaches(hi, lo)) continue
        above.getOrPut(lo) { LinkedHashSet() }.add(hi)
    }
    val edges = above.flatMap { (lo, his) -> his.map { lo to it } }
    val z = HashMap<Int, Int>().apply { for (p in members.keys) put(top(p), 0) }
    repeat(z.size + 1) { for ((lo, hi) in edges) z[hi] = max(z.getValue(hi), z.getValue(lo) + 1) }
    // Shorten stairways: move everything hanging rigidly off one end a level toward the other.
    fun total() = edges.sumOf { (lo, hi) -> z.getValue(hi) - z.getValue(lo) }
    var improved = true
    while (improved) {
        improved = false
        search@ for ((lo, hi) in edges) {
            if (z.getValue(hi) - z.getValue(lo) < 2) continue
            for ((from, step) in listOf(lo to 1, hi to -1)) {
                val group = hashSetOf(from)
                val stack = ArrayDeque(listOf(from))
                while (stack.isNotEmpty()) {
                    val x = stack.removeLast()
                    for ((a, b) in edges) if (z.getValue(b) - z.getValue(a) == 1 && (x == a || x == b)) {
                        val y = if (x == a) b else a
                        if (group.add(y)) stack.add(y)
                    }
                }
                if ((if (from == lo) hi else lo) in group) continue
                val before = total()
                for (x in group) z[x] = z.getValue(x) + step
                if (edges.all { (a, b) -> z.getValue(b) - z.getValue(a) >= 1 } && total() < before) {
                    improved = true
                    break@search
                }
                for (x in group) z[x] = z.getValue(x) - step
            }
        }
    }
    val base = z.getValue(top(start))
    val level = members.keys.associateWith { z.getValue(top(it)) - base }

    // Where each plate sits on its level.
    val off = LinkedHashMap<Int, Cell>()
    fun box(p: Int): IntArray {
        val o = off.getValue(p)
        val cs = members.getValue(p).map { cell.getValue(it) }
        return intArrayOf(cs.minOf { it.x } + o.x, cs.minOf { it.y } + o.y, cs.maxOf { it.x } + o.x, cs.maxOf { it.y } + o.y)
    }
    fun settle(p: Int) {
        val home = off.getValue(p)
        for (step in 0 until 600) {
            off[p] = Cell(home.x + ((step + 1) / 2) * (if (step % 2 == 1) 1 else -1), home.y)
            val b = box(p)
            val hit = off.keys.any { q ->
                q != p && level[q] == level[p] && box(q).let { o ->
                    b[0] <= o[2] + 1 && o[0] <= b[2] + 1 && b[1] <= o[3] + 1 && o[1] <= b[3] + 1
                }
            }
            if (!hit) return
        }
    }
    off[start] = Cell(0, 0)
    while (off.size < members.size) {
        // stairs first, so a plate lands directly above or below the room it is reached from
        val link = cross.firstOrNull { climbs(it) && (plateOf.getValue(it.from) in off) != (plateOf.getValue(it.to!!) in off) }
            ?: cross.firstOrNull { (plateOf.getValue(it.from) in off) != (plateOf.getValue(it.to!!) in off) }
        if (link == null) {             // reached only by means no passage describes: set it down to one side
            val p = members.keys.first { it !in off }
            val far = off.keys.maxOf { box(it)[2] } + 4
            off[p] = Cell(far - members.getValue(p).minOf { cell.getValue(it).x }, 0)
            settle(p)
            continue
        }
        val outward = plateOf.getValue(link.from) in off
        val known = if (outward) link.from else link.to!!
        val fresh = if (outward) link.to!! else link.from
        val a = plateOf.getValue(known)
        val b = plateOf.getValue(fresh)
        var tx = cell.getValue(known).x + off.getValue(a).x
        var ty = cell.getValue(known).y + off.getValue(a).y
        if (!climbs(link)) {            // same level: beside it, on the side the passage leads
            val sign = if (outward) 2 else -2
            tx += sign * (if (link.dir.isCompass) link.dir.dx else 1)
            ty += sign * link.dir.dy
        }
        off[b] = Cell(tx - cell.getValue(fresh).x, ty - cell.getValue(fresh).y)
        settle(b)
    }
    return placed.mapValues { (r, p) ->
        val o = off.getValue(p.area)
        Spot(p.area, level.getValue(p.area), p.x + o.x, p.y + o.y)
    }
}

/**
 * How many rows each plate is drawn further up the page than its cells say, so that a level
 * takes no more room than it needs.
 *
 * Plates on one level are set down beside the rooms their stairs lead from, which can leave
 * them many rows apart with nothing between. Sliding a plate straight up or down the page
 * keeps every stairway exactly as upright as it was. Plates joined by a level passage move
 * together; the biggest group on a level stays put and the others close up to it, never
 * nearer than a clear row from another plate.
 *
 * [lean] is how far one step south also shifts west on the page, and [pad] how far a plate
 * reaches beyond its outermost rooms, both in cells.
 */
fun plateRises(map: GameMap, spot: Map<String, Spot>, lean: Float, pad: Float): Map<Int, Int> {
    val plates = spot.values.groupBy { it.plate }
    val head = HashMap<Int, Int>().apply { for (p in plates.keys) put(p, p) }
    fun top(p: Int): Int { var x = p; while (head.getValue(x) != x) x = head.getValue(x); return x }
    for (p in map.passages) {
        val a = spot[p.from] ?: continue
        val b = spot[p.to ?: continue] ?: continue
        if (a.plate == b.plate || a.level != b.level || p.dir == Dir.UP || p.dir == Dir.DOWN) continue
        val x = top(a.plate); val y = top(b.plate)
        if (x != y) head[max(x, y)] = min(x, y)
    }
    class Box(val y0: Int, val y1: Int, val left: Float, val right: Float)
    fun boxOf(p: Int): Box {
        val ss = plates.getValue(p)
        val y0 = ss.minOf { it.y }; val y1 = ss.maxOf { it.y }
        return Box(y0, y1, ss.minOf { it.x } - pad - (y1 + pad) * lean, ss.maxOf { it.x } + pad - (y0 - pad) * lean)
    }
    val rise = HashMap<Int, Int>()
    for (onLevel in plates.keys.groupBy { plates.getValue(it).first().level }.values) {
        val groups = onLevel.groupBy(::top).values
            .sortedWith(compareByDescending<List<Int>> { g -> g.sumOf { plates.getValue(it).size } }.thenBy { it.min() })
        val done = ArrayList<Pair<Box, Int>>()
        var lo = 0; var hi = 0
        for ((i, group) in groups.withIndex()) {
            val boxes = group.map(::boxOf)
            val g0 = boxes.minOf { it.y0 }; val g1 = boxes.maxOf { it.y1 }
            var best = 0
            if (i > 0) {
                fun free(k: Int) = boxes.none { b ->
                    done.any { (o, ok) ->
                        b.y0 - k <= o.y1 - ok + 1 && o.y0 - ok <= b.y1 - k + 1 && b.left < o.right + 0.3f && o.left < b.right + 0.3f
                    }
                }
                fun extent(k: Int) = max(hi, g1 - k) - min(lo, g0 - k)
                best = (min(g0 - hi, 0)..max(g1 - lo, 0)).filter(::free)
                    .minWithOrNull(compareBy<Int> { extent(it) }.thenBy { kotlin.math.abs(it) }) ?: 0
                lo = min(lo, g0 - best); hi = max(hi, g1 - best)
            } else { lo = g0; hi = g1 }
            for (p in group) rise[p] = best
            boxes.forEach { done.add(it to best) }
        }
    }
    return rise
}
