package com.jrod.droidmapper.model

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Spread the rooms of a tangle out as a web: connected rooms pull together, all rooms push
 * apart, and nothing ends up closer than a clear gap. Position means nothing in a tangle,
 * so this only has to be readable. Returns each room's place in a unit square.
 * The same map always gives the same picture.
 */
fun webLayout(nodes: List<String>, links: Set<Pair<String, String>>): Map<String, Pair<Float, Float>> {
    if (nodes.isEmpty()) return emptyMap()
    if (nodes.size == 1) return mapOf(nodes[0] to (0.5f to 0.5f))
    val rng = Random(7)
    val x = HashMap<String, Float>()
    val y = HashMap<String, Float>()
    for (n in nodes) { x[n] = 200f + rng.nextFloat() * 600f; y[n] = 200f + rng.nextFloat() * 600f }
    val k = sqrt(1_000_000f / nodes.size) * 0.95f
    var temp = 125f
    repeat(600) {
        val mx = HashMap<String, Float>()
        val my = HashMap<String, Float>()
        for (n in nodes) { mx[n] = 0f; my[n] = 0f }
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val a = nodes[i]; val b = nodes[j]
            val dx = x.getValue(a) - x.getValue(b); val dy = y.getValue(a) - y.getValue(b)
            val d = max(1f, hypot(dx, dy))
            val f = k * k / d
            mx[a] = mx.getValue(a) + dx / d * f; my[a] = my.getValue(a) + dy / d * f
            mx[b] = mx.getValue(b) - dx / d * f; my[b] = my.getValue(b) - dy / d * f
        }
        for ((a, b) in links) {
            val dx = x.getValue(a) - x.getValue(b); val dy = y.getValue(a) - y.getValue(b)
            val d = max(1f, hypot(dx, dy))
            val f = d * d / k
            mx[a] = mx.getValue(a) - dx / d * f; my[a] = my.getValue(a) - dy / d * f
            mx[b] = mx.getValue(b) + dx / d * f; my[b] = my.getValue(b) + dy / d * f
        }
        for (n in nodes) {
            val d = max(1f, hypot(mx.getValue(n), my.getValue(n)))
            x[n] = x.getValue(n) + mx.getValue(n) / d * minOf(d, temp)
            y[n] = y.getValue(n) + my.getValue(n) / d * minOf(d, temp)
        }
        temp *= 0.992f
    }
    val x0 = x.values.min(); val x1 = x.values.max(); val y0 = y.values.min(); val y1 = y.values.max()
    val out = nodes.associateWith {
        floatArrayOf((x.getValue(it) - x0) / max(1f, x1 - x0), (y.getValue(it) - y0) / max(1f, y1 - y0))
    }
    val gap = minOf(0.2f, 0.9f / sqrt(nodes.size.toFloat()))
    repeat(200) {
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val a = out.getValue(nodes[i]); val b = out.getValue(nodes[j])
            val dx = a[0] - b[0]; val dy = a[1] - b[1]
            val d = max(1e-4f, hypot(dx, dy))
            if (d < gap) {
                val push = (gap - d) / 2
                a[0] += dx / d * push; a[1] += dy / d * push; b[0] -= dx / d * push; b[1] -= dy / d * push
            }
        }
    }
    val u0 = out.values.minOf { it[0] }; val u1 = out.values.maxOf { it[0] }
    val v0 = out.values.minOf { it[1] }; val v1 = out.values.maxOf { it[1] }
    return out.mapValues { (_, p) -> ((p[0] - u0) / max(1e-4f, u1 - u0)) to ((p[1] - v0) / max(1e-4f, v1 - v0)) }
}
