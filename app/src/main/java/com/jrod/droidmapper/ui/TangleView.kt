package com.jrod.droidmapper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jrod.droidmapper.model.Dir
import com.jrod.droidmapper.model.GameMap
import com.jrod.droidmapper.model.Room
import com.jrod.droidmapper.model.webLayout
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A tangle seen on its own: a web where position means nothing and every line carries the
 * exit to take at each end, and under it a table that answers "I am in room 7 and go west:
 * where am I?". Tapping a row opens that room's card.
 */
@Composable
fun TangleView(
    members: List<Room>, map: GameMap, names: Map<String, String>,
    onEditRoom: (String) -> Unit, onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val inside = remember(members) { members.map { it.id }.toSet() }
    // Short tags: the number a same-named room already carries, e.g. "Maze (7)" is 7.
    val tag = remember(members, names) {
        members.withIndex().associate { (i, r) ->
            r.id to (Regex("\\((\\d+)\\)$").find(names[r.id] ?: "")?.groupValues?.get(1) ?: "${i + 1}")
        }
    }
    val around = remember(map, inside) {
        map.passages.filter { it.to != null && (it.from in inside) != (it.to in inside) }
            .map { if (it.from in inside) it.to!! else it.from }.distinct()
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${members.first().name.trim()}: ${members.size} rooms", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "These rooms share a name and their exits don't come back the way they went, so they are kept off the map and shown here.",
                            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Web(members, around, map, names, tag, Modifier.fillMaxWidth().height(430.dp).padding(8.dp))
                Text("Where each exit leads", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
                Text(
                    "? means the app only assumed it. Tap a room to edit it.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
                )
                Table(members, map, names, tag, onEditRoom)
            }
        }
    }
}

@Composable
private fun Table(members: List<Room>, map: GameMap, names: Map<String, String>, tag: Map<String, String>, onEditRoom: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dirs = Dir.entries.filter { d -> members.any { map.exit(it.id, d) != null } }
    Column(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.background(scheme.surfaceVariant)) {
            Text("From", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(104.dp).padding(6.dp))
            for (d in dirs) {
                Text(d.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.width(74.dp).padding(6.dp))
            }
        }
        for (r in members) {
            val here = r.id == map.current
            Row(Modifier.clickable { onEditRoom(r.id) }.background(if (here) scheme.primaryContainer else scheme.surface)) {
                Text(
                    names[r.id] ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (here) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(104.dp).padding(6.dp),
                )
                for (d in dirs) {
                    val e = map.exit(r.id, d)
                    val to = e?.to
                    val text = when {
                        e == null -> "·"
                        to == null -> "✕"                      // could not go
                        to == r.id -> "↺"                      // straight back here
                        else -> (tag[to] ?: names[to] ?: "") + (if (e.assumed) "?" else "") + (if (e.oneWay) " →" else "")
                    }
                    Text(
                        text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        color = when {
                            e == null -> scheme.outline
                            to != null && to !in tag -> scheme.primary      // a way out of the tangle
                            else -> scheme.onSurface
                        },
                        fontWeight = if (to != null && to !in tag) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(74.dp).padding(6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Web(
    members: List<Room>, around: List<String>, map: GameMap, names: Map<String, String>, tag: Map<String, String>, modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    val nodes = remember(members, around) { members.map { it.id } + around }
    val shown = remember(nodes) { nodes.toSet() }
    val passages = remember(map, shown) {
        map.passages.filter { it.to != null && it.from in shown && it.to in shown && (it.from in tag || it.to in tag) }
    }
    val spots = remember(nodes, passages) {
        webLayout(nodes, passages.filter { it.from != it.to }.map { minOf(it.from, it.to!!) to maxOf(it.from, it.to) }.toSet())
    }
    Canvas(modifier) {
        val pad = 46f * density
        val r = 17f * density
        fun at(id: String): Offset {
            val (u, v) = spots.getValue(id)
            return Offset(pad + u * (size.width - 2 * pad), pad + v * (size.height - 2 * pad))
        }
        val bold = TextStyle(color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        val small = TextStyle(color = scheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        val faint = small.copy(color = scheme.onSurfaceVariant, fontWeight = FontWeight.Normal)
        // rooms outside the tangle are boxes with their names; rooms inside are numbered discs
        val outside = around.associateWith { measurer.measure(names[it] ?: "", small, maxLines = 1) }
        fun half(id: String) = outside[id]?.let { it.size.width / 2f + 7f * density } ?: r
        val solid = nodes.mapTo(ArrayList()) { at(it).let { c -> Rect(c.x - half(it) - 3, c.y - r - 3, c.x + half(it) + 3, c.y + r + 3) } }
        val texts = ArrayList<Pair<androidx.compose.ui.text.TextLayoutResult, Offset>>()
        fun label(s: String, from: Offset, u: Offset, style: TextStyle) {
            val laid = measurer.measure(s, style, maxLines = 1)
            val w = laid.size.width.toFloat(); val h = laid.size.height.toFloat()
            var best: Rect? = null
            for (step in 0..30) {
                val c = from + u * (step * 4f * density)
                val box = Rect(c.x - w / 2, c.y - h / 2, c.x + w / 2, c.y + h / 2)
                if (best == null) best = box
                if (solid.none { it.overlaps(box) }) { best = box; break }
            }
            solid.add(best!!)
            texts.add(laid to best.topLeft)
        }
        fun rim(id: String, u: Offset): Offset {
            val c = at(id)
            val h = half(id)
            if (id !in outside) return c + u * r
            val t = minOf(if (u.x != 0f) h / kotlin.math.abs(u.x) else 1e9f, if (u.y != 0f) (r - 4 * density) / kotlin.math.abs(u.y) else 1e9f)
            return c + u * t
        }
        val pairs = passages.filter { it.from != it.to }.groupBy { setOf(it.from, it.to!!) }
        for ((key, ps) in pairs) {
            val (a, b) = key.toList()
            val d = at(b) - at(a)
            val u = d / hypot(d.x, d.y)
            val p1 = rim(a, u); val p2 = rim(b, -u)
            val ab = ps.filter { it.from == a }; val ba = ps.filter { it.from == b }
            val oneWay = ps.any { it.oneWay }
            val color = if (oneWay) scheme.primary else scheme.onSurface
            drawLine(color, p1, p2, (if (oneWay) 2.4f else 1.8f) * density)
            for (p in ps.filter { it.oneWay }) {            // arrowhead at the room it leads to
                val tip = if (p.to == b) p2 else p1
                val dir = if (p.to == b) u else -u
                val ang = atan2(dir.y, dir.x)
                for (turn in listOf(2.6f, -2.6f)) {
                    drawLine(color, tip, Offset(tip.x + cos(ang + turn) * 9f * density, tip.y + sin(ang + turn) * 9f * density), 2.2f * density)
                }
            }
            // the exit to take from each end; one the app only assumed is fainter
            if (ab.isNotEmpty()) label(ab.joinToString("/") { it.dir.label }, p1 + u * (11f * density), u, if (ab.all { it.assumed }) faint else small)
            if (ba.isNotEmpty()) label(ba.joinToString("/") { it.dir.label }, p2 - u * (11f * density), -u, if (ba.all { it.assumed }) faint else small)
        }
        for (id in nodes) {
            val c = at(id)
            val name = outside[id]
            if (name == null) {
                val here = id == map.current
                drawCircle(scheme.primaryContainer, r, c)
                drawCircle(if (here) scheme.primary else scheme.onSurface, r, c, style = Stroke((if (here) 4f else 1.6f) * density))
                val laid = measurer.measure(tag[id] ?: "", bold)
                drawText(laid, topLeft = Offset(c.x - laid.size.width / 2f, c.y - laid.size.height / 2f))
                val loops = map.exitsOf(id).filter { it.to == id }
                if (loops.isNotEmpty()) label("↺ " + loops.joinToString("/") { it.dir.label }, c + Offset(0f, r + 10f * density), Offset(0f, 1f), faint)
            } else {
                val h = half(id)
                drawRoundRect(scheme.surface, Offset(c.x - h, c.y - r + 4 * density), Size(2 * h, 2 * r - 8 * density), CornerRadius(4f * density))
                drawRoundRect(scheme.onSurface, Offset(c.x - h, c.y - r + 4 * density), Size(2 * h, 2 * r - 8 * density), CornerRadius(4f * density), style = Stroke(1.6f * density))
                drawText(name, topLeft = Offset(c.x - name.size.width / 2f, c.y - name.size.height / 2f))
            }
        }
        for ((laid, p) in texts) {
            drawRect(scheme.background, p, Size(laid.size.width.toFloat(), laid.size.height.toFloat()))
            drawText(laid, topLeft = p)
        }
    }
}
