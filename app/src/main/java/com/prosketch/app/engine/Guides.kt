package com.prosketch.app.engine

import com.prosketch.app.L

import android.graphics.Matrix
import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class GuideType(val label: String) {
    GRID(L.t("2D 격자")), ISOMETRIC(L.t("등각")), PERSPECTIVE(L.t("원근")), SYMMETRY(L.t("대칭"))
}

enum class SymmetryType(val label: String) {
    VERTICAL(L.t("수직")), HORIZONTAL(L.t("수평")), QUADRANT(L.t("사분면")), RADIAL(L.t("방사형"))
}

class GuideConfig {
    var enabled = false
    var type = GuideType.GRID
    var gridSize = 120f
    var color = 0xFF3FA9F5.toInt()
    var opacity = 0.65f
    var thickness = 1.5f
    var assist = false
    var symmetry = SymmetryType.VERTICAL
    var rotational = false
    var radialSegments = 8
    /** Guide origin (symmetry centre / grid origin), in document pixels. NaN = canvas centre. */
    var cx = Float.NaN
    var cy = Float.NaN
    var rotationDeg = 0f
    val vanishingPoints = mutableListOf<PointF>()

    fun centerX(w: Int) = if (cx.isNaN()) w / 2f else cx
    fun centerY(h: Int) = if (cy.isNaN()) h / 2f else cy

    fun copyFrom(o: GuideConfig) {
        enabled = o.enabled; type = o.type; gridSize = o.gridSize; color = o.color; opacity = o.opacity
        thickness = o.thickness; assist = o.assist; symmetry = o.symmetry; rotational = o.rotational
        radialSegments = o.radialSegments; cx = o.cx; cy = o.cy; rotationDeg = o.rotationDeg
        vanishingPoints.clear(); o.vanishingPoints.forEach { vanishingPoints.add(PointF(it.x, it.y)) }
    }

    /** Symmetry transforms (including identity) used to replicate dabs. */
    fun symmetryTransforms(w: Int, h: Int): List<Matrix> {
        if (!enabled || type != GuideType.SYMMETRY || !assist) return listOf(Matrix())
        val c = PointF(centerX(w), centerY(h))
        fun about(block: Matrix.() -> Unit): Matrix = Matrix().apply {
            postTranslate(-c.x, -c.y)
            postRotate(-rotationDeg)
            block()
            postRotate(rotationDeg)
            postTranslate(c.x, c.y)
        }
        val list = ArrayList<Matrix>()
        list.add(Matrix())
        when (symmetry) {
            SymmetryType.VERTICAL ->
                list.add(if (rotational) about { postRotate(180f) } else about { postScale(-1f, 1f) })
            SymmetryType.HORIZONTAL ->
                list.add(if (rotational) about { postRotate(180f) } else about { postScale(1f, -1f) })
            SymmetryType.QUADRANT -> if (rotational) {
                list.add(about { postRotate(90f) }); list.add(about { postRotate(180f) }); list.add(about { postRotate(270f) })
            } else {
                list.add(about { postScale(-1f, 1f) }); list.add(about { postScale(1f, -1f) }); list.add(about { postScale(-1f, -1f) })
            }
            SymmetryType.RADIAL -> {
                val n = radialSegments.coerceIn(2, 32)
                if (rotational) {
                    for (k in 1 until n) list.add(about { postRotate(360f * k / n) })
                } else {
                    val half = maxOf(1, n / 2)
                    for (k in 0 until half) {
                        if (k > 0) list.add(about { postRotate(360f * k / half) })
                        list.add(about { postScale(-1f, 1f); postRotate(360f * k / half) })
                    }
                }
            }
        }
        return list
    }

    /** Whether strokes should be snapped to guide directions. */
    val snaps: Boolean get() = enabled && assist && type != GuideType.SYMMETRY
}

/**
 * Drawing Assist: decides a constraint line once the stroke has moved far enough,
 * then projects every later point onto that line.
 */
class SnapAssist(private val cfg: GuideConfig, private val docW: Int, private val docH: Int, private val decideDist: Float) {
    private var start: PointF? = null
    private var dirX = 0f
    private var dirY = 0f
    var decided = false
        private set

    fun reset() { start = null; decided = false }

    /** Returns null while undecided (point should be buffered). */
    fun project(x: Float, y: Float): PointF? {
        val s = start ?: run { start = PointF(x, y); return null }
        if (!decided) {
            val dx = x - s.x; val dy = y - s.y
            if (hypot(dx, dy) < decideDist) return null
            decide(s, dx, dy)
        }
        val px = x - s.x; val py = y - s.y
        val t = px * dirX + py * dirY
        return PointF(s.x + dirX * t, s.y + dirY * t)
    }

    fun startPoint(): PointF? = start

    private fun decide(s: PointF, dx: Float, dy: Float) {
        val candidates = ArrayList<Float>() // angles in radians
        val rot = (cfg.rotationDeg * PI / 180).toFloat()
        when (cfg.type) {
            GuideType.GRID -> { candidates += rot; candidates += rot + (PI / 2).toFloat() }
            GuideType.ISOMETRIC -> {
                candidates += rot + (PI / 6).toFloat(); candidates += rot + (PI / 2).toFloat(); candidates += rot + (5 * PI / 6).toFloat()
            }
            GuideType.PERSPECTIVE -> {
                for (vp in cfg.vanishingPoints) candidates += atan2(vp.y - s.y, vp.x - s.x)
                if (cfg.vanishingPoints.size < 3) candidates += (PI / 2).toFloat()
                if (cfg.vanishingPoints.size <= 1) candidates += 0f
            }
            GuideType.SYMMETRY -> {}
        }
        if (candidates.isEmpty()) candidates += atan2(dy, dx)
        val a = atan2(dy, dx)
        var best = candidates[0]
        var bestDiff = Float.MAX_VALUE
        for (c in candidates) {
            // lines are undirected, so |sin| of the angle difference measures misalignment
            val d = abs(sin(a - c))
            if (d < bestDiff) { bestDiff = d; best = c }
        }
        dirX = cos(best); dirY = sin(best)
        decided = true
    }
}

enum class AnimLoop(val label: String) { LOOP(L.t("반복")), PINGPONG(L.t("핑퐁")), ONESHOT(L.t("한 번")) }

class AnimationConfig {
    var enabled = false
    var fps = 12                       // 1..60
    var onionFrames = 2                // 0..12
    var onionOpacity = 0.45f
    var loop = AnimLoop.LOOP
    var colorOnion = true
    var onionBefore = 0xFFE5484D.toInt()   // frames behind (Procreate default red)
    var onionAfter = 0xFF30A46C.toInt()    // frames ahead (green)
    var blendPrimary = false           // off: the current frame sits solid on top of the onion skins
    var firstFrameBackground = false
    var lastFrameForeground = false
}
