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
import com.jrod.droidmapper.model.DrawnLink
import com.jrod.droidmapper.model.LinkKind
import com.jrod.droidmapper.model.drawingOf
import com.jrod.droidmapper.model.plateRises
import com.jrod.droidmapper.model.Dir
import com.jrod.droidmapper.model.GameMap
import com.jrod.droidmapper.model.Passage
import com.jrod.droidmapper.model.Spot
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

/** Everything worked out once per map change, in world units. */
private class Scene(
    val spot: Map<String, Spot>,
    val centre: Map<String, Offset>,
    val plates: List<List<Offset>>,
    val levelNames: List<Pair<String, Offset>>,
    val links: List<DrawnLink>,
    /** Where each drawn stairway runs: its upper end, then its lower end. */
    val stairs: Map<DrawnLink, Pair<Offset, Offset>>,
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
    // Plates on a level are closed up the page so the level is no deeper than it has to be.
    val rise = plateRises(map, spot, SLANT / CELL_W, PLATE_PAD)
    fun row(s: Spot) = s.y - (rise[s.plate] ?: 0)
    for (lv in levels) {
        val ys = spot.values.filter { it.level == lv }.map(::row)
        back[lv] = ys.min()
        top[lv] = y
        y += (ys.max() - ys.min()) * CELL_D + TILE_D + LEVEL_GAP
    }
    fun project(x: Float, yCell: Float, lv: Int, up: Int) =
        Offset(x * CELL_W - yCell * SLANT, top.getValue(lv) + (yCell - up - back.getValue(lv)) * CELL_D)

    val centre = spot.mapValues { (_, s) -> project(s.x.toFloat(), s.y.toFloat(), s.level, rise[s.plate] ?: 0) }
    val plates = spot.values.groupBy { it.plate }.values.map { ss ->
        val lv = ss.first().level
        val up = rise[ss.first().plate] ?: 0
        val x0 = ss.minOf { it.x } - PLATE_PAD
        val x1 = ss.maxOf { it.x } + PLATE_PAD
        val y0 = ss.minOf { it.y } - PLATE_PAD
        val y1 = ss.maxOf { it.y } + PLATE_PAD
        listOf(project(x0, y0, lv, up), project(x1, y0, lv, up), project(x1, y1, lv, up), project(x0, y1, lv, up))
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

    // Which passages are lines, stairs or labelled stubs is decided in the model (and tested there).
    val drawing = drawingOf(map, spot, lean = SLANT / CELL_W)

    // A stairway is only drawn as a line if the line can get from one room to the other without
    // passing over a third. It may leave either room anywhere along the facing edge to manage
    // that. If it still cannot, the stairway becomes a pair of labels, like any other passage
    // a line would misrepresent.
    val tiles = centre.mapValues { (_, c) ->
        Rect(c.x - TILE_W / 2 - LEAN - 5, c.y - TILE_D / 2 - 4, c.x + TILE_W / 2 + LEAN + 5, c.y + TILE_D / 2 + 4)
    }
    fun crosses(a: Offset, b: Offset, ends: DrawnLink) = tiles.any { (id, r) ->
        if (id == ends.a || id == ends.b) return@any false
        val y0 = maxOf(a.y, r.top); val y1 = minOf(b.y, r.bottom)
        if (y0 > y1) return@any false
        fun xAt(y: Float) = a.x + (b.x - a.x) * (y - a.y) / (b.y - a.y)
        maxOf(xAt(y0), xAt(y1)) >= r.left && minOf(xAt(y0), xAt(y1)) <= r.right
    }
    val shifts = listOf(0f, -16f, 16f, -32f, 32f, -44f, 44f)
    val pairs = shifts.flatMap { s -> shifts.map { t -> s to t } }.sortedBy { abs(it.first) + abs(it.second) }
    val stairs = HashMap<DrawnLink, Pair<Offset, Offset>>()
    val links = drawing.links.map { l ->
        if (l.kind != LinkKind.STAIR) return@map l
        val ca = centre.getValue(l.a); val cb = centre.getValue(l.b)
        val hi = if (ca.y < cb.y) ca else cb
        val lo = if (ca.y < cb.y) cb else ca
        val run = pairs.asSequence()
            .map { (s, t) -> Offset(hi.x - LEAN + s, hi.y + TILE_D / 2) to Offset(lo.x + LEAN + t, lo.y - TILE_D / 2) }
            .firstOrNull { (from, to) -> to.y > from.y && !crosses(from, to, l) }
        if (run == null) DrawnLink(l.a, l.b, l.ab, l.ba, LinkKind.STUB) else l.also { stairs[it] = run }
    }
    return Scene(spot, centre, plates, levelNames, links, stairs, drawing.loops, drawing.blocked)
}

@Composable
fun MapCanvas(
    map: GameMap, placed: Map<String, Spot>, names: Map<String, String>, boxes: Set<String>,
    onTapRoom: (String) -> Unit, onOpenRoom: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer()
    val scene = remember(map.passages, placed) { buildScene(map, placed) }
    // Working out where every label goes is the slow part, so it is done once per change, not once per frame.
    val labels = remember(scene, names, map.rooms, density, scheme) { LabelCache() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val pan = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    var whole by remember { mutableStateOf(false) }     // showing the whole map instead of following the player

    // The map follows the player: whenever the current room or its position changes, glide it to the middle.
    val here = scene.centre[map.current]
    // Double-tapping empty space shows the whole map; doing it again returns to the player.
    LaunchedEffect(map.current, here, size, whole) {
        if (here == null || size == IntSize.Zero) return@LaunchedEffect
        if (whole) {
            val xs = scene.plates.flatten().map { it.x }
            val ys = scene.plates.flatten().map { it.y }
            val w = xs.max() - xs.min() + 60f
            val h = ys.max() - ys.min() + 60f
            zoom = minOf(size.width / (w * density), size.height / (h * density)).coerceIn(0.06f, 1f)
            val middle = Offset((xs.max() + xs.min()) / 2, (ys.max() + ys.min()) / 2)
            pan.animateTo(Offset(size.width / 2f, size.height / 2f) - middle * (density * zoom))
        } else {
            if (zoom < 0.3f) zoom = 1f
            pan.animateTo(Offset(size.width / 2f, size.height / 2f) - here * (density * zoom))
        }
    }

    Canvas(
        Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, drag, change, _ ->
                    val next = (zoom * change).coerceIn(0.06f, 2.5f)
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
                    onDoubleTap = { roomAt(it)?.let(onOpenRoom) ?: run { whole = !whole } },   // on a room: its card
                    onLongPress = { roomAt(it)?.let(onOpenRoom) },      // opens the room's card
                )
            },
    ) {
        withTransform({
            translate(pan.value.x, pan.value.y)
            scale(density * zoom, density * zoom, Offset.Zero)
        }) {
            drawScene(
                scene, labels, map, names, boxes, measurer, density, todo = scheme.tertiary, fold = scheme.primaryContainer,
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

/** Where each label ended up, in the order the drawing asks for them. */
private class LabelCache {
    val placed = ArrayList<Pair<TextLayoutResult, Rect>>()
    var filled = false
}

private fun DrawScope.drawScene(
    scene: Scene, cache: LabelCache, map: GameMap, names: Map<String, String>, boxes: Set<String>, measurer: TextMeasurer, density: Float,
    todo: Color, fold: Color,
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

    // Nothing is ever written over a room, a line or another label. Everything a label must
    // keep clear of is gathered before the first label is placed.
    val solid = ArrayList<Rect>()
    fun along(from: Offset, to: Offset, half: Float) {          // a run of small boxes down a line
        val steps = ((to - from).getDistance() / 9f).toInt().coerceAtLeast(1)
        for (i in 0..steps) {
            val p = from + (to - from) * (i / steps.toFloat())
            solid.add(Rect(p.x - half, p.y - half, p.x + half, p.y + half))
        }
    }
    val tiles = ArrayList<Rect>()
    if (!cache.filled) {
        scene.centre.values.mapTo(tiles) {
            Rect(it.x - TILE_W / 2 - LEAN - 3, it.y - TILE_D / 2 - 3, it.x + TILE_W / 2 + LEAN + 3, it.y + TILE_D / 2 + 3)
        }
        solid.addAll(tiles)
        for ((from, to) in scene.stairs.values) along(from, to, 5f)
        for (l in scene.links) if (l.kind == LinkKind.STRAIGHT) {
            val ca = scene.centre.getValue(l.a); val cb = scene.centre.getValue(l.b)
            along(port(ca, l.ab?.dir ?: l.ba?.dir?.opposite, cb).first, port(cb, l.ba?.dir ?: l.ab?.dir?.opposite, ca).first, 3.5f)
        }
        for (p in scene.blocked + scene.loops) {
            val c = scene.centre[p.from] ?: continue
            val (at, u) = port(c, p.dir, c + Offset(0.7f, -1f))
            solid.add(Rect(at.x + u.x * 8f - 9f, at.y + u.y * 8f - 9f, at.x + u.x * 8f + 9f, at.y + u.y * 8f + 9f))
        }
        for (room in map.rooms) {
            val c = scene.centre[room.id] ?: continue
            for (d in room.unexplored) if (d.isCompass && map.exit(room.id, d) == null) {
                val (at, u) = port(c, d, c)
                solid.add(Rect(at.x + u.x * 10f - 9f, at.y + u.y * 10f - 9f, at.x + u.x * 10f + 9f, at.y + u.y * 10f + 9f))
            }
        }
    }
    val pending = ArrayList<Pair<TextLayoutResult, Offset>>()
    var asked = 0
    fun label(text: String, from: Offset, u: Offset, style: TextStyle) {
        val known = if (cache.filled) cache.placed.getOrNull(asked) else null
        asked++
        val laid = known?.first ?: measurer.measure(text, style, maxLines = 1)
        var best: Rect? = known?.second
        if (best == null) {
            val w = laid.size.width.toFloat()
            val h = laid.size.height.toFloat()
            var least = Float.MAX_VALUE
            search@ for (out in listOf(4f, 10f, 18f, 28f, 42f, 60f, 80f, 105f, 135f)) {
                for (side in listOf(0f, 12f, -12f, 22f, -22f, 34f, -34f, 46f, -46f, 60f, -60f, 76f, -76f, 94f, -94f, 115f, -115f)) {
                    val x = from.x + u.x * out - u.y * side
                    val y = from.y + u.y * out + u.x * side
                    val l = when { u.x > 0.3f -> x; u.x < -0.3f -> x - w; else -> x - w / 2 }
                    val t = when { u.y > 0.3f -> y; u.y < -0.3f -> y - h; else -> y - h / 2 }
                    val r = Rect(l - 2f, t, l + w + 2f, t + h)
                    // if nowhere is free, the spot covering least is the one to settle for
                    var covered = 0f
                    for (o in solid) if (o.overlaps(r)) covered += o.intersect(r).let { it.width * it.height }
                    // a leader that would have to run behind a room is as bad as covering something
                    val near = Offset(from.x.coerceIn(r.left, r.right), from.y.coerceIn(r.top, r.bottom))
                    val reach = (near - from).getDistance()
                    if (reach > 9f) for (i in 1..(reach / 5f).toInt()) {
                        val p = from + (near - from) * (i * 5f / reach)
                        if (tiles.any { it.contains(p) }) { covered += 4000f; break }
                    }
                    if (covered < least) { least = covered; best = r }
                    if (covered == 0f) break@search
                }
            }
            solid.add(best!!)
            if (!cache.filled) cache.placed.add(laid to best)
        }
        pending.add(laid to Offset(best.left + 2f, best.top))
        // A label that had to move aside keeps a thin leader back to what it describes.
        val near = Offset(from.x.coerceIn(best.left, best.right), from.y.coerceIn(best.top, best.bottom))
        if ((near - from).getDistance() > 9f) drawLine(muted, from, near, 1f)
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
            LinkKind.STRAIGHT -> {
                val (pa, ua) = port(ca, l.ab?.dir ?: l.ba?.dir?.opposite, cb)
                val (pb, ub) = port(cb, l.ba?.dir ?: l.ab?.dir?.opposite, ca)
                drawLine(ink, pa, pb, 2f)
                if (l.ab?.oneWay == true) arrow(pb, -ub, ink)
                if (l.ba?.oneWay == true) arrow(pa, -ua, ink)
            }
            LinkKind.STAIR -> {
                // from the bottom edge of the upper room to the top edge of the lower one
                val aIsUpper = la > lb
                val (from, to) = scene.stairs.getValue(l)
                drawLine(accent, from, to, 2.6f)
                drawCircle(accent, 3.6f, from)
                drawCircle(accent, 3.6f, to)
                val u = (to - from) / hypot(to.x - from.x, to.y - from.y)
                if ((if (aIsUpper) l.ab else l.ba)?.oneWay == true) arrow(to, u, accent)       // only down
                if ((if (aIsUpper) l.ba else l.ab)?.oneWay == true) arrow(from, -u, accent)    // only up
            }
            LinkKind.STUB -> {
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
    // Ways out the game mentions that have not been tried: a green stub ending in an open ring,
    // or "U?" / "D?" where the direction has no side of its own.
    for (room in map.rooms) {
        val c = scene.centre[room.id] ?: continue
        for (d in room.unexplored) {
            if (map.exit(room.id, d) != null) continue
            if (d.isCompass) {
                val (at, u) = port(c, d, c)
                drawLine(todo, at, at + u * 10f, 2.4f)
                drawCircle(todo, 4.2f, at + u * 14.5f, style = Stroke(2.2f))
            } else {
                val up = d == Dir.UP || d == Dir.IN
                label("${d.label}?", Offset(c.x + TILE_W / 2 - 16f, c.y + if (up) -TILE_D / 2 - 2f else TILE_D / 2 + 2f),
                    Offset(0f, if (up) -1f else 1f), tagStyle.copy(color = todo))
            }
        }
    }
    // What is lying in a room is written beside it, as on Infocom's printed maps.
    for (room in map.rooms) {
        val c = scene.centre[room.id] ?: continue
        if (room.items.isBlank()) continue
        val text = room.items.lines().filter { it.isNotBlank() }.joinToString(", ")
            .let { if (it.length > 34) it.take(33) + "…" else it }
        label(text, Offset(c.x - TILE_W / 2 + 6f, c.y + TILE_D / 2 + 2f), Offset(1f, 1f) / hypot(1f, 1f), itemStyle)
    }
    cache.filled = true
    for ((laid, at) in pending) {
        drawRoundRect(plate, at - Offset(3f, 0f), Size(laid.size.width + 6f, laid.size.height.toFloat()), CornerRadius(3f))
        drawText(laid, topLeft = at)
    }
    for ((id, c) in scene.centre) {
        val shape = path(tile(c))
        val current = id == map.current
        drawPath(shape, if (id in boxes) fold else box)       // a folded tangle is tinted
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
