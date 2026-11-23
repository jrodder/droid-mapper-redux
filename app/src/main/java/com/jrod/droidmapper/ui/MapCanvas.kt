package com.jrod.droidmapper.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.jrod.droidmapper.model.Cell
import com.jrod.droidmapper.model.Dir
import com.jrod.droidmapper.model.GameMap
import com.jrod.droidmapper.model.Passage
import com.jrod.droidmapper.model.Placed
import com.jrod.droidmapper.model.clear
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

// World units are dp. One grid cell, and the room box drawn in it; the gaps hold labels.
private const val CELL_W = 138f
private const val CELL_H = 100f
private const val BOX_W = 104f
private const val BOX_H = 52f
private const val AREA_GAP = 2          // empty cells between two areas
private const val ROW_WIDTH = 9         // areas wrap to a new row past this many cells

/** A line between two rooms, or a labelled stub where a line would mislead. */
private class Link(val a: String, val b: String, val ab: Passage?, val ba: Passage?, val straight: Boolean)

/** Everything worked out once per map change, in world units. */
private class Scene(
    val centre: Map<String, Offset>,
    val areaBoxes: List<Rect>,
    val links: List<Link>,
    val loops: List<Passage>,
    val blocked: List<Passage>,
)

private fun buildScene(map: GameMap, placed: Map<String, Placed>): Scene {
    // Areas are set out left to right in the order they were made, wrapping into rows.
    val byArea = placed.entries.groupBy({ it.value.area }, { it.key }).toSortedMap()
    val centre = HashMap<String, Offset>()
    val areaBoxes = ArrayList<Rect>()
    var cx = 0
    var cy = 0
    var rowH = 0
    for ((_, rooms) in byArea) {
        val x0 = rooms.minOf { placed.getValue(it).x }
        val x1 = rooms.maxOf { placed.getValue(it).x }
        val y0 = rooms.minOf { placed.getValue(it).y }
        val y1 = rooms.maxOf { placed.getValue(it).y }
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        if (cx > 0 && cx + w > ROW_WIDTH) { cx = 0; cy += rowH + AREA_GAP; rowH = 0 }
        for (r in rooms) {
            val p = placed.getValue(r)
            centre[r] = Offset((p.x - x0 + cx) * CELL_W, (p.y - y0 + cy) * CELL_H)
        }
        areaBoxes.add(
            Rect(cx * CELL_W - BOX_W / 2 - 16, cy * CELL_H - BOX_H / 2 - 16,
                (cx + w - 1) * CELL_W + BOX_W / 2 + 16, (cy + h - 1) * CELL_H + BOX_H / 2 + 16),
        )
        cx += w + AREA_GAP
        rowH = max(rowH, h)
    }

    val cells = placed.mapValues { Cell(it.value.x, it.value.y) }
    val takenBy = placed.entries.groupBy({ it.value.area }, { Cell(it.value.x, it.value.y) }).mapValues { it.value.toSet() }
    val pairs = LinkedHashMap<Set<String>, MutableList<Passage>>()
    val loops = ArrayList<Passage>()
    val blocked = ArrayList<Passage>()
    for (p in map.passages) {
        when {
            p.to == null -> blocked.add(p)
            p.to == p.from -> loops.add(p)
            p.from in placed && p.to in placed -> pairs.getOrPut(setOf(p.from, p.to)) { ArrayList() }.add(p)
        }
    }
    val links = pairs.map { (key, ps) ->
        val (a, b) = key.toList()
        val pa = placed.getValue(a)
        val pb = placed.getValue(b)
        val same = pa.area == pb.area
        // Prefer the passage the drawing already shows correctly, so a duplicate exit never bends the line.
        fun shown(p: Passage) = same && p.dir.isCompass && clear(cells, takenBy.getValue(pa.area), p.from, p.to!!, p.dir)
        val ab = ps.filter { it.from == a }.let { l -> l.firstOrNull(::shown) ?: l.firstOrNull { it.dir.isCompass } ?: l.firstOrNull() }
        val ba = ps.filter { it.from == b }.let { l -> l.firstOrNull(::shown) ?: l.firstOrNull { it.dir.isCompass } ?: l.firstOrNull() }
        val near = max(abs(pa.x - pb.x), abs(pa.y - pb.y)) <= 1
        val straight = same && (ps.any(::shown) || (near && ps.none { it.dir.isCompass }))
        Link(a, b, ab, ba, straight)
    }
    return Scene(centre, areaBoxes, links, loops, blocked)
}

@Composable
fun MapCanvas(map: GameMap, placed: Map<String, Placed>, names: Map<String, String>, onTapRoom: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer()
    val scene = remember(map.passages, placed) { buildScene(map, placed) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val pan = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()

    // The map follows the player: whenever the current room or its position changes, glide it to the middle.
    val here = scene.centre[map.current]
    LaunchedEffect(map.current, here, size) {
        if (here != null && size != IntSize.Zero) {
            pan.animateTo(Offset(size.width / 2f, size.height / 2f) - here * (density * zoom))
        }
    }

    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, drag, change, _ ->
                    val next = (zoom * change).coerceIn(0.35f, 2.5f)
                    val kept = centroid - (centroid - pan.value) * (next / zoom)   // zoom about the fingers
                    zoom = next
                    scope.launch { pan.snapTo(kept + drag) }
                }
            }
            .pointerInput(scene) {
                detectTapGestures { tap ->
                    val w = (tap - pan.value) / (density * zoom)
                    scene.centre.entries.firstOrNull { (_, c) -> abs(w.x - c.x) <= BOX_W / 2 && abs(w.y - c.y) <= BOX_H / 2 }
                        ?.let { onTapRoom(it.key) }
                }
            },
    ) {
        withTransform({
            translate(pan.value.x, pan.value.y)
            scale(density * zoom, density * zoom, Offset.Zero)
        }) {
            drawScene(scene, map, names, measurer, density, scheme.onSurface, scheme.surface, scheme.outline, scheme.primary, scheme.error, scheme.background)
        }
    }
}

private fun unit(d: Dir): Offset {
    val n = hypot(d.dx.toFloat(), d.dy.toFloat())
    return if (n == 0f) Offset.Zero else Offset(d.dx / n, d.dy / n)
}

/** Where a passage leaves its room: the side its direction names, or the side facing [toward] for up/down/in/out. */
private fun port(centre: Offset, d: Dir?, toward: Offset): Pair<Offset, Offset> {
    val v = if (d != null && d.isCompass) Offset(d.dx.toFloat(), d.dy.toFloat()) else {
        val a = Math.round(atan2(toward.y - centre.y, toward.x - centre.x) / (Math.PI / 4)).toInt()
        val k = ((a % 8) + 8) % 8
        listOf(Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f), Offset(-1f, 1f), Offset(-1f, 0f), Offset(-1f, -1f), Offset(0f, -1f), Offset(1f, -1f))[k]
    }
    val n = hypot(v.x, v.y)
    return Offset(centre.x + v.x * BOX_W / 2, centre.y + v.y * BOX_H / 2) to Offset(v.x / n, v.y / n)
}

private fun DrawScope.drawScene(
    scene: Scene, map: GameMap, names: Map<String, String>, measurer: TextMeasurer, density: Float,
    ink: Color, box: Color, faint: Color, accent: Color, warn: Color, paper: Color,
) {
    val dotted = PathEffect.dashPathEffect(floatArrayOf(3f, 6f))
    for (r in scene.areaBoxes) {
        drawRoundRect(faint, r.topLeft, r.size, CornerRadius(12f), style = Stroke(1f, pathEffect = dotted))
    }
    // Nothing is ever written over a room or over another label.
    val solid = scene.centre.values.mapTo(ArrayList()) { Rect(it.x - BOX_W / 2 - 3, it.y - BOX_H / 2 - 3, it.x + BOX_W / 2 + 3, it.y + BOX_H / 2 + 3) }
    // The canvas is already scaled by the screen density, and text is measured in real pixels,
    // so sizes are divided back down to come out in the map's own units.
    fun pt(size: Float) = (size / density).sp
    val labelStyle = TextStyle(color = ink, fontSize = pt(11f), fontStyle = FontStyle.Italic)
    val tagStyle = TextStyle(color = accent, fontSize = pt(11f), fontWeight = FontWeight.Bold)
    val pending = ArrayList<Pair<androidx.compose.ui.text.TextLayoutResult, Offset>>()

    fun label(text: String, from: Offset, u: Offset, style: TextStyle) {
        val laid = measurer.measure(text, style, maxLines = 1)
        val w = laid.size.width.toFloat()
        val h = laid.size.height.toFloat()
        var best: Rect? = null
        search@ for (along in listOf(4f, 10f, 18f, 28f, 42f, 60f)) {
            for (side in listOf(0f, 12f, -12f, 22f, -22f, 34f, -34f, 46f, -46f)) {
                val x = from.x + u.x * along - u.y * side
                val y = from.y + u.y * along + u.x * side
                val left = when { u.x > 0.3f -> x; u.x < -0.3f -> x - w; else -> x - w / 2 }
                val top = when { u.y > 0.3f -> y; u.y < -0.3f -> y - h; else -> y - h / 2 }
                val r = Rect(left, top, left + w, top + h)
                if (best == null) best = r
                if (solid.none { it.overlaps(r) }) { best = r; break@search }
            }
        }
        solid.add(best!!)
        pending.add(laid to best.topLeft)
    }

    fun arrow(tip: Offset, u: Offset, color: Color) {
        val a = atan2(u.y, u.x)
        for (turn in listOf(2.6f, -2.6f)) {
            drawLine(color, tip, Offset(tip.x + cos(a + turn) * 9f, tip.y + sin(a + turn) * 9f), 2.2f)
        }
    }

    for (l in scene.links) {
        val ca = scene.centre.getValue(l.a)
        val cb = scene.centre.getValue(l.b)
        val (pa, ua) = port(ca, l.ab?.dir ?: l.ba?.dir?.opposite, cb)
        val (pb, ub) = port(cb, l.ba?.dir ?: l.ab?.dir?.opposite, ca)
        val oneWayToB = l.ab?.oneWay == true
        val oneWayToA = l.ba?.oneWay == true
        if (l.straight) {
            drawLine(ink, pa, pb, 2f)
            if (oneWayToB) arrow(pb, -ub, ink)
            if (oneWayToA) arrow(pa, -ua, ink)
            // up/down/in/out says nothing about the page, so the line carries the word
            l.ab?.dir?.takeIf { !it.isCompass }?.let { label(it.label, pa, ua, tagStyle) }
            l.ba?.dir?.takeIf { !it.isCompass }?.let { label(it.label, pb, ub, tagStyle) }
        } else {
            // A line here would bend, cross other lines or run behind rooms: a short stub at each end instead.
            for ((p, u, leaving, other) in listOf(Stub(pa, ua, l.ab, l.b), Stub(pb, ub, l.ba, l.a))) {
                val end = p + u * 12f
                drawLine(ink, p, end, 2f)
                val word = if (leaving == null) "from" else "to"
                val prefix = leaving?.dir?.takeIf { !it.isCompass }?.let { "${it.label} " } ?: ""
                if (leaving?.oneWay == true) arrow(end, u, ink)
                label("$prefix$word ${names[other]}", end, u, labelStyle)
            }
        }
    }
    for (p in scene.loops) {                      // leads straight back to the same room
        val c = scene.centre[p.from] ?: continue
        val (at, u) = port(c, p.dir, c + Offset(0.7f, -1f))
        drawCircle(ink, 8f, at + u * 8f, style = Stroke(2f))
    }
    for (p in scene.blocked) {                    // tried, could not go: a short stub with a bar across it
        val c = scene.centre[p.from] ?: continue
        val (at, u) = port(c, p.dir, c + Offset(0.7f, -1f))
        val end = at + u * 9f
        drawLine(warn, at, end, 2f)
        drawLine(warn, end + Offset(-u.y, u.x) * 6f, end - Offset(-u.y, u.x) * 6f, 2.4f)
    }
    for ((laid, at) in pending) {
        drawRect(paper, at - Offset(2f, 0f), Size(laid.size.width + 4f, laid.size.height.toFloat()))
        drawText(laid, topLeft = at)
    }
    for ((id, c) in scene.centre) {
        val topLeft = Offset(c.x - BOX_W / 2, c.y - BOX_H / 2)
        drawRoundRect(box, topLeft, Size(BOX_W, BOX_H), CornerRadius(5f))
        val current = id == map.current
        drawRoundRect(if (current) accent else ink, topLeft, Size(BOX_W, BOX_H), CornerRadius(5f), style = Stroke(if (current) 4.5f else 1.8f))
        val laid = measurer.measure(
            names[id] ?: "",
            TextStyle(color = ink, fontSize = pt(12.5f), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
            overflow = TextOverflow.Ellipsis, maxLines = 2,
            constraints = Constraints(maxWidth = (BOX_W - 8).toInt()),
        )
        drawText(laid, topLeft = Offset(c.x - laid.size.width / 2f, c.y - laid.size.height / 2f))
    }
}

private data class Stub(val at: Offset, val u: Offset, val leaving: Passage?, val other: String)
