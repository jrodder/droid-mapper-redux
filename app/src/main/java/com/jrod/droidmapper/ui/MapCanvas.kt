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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
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
import com.jrod.droidmapper.model.Spot
import com.jrod.droidmapper.model.clear
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// The map is drawn as stacked floors seen slightly from above, in the style of Infocom's own
// Lurking Horror map: east-west is level across the page so names read normally, north is
// toward the back of a plate, and up/down is straight up and down the page.
// World units are dp.
private const val CELL_W = 134f        // one step east
private const val CELL_D = 88f         // one step south, down the page
private const val SLANT = 18f          // how far a step south also leans west
private const val TILE_W = 104f
private const val TILE_D = 50f
private const val LEVEL_GAP = 96f      // clear height between one level and the next
private const val PLATE_PAD = 0.58f    // how far a plate reaches beyond its outermost rooms, in cells

/** How far a tile's top edge leans east of its centre line (and its bottom edge west). */
private const val LEAN = SLANT * TILE_D / CELL_D / 2

private enum class Kind { STRAIGHT, STAIR, STUB }

/** A pair of connected rooms and how it is drawn. */
private class Link(val a: String, val b: String, val ab: Passage?, val ba: Passage?, val kind: Kind)

/** Everything worked out once per map change, in world units. */
private class Scene(
    val spot: Map<String, Spot>,
    val centre: Map<String, Offset>,
    val plates: List<List<Offset>>,
    val levelNames: List<Pair<String, Offset>>,
    val links: List<Link>,
    val loops: List<Passage>,
    val blocked: List<Passage>,
)

private fun Passage.climbs() = dir == Dir.UP || dir == Dir.DOWN

private fun buildScene(map: GameMap, spot: Map<String, Spot>): Scene {
    // Each level gets a band just deep enough for its plates, highest level at the top.
    val levels = spot.values.map { it.level }.distinct().sortedDescending()
    val top = HashMap<Int, Float>()
    val back = HashMap<Int, Int>()
    var y = 0f
    for (lv in levels) {
        val ys = spot.values.filter { it.level == lv }.map { it.y }
        back[lv] = ys.min()
        top[lv] = y
        y += (ys.max() - ys.min()) * CELL_D + TILE_D + LEVEL_GAP
    }
    fun project(x: Float, yCell: Float, lv: Int) =
        Offset(x * CELL_W - yCell * SLANT, top.getValue(lv) + (yCell - back.getValue(lv)) * CELL_D)

    val centre = spot.mapValues { (_, s) -> project(s.x.toFloat(), s.y.toFloat(), s.level) }
    val plates = spot.values.groupBy { it.plate }.values.map { ss ->
        val lv = ss.first().level
        val x0 = ss.minOf { it.x } - PLATE_PAD
        val x1 = ss.maxOf { it.x } + PLATE_PAD
        val y0 = ss.minOf { it.y } - PLATE_PAD
        val y1 = ss.maxOf { it.y } + PLATE_PAD
        listOf(project(x0, y0, lv), project(x1, y0, lv), project(x1, y1, lv), project(x0, y1, lv))
    }
    val left = centre.values.minOf { it.x } - TILE_W
    val levelNames = if (levels.size < 2) emptyList() else levels.map { lv ->
        val ys = centre.filterKeys { spot.getValue(it).level == lv }.values.map { it.y }
        val name = when {
            lv == 0 -> "start level"
            lv > 0 -> "$lv up"
            else -> "${-lv} down"
        }
        name to Offset(left, (ys.min() + ys.max()) / 2)
    }

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
        val sideways = abs(centre.getValue(a).x - centre.getValue(b).x)
        val kind = when {
            same && ps.any(::shown) -> Kind.STRAIGHT
            !same && ps.any { it.climbs() } && sa.level != sb.level && sideways <= 2.5f * CELL_W -> Kind.STAIR
            else -> Kind.STUB
        }
        if (ps.any { it.climbs() } && !same) Link(a, b, stair(a) ?: pick(a), stair(b) ?: pick(b), kind)
        else Link(a, b, pick(a), pick(b), kind)
    }
    return Scene(spot, centre, plates, levelNames, links, loops, blocked)
}

@Composable
fun MapCanvas(
    map: GameMap, placed: Map<String, Spot>, names: Map<String, String>,
    onTapRoom: (String) -> Unit, onOpenRoom: (String) -> Unit,
) {
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
                    val next = (zoom * change).coerceIn(0.3f, 2.5f)
                    val kept = centroid - (centroid - pan.value) * (next / zoom)   // zoom about the fingers
                    zoom = next
                    scope.launch { pan.snapTo(kept + drag) }
                }
            }
            .pointerInput(scene) {
                fun roomAt(tap: Offset): String? {
                    val w = (tap - pan.value) / (density * zoom)
                    return scene.centre.entries
                        .firstOrNull { (_, c) -> abs(w.x - c.x) <= TILE_W / 2 && abs(w.y - c.y) <= TILE_D / 2 }?.key
                }
                detectTapGestures(
                    onTap = { roomAt(it)?.let(onTapRoom) },
                    onDoubleTap = { roomAt(it)?.let(onOpenRoom) },      // a double tap or a long press
                    onLongPress = { roomAt(it)?.let(onOpenRoom) },      // opens the room's card
                )
            },
    ) {
        withTransform({
            translate(pan.value.x, pan.value.y)
            scale(density * zoom, density * zoom, Offset.Zero)
        }) {
            drawScene(
                scene, map, names, measurer, density,
                ink = scheme.onSurface, box = scheme.surface, edge = scheme.outline, plate = scheme.outlineVariant,
                accent = scheme.primary, warn = scheme.error, muted = scheme.onSurfaceVariant,
            )
        }
    }
}

/** The four corners of a room tile: a parallelogram leaning the same way as its plate. */
private fun tile(c: Offset) = listOf(
    Offset(c.x - TILE_W / 2 + LEAN, c.y - TILE_D / 2), Offset(c.x + TILE_W / 2 + LEAN, c.y - TILE_D / 2),
    Offset(c.x + TILE_W / 2 - LEAN, c.y + TILE_D / 2), Offset(c.x - TILE_W / 2 - LEAN, c.y + TILE_D / 2),
)

private fun path(points: List<Offset>) = Path().apply {
    moveTo(points[0].x, points[0].y)
    for (p in points.drop(1)) lineTo(p.x, p.y)
    close()
}

/** A compass direction as it runs on the slanted page. */
private fun onPage(dx: Float, dy: Float): Offset {
    val v = Offset(dx * CELL_W - dy * SLANT, dy * CELL_D)
    return v / hypot(v.x, v.y)
}

/**
 * Where a passage leaves its room: the side its direction names. Up/down/in/out have no
 * side of their own and leave from the side facing [toward].
 */
private fun port(centre: Offset, d: Dir?, toward: Offset): Pair<Offset, Offset> {
    val (dx, dy) = if (d != null && d.isCompass) d.dx.toFloat() to d.dy.toFloat() else {
        val a = Math.round(atan2(toward.y - centre.y, toward.x - centre.x) / (Math.PI / 4)).toInt()
        listOf(1f to 0f, 1f to 1f, 0f to 1f, -1f to 1f, -1f to 0f, -1f to -1f, 0f to -1f, 1f to -1f)[((a % 8) + 8) % 8]
    }
    return Offset(centre.x + dx * TILE_W / 2 - dy * LEAN, centre.y + dy * TILE_D / 2) to onPage(dx, dy)
}

private fun DrawScope.drawScene(
    scene: Scene, map: GameMap, names: Map<String, String>, measurer: TextMeasurer, density: Float,
    ink: Color, box: Color, edge: Color, plate: Color, accent: Color, warn: Color, muted: Color,
) {
    // The canvas is already scaled by the screen density, and text is measured in real pixels,
    // so sizes are divided back down to come out in the map's own units.
    fun pt(size: Float) = (size / density).sp
    val labelStyle = TextStyle(color = ink, fontSize = pt(11f), fontStyle = FontStyle.Italic)
    val stairStyle = TextStyle(color = accent, fontSize = pt(11f), fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold)
    val tagStyle = TextStyle(color = accent, fontSize = pt(11f), fontWeight = FontWeight.Bold)
    val itemStyle = TextStyle(color = muted, fontSize = pt(10.5f), fontStyle = FontStyle.Italic)

    for ((name, at) in scene.levelNames) {
        val laid = measurer.measure(name, TextStyle(color = muted, fontSize = pt(12f), fontWeight = FontWeight.Bold))
        drawText(laid, topLeft = Offset(at.x - laid.size.width - 14f, at.y - laid.size.height / 2f))
    }
    // Each plate is a floor: the rooms you can walk between without stairs.
    for (corners in scene.plates) {
        drawPath(path(corners), plate)
        drawPath(path(corners), edge, style = Stroke(1.2f))
    }

    // Nothing is ever written over a room or over another label.
    val solid = scene.centre.values.mapTo(ArrayList()) {
        Rect(it.x - TILE_W / 2 - LEAN - 3, it.y - TILE_D / 2 - 3, it.x + TILE_W / 2 + LEAN + 3, it.y + TILE_D / 2 + 3)
    }
    val pending = ArrayList<Pair<TextLayoutResult, Offset>>()
    fun label(text: String, from: Offset, u: Offset, style: TextStyle) {
        val laid = measurer.measure(text, style, maxLines = 1)
        val w = laid.size.width.toFloat()
        val h = laid.size.height.toFloat()
        var best: Rect? = null
        search@ for (along in listOf(4f, 10f, 18f, 28f, 42f, 60f)) {
            for (side in listOf(0f, 12f, -12f, 22f, -22f, 34f, -34f, 46f, -46f)) {
                val x = from.x + u.x * along - u.y * side
                val y = from.y + u.y * along + u.x * side
                val l = when { u.x > 0.3f -> x; u.x < -0.3f -> x - w; else -> x - w / 2 }
                val t = when { u.y > 0.3f -> y; u.y < -0.3f -> y - h; else -> y - h / 2 }
                val r = Rect(l, t, l + w, t + h)
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
    fun stub(at: Offset, u: Offset, leaving: Passage?, other: String, color: Color, style: TextStyle, climbWord: String?) {
        val end = at + u * 12f
        drawLine(color, at, end, 2f)
        if (leaving?.oneWay == true) arrow(end, u, color)
        val word = climbWord
            ?: leaving?.dir?.takeIf { !it.isCompass }?.let { "${it.label} to" }
            ?: if (leaving == null) "from" else "to"
        label("$word ${names[other]}", end, u, style)
    }

    for (l in scene.links) {
        val ca = scene.centre.getValue(l.a)
        val cb = scene.centre.getValue(l.b)
        val la = scene.spot.getValue(l.a).level
        val lb = scene.spot.getValue(l.b).level
        when (l.kind) {
            Kind.STRAIGHT -> {
                val (pa, ua) = port(ca, l.ab?.dir ?: l.ba?.dir?.opposite, cb)
                val (pb, ub) = port(cb, l.ba?.dir ?: l.ab?.dir?.opposite, ca)
                drawLine(ink, pa, pb, 2f)
                if (l.ab?.oneWay == true) arrow(pb, -ub, ink)
                if (l.ba?.oneWay == true) arrow(pa, -ua, ink)
            }
            Kind.STAIR -> {
                // from the bottom edge of the upper room to the top edge of the lower one
                val aIsUpper = la > lb
                val hi = if (aIsUpper) ca else cb
                val lo = if (aIsUpper) cb else ca
                val from = Offset(hi.x - LEAN, hi.y + TILE_D / 2)
                val to = Offset(lo.x + LEAN, lo.y - TILE_D / 2)
                drawLine(accent, from, to, 2.6f)
                drawCircle(accent, 3.6f, from)
                drawCircle(accent, 3.6f, to)
                val u = (to - from) / hypot(to.x - from.x, to.y - from.y)
                if ((if (aIsUpper) l.ab else l.ba)?.oneWay == true) arrow(to, u, accent)       // only down
                if ((if (aIsUpper) l.ba else l.ab)?.oneWay == true) arrow(from, -u, accent)    // only up
            }
            Kind.STUB -> {
                val climb = l.ab?.climbs() == true || l.ba?.climbs() == true
                if (climb && la != lb) {
                    // stairs too far sideways to draw: a short stub straight up or down, naming the other end
                    for ((c, other, leaving, goesUp) in listOf(Far(ca, l.b, l.ab, lb > la), Far(cb, l.a, l.ba, la > lb))) {
                        val at = Offset(c.x + if (goesUp) LEAN else -LEAN, c.y + if (goesUp) -TILE_D / 2 else TILE_D / 2)
                        stub(at, Offset(0f, if (goesUp) -1f else 1f), leaving, other, accent, stairStyle, if (goesUp) "up to" else "down to")
                    }
                } else {
                    val (pa, ua) = port(ca, l.ab?.dir ?: l.ba?.dir?.opposite, cb)
                    val (pb, ub) = port(cb, l.ba?.dir ?: l.ab?.dir?.opposite, ca)
                    stub(pa, ua, l.ab, l.b, ink, labelStyle, null)
                    stub(pb, ub, l.ba, l.a, ink, labelStyle, null)
                }
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
        if (!p.dir.isCompass) label(p.dir.label, end, u, tagStyle.copy(color = warn))
    }
    // What is lying in a room is written beside it, as on Infocom's printed maps.
    for (room in map.rooms) {
        val c = scene.centre[room.id] ?: continue
        if (room.items.isBlank()) continue
        val text = room.items.lines().filter { it.isNotBlank() }.joinToString(", ")
            .let { if (it.length > 34) it.take(33) + "…" else it }
        label(text, Offset(c.x - TILE_W / 2 + 6f, c.y + TILE_D / 2 + 2f), Offset(1f, 1f) / hypot(1f, 1f), itemStyle)
    }
    for ((laid, at) in pending) {
        drawRoundRect(plate, at - Offset(3f, 0f), Size(laid.size.width + 6f, laid.size.height.toFloat()), CornerRadius(3f))
        drawText(laid, topLeft = at)
    }
    for ((id, c) in scene.centre) {
        val shape = path(tile(c))
        val current = id == map.current
        drawPath(shape, box)
        drawPath(shape, if (current) accent else ink, style = Stroke(if (current) 4.5f else 1.8f))
        val laid = measurer.measure(
            names[id] ?: "",
            TextStyle(color = ink, fontSize = pt(12.5f), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
            overflow = TextOverflow.Ellipsis, maxLines = 2,
            constraints = Constraints(maxWidth = (TILE_W - 12).toInt()),
        )
        drawText(laid, topLeft = Offset(c.x - laid.size.width / 2f, c.y - laid.size.height / 2f))
    }
}

private data class Far(val centre: Offset, val other: String, val leaving: Passage?, val goesUp: Boolean)
