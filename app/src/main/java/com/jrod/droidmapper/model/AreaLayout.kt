package com.jrod.droidmapper.model

import kotlin.math.abs
import kotlin.math.max

data class Cell(val x: Int, val y: Int)

/** Where a room is drawn: which area it belongs to and its cell on that area's own grid. */
data class Placed(val area: Int, val x: Int, val y: Int)

private const val MAX_GAP = 3       // how far apart two areas may be set down when a passage joins them
private const val DOCK_CAP = 6      // a group hung off a room by stairs is released once it grows past this

private val UP_CELLS = listOf(Cell(0, -1), Cell(1, -1), Cell(-1, -1), Cell(1, 0), Cell(-1, 0))
private val DOWN_CELLS = listOf(Cell(0, 1), Cell(1, 1), Cell(-1, 1), Cell(1, 0), Cell(-1, 0))
private val ANY_CELLS = listOf(
    Cell(1, 0), Cell(-1, 0), Cell(0, 1), Cell(0, -1), Cell(1, 1), Cell(-1, 1), Cell(1, -1), Cell(-1, -1),
)

/**
 * Positions every room from moves alone. The player never places anything.
 *
 * Rooms live in areas, each with its own grid. A passage between two areas is drawn as a
 * label at each end, the way printed maps link their separate sheets.
 *
 *  - A compass move to a new room puts it next to where you came from, opening a row or
 *    column in that area if the cell is taken.
 *  - Up/down/in/out is a weak hint: the new room is set beside the room it hangs off when
 *    a cell is free. It, and whatever grows from it, is released into its own area as soon
 *    as that would collide with something, block a straight line, grow too big, or a
 *    compass passage says it belongs elsewhere.
 *  - A compass move that arrives in a known room of another area joins the two areas, if
 *    the smaller can be set down so the passage is a clear straight line and nothing breaks.
 *  - A compass move that contradicts the drawing slides the small branch hanging off one
 *    end, if that makes more passages line up.
 *  - After every move, stretched straight passages are shortened where nothing is lost.
 *
 * This is a port of research/islands.py, and LayoutParityTest holds it to that prototype's
 * exact output on a 302-move Zork I walk.
 */
class AreaLayout(start: String, private val dock: Boolean = true) {
    var pos = LinkedHashMap<String, Cell>()
        private set
    var area = LinkedHashMap<String, Int>()
        private set
    private val parent = HashMap<String, String?>()
    private val known = ArrayList<Triple<String, Dir, String>>()   // compass passages walked
    private val tethers = ArrayList<Pair<String, String>>()        // up/down/in/out links
    private val docked = HashSet<String>()                         // roots of groups set down by a weak hint
    private var nextId = 1

    init {
        pos[start] = Cell(0, 0); area[start] = 0; parent[start] = null
    }

    fun placed(): Map<String, Placed> = pos.mapValues { (r, c) -> Placed(area.getValue(r), c.x, c.y) }

    // ---- helpers ------------------------------------------------------------------

    private fun members(i: Int, of: Map<String, Int> = area): List<String> = of.filterValues { it == i }.keys.toList()
    private fun taken(i: Int): HashSet<Cell> = members(i).mapTo(HashSet()) { pos.getValue(it) }

    private fun score(p: Map<String, Cell>, a: Map<String, Int>): Int {
        val takenBy = HashMap<Int, HashSet<Cell>>()
        for ((r, c) in p) takenBy.getOrPut(a.getValue(r)) { HashSet() }.add(c)
        return known.count { (x, d, y) -> a[x] == a[y] && clear(p, takenBy.getValue(a.getValue(x)), x, y, d) }
    }

    private fun subtree(root: String): Set<String> {
        val kids = HashMap<String?, MutableList<String>>()
        for ((r, p) in parent) kids.getOrPut(p) { ArrayList() }.add(r)
        val out = HashSet<String>()
        val stack = ArrayDeque(listOf(root))
        while (stack.isNotEmpty()) {
            val r = stack.removeLast()
            if (out.add(r)) kids[r]?.let(stack::addAll)
        }
        return out
    }

    private fun dockRoot(r: String): String? {
        var x: String? = r
        while (x != null) {
            if (x in docked) return x
            x = parent[x]
        }
        return null
    }

    private fun dockGroup(root: String): List<String> {
        val tree = subtree(root)
        return members(area.getValue(root)).filter { it in tree }
    }

    private fun newArea(room: String) {
        area[room] = nextId++; pos[room] = Cell(0, 0); parent[room] = null
    }

    // ---- operations ---------------------------------------------------------------

    private fun tryDock(frm: String, d: Dir, to: String): Boolean {
        val i = area.getValue(frm)
        val full = taken(i)
        val before = score(pos, area)
        val at = pos.getValue(frm)
        val cells = when (d) { Dir.UP -> UP_CELLS; Dir.DOWN -> DOWN_CELLS; else -> ANY_CELLS }
        for (v in cells) {
            val c = Cell(at.x + v.x, at.y + v.y)
            if (c in full) continue
            pos[to] = c; area[to] = i
            if (score(pos, area) < before) {           // it would sit on someone's line
                pos.remove(to); area.remove(to)
                continue
            }
            parent[to] = frm
            docked.add(to)
            return true
        }
        return false
    }

    /** Release a docked group into its own area, keeping its shape. */
    private fun eject(root: String) {
        for (r in dockGroup(root)) area[r] = nextId
        nextId++
        parent[root] = null
        docked.remove(root)
    }

    private fun openSpace(i: Int, want: Cell, d: Dir) {
        for (r in members(i)) {
            val c = pos.getValue(r)
            if (d.dx != 0) {
                if (if (d.dx > 0) c.x >= want.x else c.x <= want.x) pos[r] = Cell(c.x + d.dx, c.y)
            } else if (if (d.dy > 0) c.y >= want.y else c.y <= want.y) {
                pos[r] = Cell(c.x, c.y + d.dy)
            }
        }
    }

    private fun tryMerge(frm: String, d: Dir, to: String) {
        val a = area.getValue(frm)
        val b = area.getValue(to)
        val ma = members(a)
        val mb = members(b)
        val before = score(pos, area)
        // the smaller area is the one that gets picked up and set down
        val moveTo = mb.size <= ma.size
        val group = if (moveTo) mb else ma
        val anchor = if (moveTo) frm else to
        val mover = if (moveTo) to else frm
        val sign = if (moveTo) 1 else -1
        val into = if (moveTo) a else b
        val others = members(into).mapTo(HashSet()) { pos.getValue(it) }
        for (k in 1..MAX_GAP) {
            val tx = pos.getValue(anchor).x + sign * d.dx * k - pos.getValue(mover).x
            val ty = pos.getValue(anchor).y + sign * d.dy * k - pos.getValue(mover).y
            val trial = LinkedHashMap(pos)
            val trialArea = LinkedHashMap(area)
            for (r in group) {
                val c = pos.getValue(r)
                trial[r] = Cell(c.x + tx, c.y + ty)
                trialArea[r] = into
            }
            if (group.any { trial.getValue(it) in others }) continue
            if (score(trial, trialArea) > before) {     // the new passage is clear and nothing else broke
                pos = trial; area = trialArea
                return
            }
        }
    }

    private fun tryRehang(frm: String, d: Dir, to: String) {
        val i = area.getValue(frm)
        val before = score(pos, area)
        var bestGain = 0
        var bestSize = 0
        var bestTrial: LinkedHashMap<String, Cell>? = null
        var bestMover = ""
        var bestAnchor = ""
        for ((anchor, mover, sign) in listOf(Triple(frm, to, 1), Triple(to, frm, -1))) {
            val inArea = members(i)
            val tree = subtree(mover)
            val group = inArea.filter { it in tree }
            if (anchor in group) continue
            val groupSet = group.toHashSet()
            val others = inArea.filter { it !in groupSet }.mapTo(HashSet()) { pos.getValue(it) }
            for (k in 1..7) {
                val tx = pos.getValue(anchor).x + sign * d.dx * k - pos.getValue(mover).x
                val ty = pos.getValue(anchor).y + sign * d.dy * k - pos.getValue(mover).y
                val trial = LinkedHashMap(pos)
                for (r in group) {
                    val c = pos.getValue(r)
                    trial[r] = Cell(c.x + tx, c.y + ty)
                }
                if (group.any { trial.getValue(it) in others }) continue
                val gain = score(trial, area) - before
                if (gain > 0 && (bestTrial == null || gain > bestGain || (gain == bestGain && group.size < bestSize))) {
                    bestGain = gain; bestSize = group.size; bestTrial = trial; bestMover = mover; bestAnchor = anchor
                }
                break
            }
        }
        bestTrial?.let {
            pos = it
            parent[bestMover] = bestAnchor
        }
    }

    /** Shorten stretched straight passages in area [i], one step at a time; nothing is ever stretched. */
    private fun tighten(i: Int) {
        repeat(40) {
            val mem = members(i).toHashSet()
            if (mem.size < 3) return
            val full = mem.mapTo(HashSet()) { pos.getValue(it) }
            val links = known.filter { (a, d, b) -> a in mem && b in mem && clear(pos, full, a, b, d) }
            val rigid = HashMap<String, MutableSet<String>>()
            fun tie(a: String, b: String) {
                rigid.getOrPut(a) { HashSet() }.add(b); rigid.getOrPut(b) { HashSet() }.add(a)
            }
            for ((a, _, b) in links) tie(a, b)
            for ((a, b) in tethers) if (a in mem && b in mem) tie(a, b)
            val before = score(pos, area)
            var moved = false
            for ((a, d, b) in links) {
                val pa = pos.getValue(a)
                val pb = pos.getValue(b)
                if (max(abs(pb.x - pa.x), abs(pb.y - pa.y)) < 2) continue
                val group = hashSetOf(b)                 // what must travel with b
                val stack = ArrayDeque(listOf(b))
                while (stack.isNotEmpty()) {
                    val r = stack.removeLast()
                    for (q in rigid[r].orEmpty()) {
                        val theLink = (r == a && q == b) || (r == b && q == a)
                        if (q !in group && !theLink) { group.add(q); stack.add(q) }
                    }
                }
                if (a in group) continue                 // held open by a loop of passages
                val trial = LinkedHashMap(pos)
                for (r in group) {
                    val c = pos.getValue(r)
                    trial[r] = Cell(c.x - d.dx, c.y - d.dy)
                }
                val rest = mem.filter { it !in group }.mapTo(HashSet()) { pos.getValue(it) }
                if (group.any { trial.getValue(it) in rest }) continue
                if (score(trial, area) < before) continue
                pos = trial
                moved = true
                break
            }
            if (!moved) return
        }
    }

    /** The player is somewhere new by means no passage describes: a fresh area. */
    fun arrive(room: String) {
        if (room !in pos) newArea(room)
    }

    /** Leaving [frm] by [d] led to [to]. */
    fun move(frm: String, d: Dir, to: String) {
        if (to == frm) return
        val isNew = to !in pos
        if (!d.isCompass) tethers.add(frm to to)
        step(frm, d, to, isNew)
        for (i in sortedSetOf(area.getValue(frm), area.getValue(to))) tighten(i)
    }

    private fun step(frm: String, d: Dir, to: String, isNew: Boolean) {
        val soft = !d.isCompass
        if (isNew) {
            if (soft) {
                // With docking off (the floors map), stairs always lead to a separate area.
                if (!(dock && tryDock(frm, d, to))) newArea(to)
                return
            }
            fun wanted() = Cell(pos.getValue(frm).x + d.dx, pos.getValue(frm).y + d.dy)
            var want = wanted()
            if (want in taken(area.getValue(frm))) {
                dockRoot(frm)?.let(::eject)             // no room to grow here: go and be your own area
                if (want in taken(area.getValue(frm))) {
                    openSpace(area.getValue(frm), want, d)
                    want = wanted()
                }
            }
            val before = score(pos, area)
            area[to] = area.getValue(frm); pos[to] = want; parent[to] = frm
            known.add(Triple(frm, d, to))
            val root = dockRoot(to)
            if (root != null && (dockGroup(root).size > DOCK_CAP || score(pos, area) < before + 1)) eject(root)
            return
        }
        if (soft) return
        known.add(Triple(frm, d, to))
        val rf = dockRoot(frm)
        val rt = dockRoot(to)
        if (area[frm] != area[to]) {
            for (r in listOfNotNull(rf, rt).distinct()) eject(r)   // a docked group joins on its own, not with its host
            tryMerge(frm, d, to)
            return
        }
        if (clear(pos, taken(area.getValue(frm)), frm, to, d)) return
        tryRehang(frm, d, to)
        if (rf != rt && !clear(pos, taken(area.getValue(frm)), frm, to, d)) {
            // a compass passage outranks the weak hint: release and set down properly
            for (r in listOfNotNull(rf, rt).distinct()) eject(r)
            if (area[frm] != area[to]) tryMerge(frm, d, to)
        }
    }
}

/** Is [b] in direction [d] from [a] with no other room on the line between them? */
fun clear(pos: Map<String, Cell>, taken: Set<Cell>, a: String, b: String, d: Dir): Boolean {
    val pa = pos.getValue(a)
    val pb = pos.getValue(b)
    val dx = pb.x - pa.x
    val dy = pb.y - pa.y
    if (dx * d.dy != dy * d.dx || dx * d.dx + dy * d.dy <= 0) return false
    var c = Cell(pa.x + d.dx, pa.y + d.dy)
    while (c != pb) {
        if (c in taken) return false
        c = Cell(c.x + d.dx, c.y + d.dy)
    }
    return true
}

/**
 * Where every room of [map] is drawn. Replays the walked passages in the order they were
 * learned; a way back the app only assumed says nothing new and is skipped.
 */
fun layoutOf(map: GameMap, dock: Boolean = true): Map<String, Placed> {
    val engine = AreaLayout(map.rooms.first().id, dock)
    val used = HashSet<Pair<String, Dir>>()
    for (p in map.passages) {
        val to = p.to ?: continue
        if (p.assumed) continue
        // A folded tangle can leave by one direction to several places; only the first can
        // steer the layout, the rest are drawn as labels.
        if (!used.add(p.from to p.dir)) continue
        engine.arrive(p.from)
        engine.move(p.from, p.dir, to)
    }
    for (r in map.rooms) engine.arrive(r.id)
    return engine.placed()
}
