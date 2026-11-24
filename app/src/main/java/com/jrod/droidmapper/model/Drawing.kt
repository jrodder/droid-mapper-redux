package com.jrod.droidmapper.model

import kotlin.math.abs

enum class LinkKind {
    /** One plain line: the two rooms really sit in that direction with nothing between. */
    STRAIGHT,
    /** A stair line between two plates on different levels. */
    STAIR,
    /** A short stub with a "to Room" label at each end, where a line would mislead. */
    STUB,
}

/** A pair of connected rooms and how it is drawn. [ab] leaves [a] for [b]; [ba] the other way. */
class DrawnLink(val a: String, val b: String, val ab: Passage?, val ba: Passage?, val kind: LinkKind)

class Drawing(val links: List<DrawnLink>, val loops: List<Passage>, val blocked: List<Passage>)

private fun Passage.climbs() = dir == Dir.UP || dir == Dir.DOWN

/**
 * How every passage of [map] is drawn on the floors map, given where the rooms are.
 * [lean] is how far one step south also shifts west on the page, in cells; a stairway whose
 * ends are more than [maxSideways] cells apart across the page is too slanted to draw and
 * becomes a pair of labels.
 */
fun drawingOf(map: GameMap, spot: Map<String, Spot>, lean: Float, maxSideways: Float = 2.5f): Drawing {
    val cells = spot.mapValues { Cell(it.value.x, it.value.y) }
    val takenBy = spot.values.groupBy({ it.plate }, { Cell(it.x, it.y) }).mapValues { it.value.toSet() }
    val pairs = LinkedHashMap<Set<String>, MutableList<Passage>>()
    val loops = ArrayList<Passage>()
    val blocked = ArrayList<Passage>()
    for (p in map.passages) {
        when {
            p.to == null -> blocked.add(p)
            p.to == p.from -> loops.add(p)
            p.from in spot && p.to in spot -> pairs.getOrPut(setOf(p.from, p.to)) { ArrayList() }.add(p)
        }
    }
    val links = pairs.map { (key, ps) ->
        val (a, b) = key.toList()
        val sa = spot.getValue(a)
        val sb = spot.getValue(b)
        val same = sa.plate == sb.plate
        // Prefer the passage the drawing already shows correctly, so a duplicate exit never bends the line.
        fun shown(p: Passage) = same && p.dir.isCompass && clear(cells, takenBy.getValue(sa.plate), p.from, p.to!!, p.dir)
        fun pick(from: String) = ps.filter { it.from == from }
            .let { l -> l.firstOrNull(::shown) ?: l.firstOrNull { it.dir.isCompass } ?: l.firstOrNull() }
        // On a stairway the up/down passage is the one to draw, whatever else joins the pair.
        fun stair(from: String) = ps.firstOrNull { it.from == from && it.climbs() }
        val sideways = abs((sa.x - sa.y * lean) - (sb.x - sb.y * lean))
        val kind = when {
            same && ps.any(::shown) -> LinkKind.STRAIGHT
            !same && ps.any { it.climbs() } && sa.level != sb.level && sideways <= maxSideways -> LinkKind.STAIR
            else -> LinkKind.STUB
        }
        if (ps.any { it.climbs() } && !same) DrawnLink(a, b, stair(a) ?: pick(a), stair(b) ?: pick(b), kind)
        else DrawnLink(a, b, pick(a), pick(b), kind)
    }
    return Drawing(links, loops, blocked)
}
