package com.jrod.droidmapper.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFactsTest {
    private val start = GameMap.starting("Test", "Kitchen")

    @Test
    fun `walking somewhere new records the passage and assumes the way back`() {
        val m = start.walkToNew(Dir.E, "Hall")
        assertEquals("r2", m.current)
        assertEquals(Passage("r1", Dir.E, "r2"), m.exit("r1", Dir.E))
        assertEquals(Passage("r2", Dir.W, "r1", assumed = true), m.exit("r2", Dir.W))
    }

    @Test
    fun `walking back confirms the assumed way back`() {
        val m = start.walkToNew(Dir.E, "Hall").walkTo(Dir.W, "r1")
        assertEquals("r1", m.current)
        assertEquals(false, m.exit("r2", Dir.W)!!.assumed)
        assertEquals(2, m.passages.size)
    }

    @Test
    fun `arriving somewhere else re-points the assumed way back`() {
        val m = start.walkToNew(Dir.E, "Hall").walkToNew(Dir.W, "Cellar")
        assertEquals("r3", m.exit("r2", Dir.W)!!.to)
        assertEquals("r2", m.exit("r1", Dir.E)!!.to)       // the outward passage is untouched
    }

    @Test
    fun `cannot go back marks the outward passage one-way`() {
        val m = start.walkToNew(Dir.DOWN, "Pit").cannotGo(Dir.UP)
        assertTrue(m.exit("r1", Dir.DOWN)!!.oneWay)
        assertNull(m.exit("r2", Dir.UP)!!.to)
    }

    @Test
    fun `closing a loop into a known room makes no new room`() {
        val m = start.walkToNew(Dir.E, "Hall").walkToNew(Dir.S, "Yard").walkToNew(Dir.W, "Shed").walkTo(Dir.N, "r1")
        assertEquals(4, m.rooms.size)
        assertEquals("r1", m.current)
        assertEquals("r1", m.exit("r4", Dir.N)!!.to)
    }

    @Test
    fun `a room card keeps its description and items`() {
        val m = start.editRoom("r1", "Kitchen", "A table has been used recently.", "Brown sack\nGlass bottle")
        assertEquals("A table has been used recently.", m.room("r1")!!.description)
        assertEquals("Brown sack\nGlass bottle", m.room("r1")!!.items)
    }

    @Test
    fun `same-named rooms are numbered`() {
        val m = start.walkToNew(Dir.E, "Forest").walkToNew(Dir.E, "Forest")
        assertEquals(listOf("Kitchen", "Forest (1)", "Forest (2)"), m.rooms.map { m.displayNames().getValue(it.id) })
    }
}

class FixLaterTest {
    private val start = GameMap.starting("Test", "Kitchen")

    @Test
    fun `a passage that brings you back to the same room is recorded as a loop`() {
        val m = start.walkTo(Dir.N, "r1")
        assertEquals("r1", m.exit("r1", Dir.N)!!.to)
        assertEquals(1, m.passages.size)                    // no way back is assumed for a loop
    }

    @Test
    fun `two rooms that turn out to be one are merged`() {
        // Kitchen -E-> Hall -S-> Yard -W-> "Shed" -N-> a duplicate "Kitchen"
        val m = start.walkToNew(Dir.E, "Hall").walkToNew(Dir.S, "Yard").walkToNew(Dir.W, "Shed").walkToNew(Dir.N, "Kitchen")
        val fixed = m.mergeRooms(keep = "r1", drop = "r5")
        assertEquals(4, fixed.rooms.size)
        assertEquals("r1", fixed.current)
        assertEquals("r1", fixed.exit("r4", Dir.N)!!.to)    // Shed north now reaches the real Kitchen
        assertEquals("r4", fixed.exit("r1", Dir.S)!!.to)    // and the assumed way back came along
        assertEquals("r2", fixed.exit("r1", Dir.E)!!.to)
    }

    @Test
    fun `when merged rooms disagree a walked passage beats an assumed one`() {
        // Kitchen's west is only assumed (from Hall going east... the other way round here)
        val m = start.walkToNew(Dir.E, "Hall").jumpToNew("Kitchen").walkToNew(Dir.E, "Pantry")
        // r1 east -> Hall (walked); r3 east -> Pantry (walked). Survivor r1 keeps its own.
        val fixed = m.mergeRooms(keep = "r1", drop = "r3")
        assertEquals("r2", fixed.exit("r1", Dir.E)!!.to)
        // Hall's assumed west -> r1 stands; Pantry's assumed west now points at r1 too
        assertEquals("r1", fixed.exit("r4", Dir.W)!!.to)
    }

    @Test
    fun `a passage can be re-pointed or removed after the fact`() {
        val m = start.walkToNew(Dir.E, "Hall").walkToNew(Dir.E, "Yard")
        val re = m.redirect("r1", Dir.E, "r3")
        assertEquals("r3", re.exit("r1", Dir.E)!!.to)
        assertNull(re.passages.firstOrNull { it.from == "r2" && it.dir == Dir.W && it.to == "r1" })   // old assumed way back is gone
        val gone = m.removePassage("r1", Dir.E)
        assertNull(gone.exit("r1", Dir.E))
        assertNull(gone.exit("r2", Dir.W))
    }

    @Test
    fun `deleting a room removes its passages and moves you out of it`() {
        val m = start.walkToNew(Dir.E, "Hall").deleteRoom("r2")
        assertEquals(listOf("r1"), m.rooms.map { it.id })
        assertEquals("r1", m.current)
        assertTrue(m.passages.isEmpty())
    }
}

class LayoutTest {
    @Test
    fun `a square of rooms is drawn as a square`() {
        val m = GameMap.starting("T", "A").walkToNew(Dir.E, "B").walkToNew(Dir.S, "C").walkToNew(Dir.W, "D").walkTo(Dir.N, "r1")
        val p = layoutOf(m)
        assertEquals(Placed(0, 0, 0), p["r1"])
        assertEquals(Placed(0, 1, 0), p["r2"])
        assertEquals(Placed(0, 1, 1), p["r3"])
        assertEquals(Placed(0, 0, 1), p["r4"])
    }

    @Test
    fun `going up sets the new room above, in the same area`() {
        val p = layoutOf(GameMap.starting("T", "Kitchen").walkToNew(Dir.UP, "Attic"))
        assertEquals(Placed(0, 0, -1), p["r2"])
    }

    @Test
    fun `a room reached by jumping starts its own area`() {
        val p = layoutOf(GameMap.starting("T", "A").jumpToNew("Elsewhere"))
        assertTrue(p.getValue("r2").area != p.getValue("r1").area)
    }

    @Test
    fun `the best guess for a loop-closing move is the room drawn where the exit points`() {
        val m = GameMap.starting("T", "A").walkToNew(Dir.E, "B").walkToNew(Dir.S, "C").walkToNew(Dir.W, "D")
        assertEquals("A", guessArrival(m, floorsOf(m), Dir.N).first().name)
    }
}

class FloorsTest {
    private val house = GameMap.starting("T", "Kitchen").walkToNew(Dir.E, "Hall")

    @Test
    fun `rooms you can walk between share a plate and a level`() {
        val f = floorsOf(house)
        assertEquals(f.getValue("r1").plate, f.getValue("r2").plate)
        assertEquals(Spot(f.getValue("r1").plate, 0, 1, 0), f["r2"])
    }

    @Test
    fun `going up leads to a plate one level higher, directly above`() {
        val f = floorsOf(house.walkToNew(Dir.UP, "Landing"))
        val hall = f.getValue("r2")
        val landing = f.getValue("r3")
        assertTrue(landing.plate != hall.plate)
        assertEquals(1, landing.level)
        assertEquals(hall.x to hall.y, landing.x to landing.y)
    }

    @Test
    fun `down then down again is two levels below`() {
        val f = floorsOf(house.walkToNew(Dir.DOWN, "Cellar").walkToNew(Dir.DOWN, "Pit"))
        assertEquals(-1, f.getValue("r3").level)
        assertEquals(-2, f.getValue("r4").level)
    }

    @Test
    fun `two ways down to the same floor keep it one level below`() {
        // Kitchen-Hall above; Cellar-Vault below; stairs at both ends.
        val m = house.walkToNew(Dir.DOWN, "Vault").walkToNew(Dir.W, "Cellar").walkTo(Dir.UP, "r1")
        val f = floorsOf(m)
        assertEquals(f.getValue("r3").plate, f.getValue("r4").plate)
        assertEquals(-1, f.getValue("r4").level)
        assertEquals(f.getValue("r1").x, f.getValue("r4").x)      // Cellar sits under Kitchen
    }
}

/** The Kotlin engine must reproduce the Python prototype exactly on a full Zork I walk. */
class LayoutParityTest {
    @Test
    fun `302 moves of Zork I land where the prototype put them`() {
        val text = javaClass.getResource("/zork1_walk.json")!!.readText()
        val root = Json.parseToJsonElement(text).jsonObject
        val engine = AreaLayout(root.getValue("start").jsonPrimitive.content)
        for (e in root.getValue("events").jsonArray) {
            val (frm, d, to) = e.jsonArray.take(3).map { it.jsonPrimitive.content }
            assertEquals("is-new flag for $frm $d $to", e.jsonArray[3].jsonPrimitive.boolean, to !in engine.pos)
            engine.move(frm, Dir.valueOf(d), to)
        }
        val got = engine.placed()
        val want = root.getValue("expected").jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.int } }
        assertEquals(want.keys, got.keys)
        // Area numbers are only names; what must match is who shares an area, and every cell.
        val wantGroups = want.entries.groupBy({ it.value[0] }, { it.key }).values.map { it.toSet() }.toSet()
        val gotGroups = got.entries.groupBy({ it.value.area }, { it.key }).values.map { it.toSet() }.toSet()
        assertEquals(wantGroups, gotGroups)
        val wrong = want.filter { (r, w) -> got.getValue(r).let { it.x != w[1] || it.y != w[2] } }.keys
        assertTrue("rooms in a different cell: $wrong", wrong.isEmpty())
    }
}
