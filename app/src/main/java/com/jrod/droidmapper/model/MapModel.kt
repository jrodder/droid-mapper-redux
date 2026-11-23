package com.jrod.droidmapper.model

import kotlinx.serialization.Serializable

/** The twelve ways a player can say they moved. Only the eight compass ones say anything about the page. */
@Serializable
enum class Dir(val dx: Int, val dy: Int, val label: String) {
    N(0, -1, "N"), NE(1, -1, "NE"), E(1, 0, "E"), SE(1, 1, "SE"),
    S(0, 1, "S"), SW(-1, 1, "SW"), W(-1, 0, "W"), NW(-1, -1, "NW"),
    UP(0, 0, "U"), DOWN(0, 0, "D"), IN(0, 0, "In"), OUT(0, 0, "Out");

    val isCompass: Boolean get() = dx != 0 || dy != 0

    /** The direction as a player would say it. */
    val word: String
        get() = when (this) {
            N -> "north"; NE -> "northeast"; E -> "east"; SE -> "southeast"
            S -> "south"; SW -> "southwest"; W -> "west"; NW -> "northwest"
            UP -> "up"; DOWN -> "down"; IN -> "in"; OUT -> "out"
        }

    val opposite: Dir
        get() = when (this) {
            N -> S; S -> N; E -> W; W -> E
            NE -> SW; SW -> NE; NW -> SE; SE -> NW
            UP -> DOWN; DOWN -> UP; IN -> OUT; OUT -> IN
        }
}

@Serializable
data class Room(
    val id: String,
    val name: String,
    /** What the game says about the room, in the player's words or pasted in. */
    val description: String = "",
    /** What is lying here. Shown beside the room on the map, as printed maps do. */
    val items: String = "",
)

/**
 * One fact: leaving [from] by [dir] leads to [to].
 *
 * [to] is null when the player tried that way and could not go.
 * [assumed] marks the way back the app pencilled in when the player walked the other
 * direction: every passage is taken to be two-way until the player says otherwise.
 * [oneWay] is only ever set by the player.
 */
@Serializable
data class Passage(
    val from: String,
    val dir: Dir,
    val to: String?,
    val assumed: Boolean = false,
    val oneWay: Boolean = false,
)

/**
 * Everything the player has told the app. Nothing here says where a room is drawn:
 * positions are worked out from these facts every time (see [layoutOf]).
 *
 * The order of [passages] is the order the facts were learned, and the layout replays
 * them in that order, so a fact learned later can never rearrange how an earlier part
 * of the map was first laid out.
 */
@Serializable
data class GameMap(
    val name: String,
    val rooms: List<Room>,
    val passages: List<Passage> = emptyList(),
    val current: String,
) {
    fun room(id: String): Room? = rooms.firstOrNull { it.id == id }
    fun exit(from: String, dir: Dir): Passage? = passages.firstOrNull { it.from == from && it.dir == dir }
    fun exitsOf(id: String): List<Passage> = passages.filter { it.from == id }

    companion object {
        fun starting(mapName: String, firstRoom: String): GameMap =
            GameMap(mapName, listOf(Room("r1", firstRoom)), current = "r1")
    }
}

private fun GameMap.nextRoomId(): String =
    "r" + ((rooms.mapNotNull { it.id.removePrefix("r").toIntOrNull() }.maxOrNull() ?: 0) + 1)

/**
 * The player went [dir] from the current room and is now in the known room [destId].
 *
 * - Nothing recorded that way yet: the passage is recorded, and so is an assumed way back.
 * - The recorded passage already leads there: it is followed, and an assumed one becomes
 *   a confirmed one.
 * - The recorded passage led somewhere else: the player's report wins and it is re-pointed.
 */
fun GameMap.walkTo(dir: Dir, destId: String): GameMap {
    require(room(destId) != null) { "walkTo: unknown room $destId" }
    val here = current
    val old = exit(here, dir)
    if (old != null && old.to == destId && !old.assumed) return copy(current = destId)
    // A newly learned or newly confirmed fact goes to the end: it is the latest thing known.
    var facts = passages.filterNot { it.from == here && it.dir == dir } + Passage(here, dir, destId)
    val back = dir.opposite
    if (destId != here && facts.none { it.from == destId && it.dir == back }) {
        facts = facts + Passage(destId, back, here, assumed = true)
    }
    return copy(passages = facts, current = destId)
}

/** The player went [dir] and is somewhere new, which they call [name]. */
fun GameMap.walkToNew(dir: Dir, name: String): GameMap {
    val id = nextRoomId()
    return copy(rooms = rooms + Room(id, name.trim())).walkTo(dir, id)
}

/**
 * The player tried [dir] from the current room and could not go.
 *
 * If the app had only assumed that way led back somewhere, the assumption was wrong:
 * the passage that created it is marked one-way.
 */
fun GameMap.cannotGo(dir: Dir): GameMap {
    val here = current
    val old = exit(here, dir)
    if (old != null && !old.assumed && old.to != null) return this      // the player has walked it before
    var facts = passages.filterNot { it.from == here && it.dir == dir }
    if (old != null && old.assumed && old.to != null) {
        facts = facts.map { p ->
            if (p.from == old.to && p.to == here && p.dir == dir.opposite) p.copy(oneWay = true) else p
        }
    }
    return copy(passages = facts + Passage(here, dir, null))
}

/** Died, restored, teleported: the player is simply somewhere else now. No passage is recorded. */
fun GameMap.jumpTo(roomId: String): GameMap = if (room(roomId) == null) this else copy(current = roomId)

fun GameMap.jumpToNew(name: String): GameMap {
    val id = nextRoomId()
    return copy(rooms = rooms + Room(id, name.trim()), current = id)
}

fun GameMap.rename(roomId: String, name: String): GameMap =
    copy(rooms = rooms.map { if (it.id == roomId) it.copy(name = name.trim()) else it })

/** Save a room's card: its name, description and items. */
fun GameMap.editRoom(roomId: String, name: String, description: String, items: String): GameMap =
    copy(rooms = rooms.map {
        if (it.id == roomId) it.copy(name = name.trim(), description = description.trim(), items = items.trim()) else it
    })

/** Mark the passage leaving [from] by [dir] one-way (dropping any way back the app had assumed), or clear the mark. */
fun GameMap.setOneWay(from: String, dir: Dir, oneWay: Boolean): GameMap {
    val p = exit(from, dir) ?: return this
    val to = p.to ?: return this
    var facts = passages.map { if (it === p) it.copy(oneWay = oneWay) else it }
    val back = dir.opposite
    facts = if (oneWay) {
        facts.filterNot { it.from == to && it.dir == back && it.to == from && it.assumed }
    } else if (facts.none { it.from == to && it.dir == back }) {
        facts + Passage(to, back, from, assumed = true)
    } else facts
    return copy(passages = facts)
}

/** Names as drawn: rooms that share a name are numbered in the order they were found. */
fun GameMap.displayNames(): Map<String, String> {
    val byName = rooms.groupBy { it.name.trim().lowercase() }
    return rooms.associate { r ->
        val twins = byName.getValue(r.name.trim().lowercase())
        val base = r.name.trim().ifBlank { "(unnamed)" }
        r.id to if (twins.size == 1) base else "$base (${twins.indexOf(r) + 1})"
    }
}
