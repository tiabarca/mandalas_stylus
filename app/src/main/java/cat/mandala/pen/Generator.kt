package cat.mandala.pen

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class Generated(val sectors: Int, val rings: List<List<Stroke>>)

/**
 * Genera línia (per pintar) en anells concèntrics tancats. Cada motiu es defineix dins d'una sola cunya
 * (angle 0..π/n) i la simetria radial + mirall del [Board] el repeteix. Cada anell té un cercle base
 * perquè les zones quedin tancades i es puguin omplir.
 */
object Generator {
    private const val PI_F = PI.toFloat()

    fun generate(seed: Long, radius: Float, lineWidth: Float): Generated {
        val rnd = Random(seed)
        val n = listOf(6, 8, 8, 10, 12, 12, 14, 16, 18).random(rnd)
        val sec = PI_F / n
        val rings = ArrayList<List<Stroke>>()

        var r = radius * 0.05f
        var idx = 0
        val outer = radius * 0.93f
        while (r < outer - radius * 0.03f) {
            val band = min(radius * (0.07f + 0.09f * rnd.nextFloat()), outer - r)
            if (band < radius * 0.035f) break
            val w = lineWidth * (0.8f + 0.5f * rnd.nextFloat())
            val last = r + band >= outer - 1f
            val kind = if (idx == 0) 0 else if (last) 7 + rnd.nextInt(2) else rnd.nextInt(7)
            val group = ArrayList<Stroke>()
            group += arc(n, r, sec, w * 0.85f) // cercle base: tanca la zona
            group += when (kind) {
                0 -> petals(n, sec, r, band, w)
                1 -> beads(n, sec, r, band, w)
                2 -> listOf(arc(n, r + band * 0.5f, sec, w * 0.7f))
                3 -> listOf(wave(n, r + band * 0.5f, band * 0.4f, 1 + rnd.nextInt(3), sec, w))
                4 -> listOf(star(n, r, r + band, sec, w), star(n, r + band * 0.2f, r + band * 0.7f, sec, w * 0.7f))
                5 -> listOf(scallop(n, r, band, sec, w))
                6 -> offsetPetals(n, sec, r, band, w)
                7 -> listOf(scallop(n, r, band, sec, w))
                else -> listOf(star(n, r, r + band, sec, w))
            }
            rings += group
            r += band
            idx++
        }
        rings += listOf(listOf(arc(n, r, sec, lineWidth * 1.1f), arc(n, radius * 0.97f, sec, lineWidth * 1.3f)))
        return Generated(n, rings)
    }

    private fun stroke(n: Int, mirror: Boolean, pts: List<P>) =
        Stroke(0, n, mirror, Tool.PEN).also { it.pts.addAll(pts) }

    private fun polar(r: Float, th: Float, w: Float) = P(r * cos(th), r * sin(th), w)

    /** Mitja silueta d'un pètal que creix per l'eix X. */
    private fun halfPetal(r0: Float, len: Float, half: Float, w: Float): List<P> {
        val steps = 28
        return (0..steps).map { i ->
            val t = i / steps.toFloat()
            P(r0 + len * t, half * abs(sin(PI_F * t)).pow(0.85f) * (1f - 0.3f * t), w)
        }
    }

    private fun petals(n: Int, sec: Float, r: Float, band: Float, w: Float): List<Stroke> {
        val half = min(band * 0.5f, (r + band * 0.3f) * sin(sec) * 0.92f)
        return listOf(
            stroke(n, true, halfPetal(r, band, half, w)),
            stroke(n, true, halfPetal(r + band * 0.18f, band * 0.6f, half * 0.5f, w * 0.7f)),
        )
    }

    /** Pètals decalats mig sector (silueta completa, sense mirall). */
    private fun offsetPetals(n: Int, sec: Float, r: Float, band: Float, w: Float): List<Stroke> {
        val half = min(band * 0.5f, (r + band * 0.3f) * sin(sec) * 0.92f)
        val h = halfPetal(r, band, half, w)
        val full = h + h.reversed().map { P(it.x, -it.y, it.w) }
        val rot = full.map { p -> P(p.x * cos(sec) - p.y * sin(sec), p.x * sin(sec) + p.y * cos(sec), p.w) }
        return listOf(stroke(n, false, rot))
    }

    private fun beads(n: Int, sec: Float, r: Float, band: Float, w: Float): List<Stroke> {
        val cx = r + band * 0.5f
        val rad = min(band * 0.34f, cx * sin(sec) * 0.8f)
        fun circle(radius: Float, lw: Float) =
            stroke(n, false, (0..24).map { i ->
                val a = 2f * PI_F * i / 24f
                P(cx + radius * cos(a), radius * sin(a), lw)
            })
        return listOf(circle(rad, w), circle(rad * 0.4f, w * 0.8f))
    }

    private fun arc(n: Int, radius: Float, sec: Float, w: Float) =
        stroke(n, true, (0..8).map { polar(radius, sec * it / 8f, w) })

    private fun wave(n: Int, radius: Float, amp: Float, m: Int, sec: Float, w: Float) =
        stroke(n, true, (0..32).map {
            val t = it / 32f
            polar(radius + amp * cos(m * PI_F * t), sec * t, w)
        })

    private fun star(n: Int, r0: Float, r1: Float, sec: Float, w: Float) =
        stroke(n, true, listOf(polar(r0, sec, w), polar(r1, 0f, w)))

    private fun scallop(n: Int, radius: Float, depth: Float, sec: Float, w: Float) =
        stroke(n, true, (0..24).map {
            val t = it / 24f
            polar(radius + depth * abs(cos(PI_F * t / 2f)).pow(0.6f), sec * t, w)
        })
}
