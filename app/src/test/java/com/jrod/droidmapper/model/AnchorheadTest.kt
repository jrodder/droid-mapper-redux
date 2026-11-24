package com.jrod.droidmapper.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The opening streets of Anchorhead, walked in the real game (release 5) and entered the way
 * a player did on a phone. Twisting Lane is north-west of Narrow Street, which is the very
 * cell Mill Road wants when it turns out to be north of Junction. Making room for Mill Road
 * must not knock Twisting Lane off its diagonal.
 */
class AnchorheadTest {
    private fun streets(): GameMap {
        var m = GameMap.starting("Anchorhead", "Outside Real Estate")     // r1
        m = m.walkToNew(Dir.W, "Narrow Street")                           // r2
        m = m.walkToNew(Dir.NW, "Twisting Lane")                          // r3
        m = m.walkTo(Dir.SE, "r2")
        m = m.walkToNew(Dir.S, "Whately Bridge")                          // r4
        m = m.walkTo(Dir.N, "r2")
        m = m.walkToNew(Dir.W, "Junction")                                // r5
        m = m.walkToNew(Dir.N, "Mill Road")                               // r6
        m = m.toggleUnexplored("r6", Dir.W)
        return m.walkToNew(Dir.E, "Breakwater")                           // r7
    }

    @Test
    fun `making room for Mill Road keeps Twisting Lane on its diagonal`() {
        val map = streets()
        val spots = floorsOf(map)
        val drawing = drawingOf(map, spots, lean = 18f / 134f)
        File("build/playthrough").mkdirs()
        File("build/playthrough/anchorhead.json").writeText(Json.encodeToString(map))
        val x0 = spots.values.minOf { it.x }; val y0 = spots.values.minOf { it.y }
        val picture = (y0..spots.values.maxOf { it.y }).joinToString("\n") { y ->
            (x0..spots.values.maxOf { it.x }).joinToString(" ") { x ->
                (spots.entries.firstOrNull { it.value.x == x && it.value.y == y }?.let { map.room(it.key)!!.name } ?: ".").padEnd(19)
            }
        }
        File("build/playthrough/anchorhead.txt").writeText(picture + "\n" + drawing.links.joinToString("\n") {
            "${map.room(it.a)!!.name} - ${map.room(it.b)!!.name}: ${it.kind}"
        } + "\n")
        assertEquals("passages drawn as labels", 0, drawing.links.count { it.kind == LinkKind.STUB })
        assertEquals(1, spots.values.map { it.plate }.toSet().size)
    }
}
