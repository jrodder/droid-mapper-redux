package com.jrod.droidmapper.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Plays a whole game through the app's own code, one move at a time, the way a player would:
 * walk known passages to get somewhere, tap a direction, say where you arrived. After every
 * single move the map is folded, laid out as floors and its lines decided, exactly as the
 * screen does it. The finished map is written out so it can be loaded into the app and looked at.
 *
 * The player here always recognises a room they have been in, and tries the way back the
 * app assumed once every real exit of a room is used up, reporting "couldn't go" if the game
 * refuses.
 */
class PlaythroughTest {
    private class Truth(val name: String, val exits: Map<Dir, String?>)

    private fun play(game: String, title: String): String {
        val root = Json.parseToJsonElement(javaClass.getResource("/${game}_play.json")!!.readText()).jsonObject
        val truth = root.getValue("rooms").jsonObject.mapValues { (_, v) ->
            Truth(
                v.jsonObject.getValue("name").jsonPrimitive.content,
                v.jsonObject.getValue("exits").jsonObject.entries.associate { (d, to) ->
                    Dir.valueOf(d) to (if (to is JsonNull) null else to.jsonPrimitive.content)
                },
            )
        }
        val start = root.getValue("start").jsonPrimitive.content
        var map = GameMap.starting(title, truth.getValue(start).name)
        val id = hashMapOf(start to "r1")                       // the game's room -> the app's room
        var followed = 0; var refused = 0; var teleported = 0; var slowest = 0L
        val stages = ArrayList<String>()
        val moves = root.getValue("moves").jsonArray

        fun walked(room: String, d: Dir) = map.exit(room, d)?.let { !it.assumed && it.to != null } == true

        for ((n, m) in moves.withIndex()) {
            val frm = m.jsonArray[0].jsonPrimitive.content
            val d = m.jsonArray[1].jsonPrimitive.content
            val to = m.jsonArray[2].jsonPrimitive.content
            assertEquals("is-new flag at move $n", m.jsonArray[3].jsonPrimitive.boolean, to !in id)
            if (d == "JUMP") {
                map = map.jumpToNew(truth.getValue(to).name)
                id[to] = map.current
            } else {
                // get to where the move starts, along passages already walked
                val goal = id.getValue(frm)
                if (map.current != goal) {
                    val prev = HashMap<String, Passage>()
                    val queue = ArrayDeque(listOf(map.current))
                    val seen = hashSetOf(map.current)
                    while (queue.isNotEmpty() && goal !in seen) {
                        val r = queue.removeFirst()
                        for (p in map.exitsOf(r)) if (!p.assumed && p.to != null && seen.add(p.to!!)) { prev[p.to!!] = p; queue.add(p.to!!) }
                    }
                    if (goal in prev) {
                        val path = generateSequence(prev[goal]) { prev[it.from] }.toList().reversed()
                        for (p in path) { map = map.walkTo(p.dir, p.to!!); followed++ }
                    } else { map = map.jumpTo(goal); teleported++ }
                }
                assertEquals(goal, map.current)
                val dir = Dir.valueOf(d)
                if (to in id) map = map.walkTo(dir, id.getValue(to))
                else { map = map.walkToNew(dir, truth.getValue(to).name); id[to] = map.current }
                assertEquals("where move $n ($frm $d) led", id.getValue(to), map.exit(goal, dir)!!.to)
            }
            // Out of real exits in a room? Then, while standing in it, try the ways back the
            // app only assumed. Checked for the room just left as well as the one arrived in,
            // since a room's last real exit is used at the moment of leaving it.
            val arrived = map.current
            for (room in listOfNotNull(frm.takeIf { d != "JUMP" }, to)) {
                val at = id.getValue(room)
                val real = truth.getValue(room).exits
                if (!real.filterValues { it != null }.keys.all { walked(at, it) }) continue
                for (p in map.exitsOf(at).filter { it.assumed }) {
                    if (real[p.dir] == null) { map = map.jumpTo(at).cannotGo(p.dir); refused++ }
                }
            }
            map = map.jumpTo(arrived)
            // what the screen does after every move
            val t0 = System.nanoTime()
            val folded = foldTangles(map)
            val spots = floorsOf(folded.map)
            val drawing = drawingOf(folded.map, spots, lean = 18f / 134f)
            slowest = maxOf(slowest, (System.nanoTime() - t0) / 1_000_000)
            assertEquals("every drawn room has a place", folded.map.rooms.map { it.id }.toSet(), spots.keys)
            assertEquals("no two rooms share a cell", spots.size, spots.values.map { Triple(it.level, it.x, it.y) }.toSet().size)
            if ((n + 1) % (moves.size / 5) == 0 || n == moves.size - 1) {
                val k = drawing.links.groupingBy { it.kind }.eachCount()
                stages.add("  after %3d moves: %3d rooms drawn, %2d plates on %d levels, %d folded | lines %d, stairs %d, labelled stubs %d".format(
                    n + 1, spots.size, spots.values.map { it.plate }.toSet().size, spots.values.map { it.level }.toSet().size,
                    folded.boxOf.size, k[LinkKind.STRAIGHT] ?: 0, k[LinkKind.STAIR] ?: 0, k[LinkKind.STUB] ?: 0))
            }
        }
        // every passage the game really has, and that was walked, is recorded where it really goes
        for ((room, t) in truth) for ((d, dest) in t.exits) {
            val a = id[room] ?: continue
            val p = map.exit(a, d) ?: continue
            if (!p.assumed && p.to != null) assertEquals("$room $d", id[dest], p.to)
        }
        val folded = foldTangles(map)
        val spots = floorsOf(folded.map)
        val drawing = drawingOf(folded.map, spots, lean = 18f / 134f)
        val samePlate = drawing.links.count { it.kind == LinkKind.STUB && spots.getValue(it.a).plate == spots.getValue(it.b).plate }
        val oneWay = map.passages.count { it.oneWay }
        // really one-way: the game has no exit at all from the far room back to this one
        val back = id.entries.associate { it.value to it.key }
        fun reallyOneWay(p: Passage) = truth.getValue(back.getValue(p.to!!)).exits.values.none { it == back.getValue(p.from) }
        val wrongMarks = map.passages.count { it.oneWay && !reallyOneWay(it) }
        val missedMarks = map.passages.count { !it.assumed && it.to != null && it.to != it.from && !it.oneWay && reallyOneWay(it) }
        val stillAssumed = map.passages.count { it.assumed }
        val wrongAssumed = map.passages.count { p ->
            p.assumed && truth.entries.first { id[it.key] == p.from }.value.exits[p.dir]?.let { id[it] } != p.to
        }
        val out = buildString {
            appendLine("$title: ${moves.size} moves, ${map.rooms.size} rooms, ${map.passages.size} facts")
            stages.forEach(::appendLine)
            appendLine("  finished map: ${folded.members.map { "${it.value.first().name} x${it.value.size}" }.ifEmpty { listOf("no tangles") }.joinToString()}")
            appendLine("  labelled stubs: $samePlate inside one plate, ${drawing.links.count { it.kind == LinkKind.STUB } - samePlate} between plates")
            appendLine("  loops drawn: ${drawing.loops.size}, blocked exits drawn: ${drawing.blocked.size}, one-way marks: $oneWay ($wrongMarks wrong, $missedMarks missed)")
            appendLine("  ways back still only assumed: $stillAssumed, of which wrong: $wrongAssumed")
            appendLine("  known passages followed to get around: $followed; 'couldn't go' reports: $refused; jumps: $teleported")
            appendLine("  slowest redraw after a move: $slowest ms (on this computer)")
        }
        File("build/playthrough").mkdirs()
        File("build/playthrough/$game.json").writeText(Json { prettyPrint = true }.encodeToString(map))
        File("build/playthrough/$game.txt").writeText(out)
        return out
    }

    @Test
    fun `Zork I, all 302 moves`() {
        val report = play("zork1", "Zork I")
        println(report)
        assertTrue(report.contains("Maze x15"))
    }

    @Test
    fun `The Lurking Horror, all 190 moves`() {
        val report = play("lurkinghorror", "The Lurking Horror")
        println(report)
        assertTrue(report.contains("Wet Tunnel x12"))
    }
}
