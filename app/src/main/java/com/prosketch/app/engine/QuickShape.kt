package com.prosketch.app.engine

import com.prosketch.app.L

import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A recognised QuickShape. All coordinates are in document pixels. */
sealed class QShape {
    abstract val label: String
    abstract fun path(): List<PointF>
    abstract fun center(): PointF
    abstract fun mapped(f: (PointF) -> PointF): QShape
    /** "Perfect" version: circle, square, equilateral triangle, 15°-snapped line. */
    abstract fun perfect(): QShape

    data class Line(val a: PointF, val b: PointF) : QShape() {
        override val label = L.t("직선")
        override fun path() = listOf(a, b)
        override fun center() = PointF((a.x + b.x) / 2, (a.y + b.y) / 2)
        override fun mapped(f: (PointF) -> PointF) = Line(f(a), f(b))
        override fun perfect(): QShape {
            val len = hypot(b.x - a.x, b.y - a.y)
            val ang = atan2(b.y - a.y, b.x - a.x)
            val step = (PI / 12).toFloat()
            val snapped = (ang / step).roundToInt() * step
            return Line(a, PointF(a.x + cos(snapped) * len, a.y + sin(snapped) * len))
        }
    }

    data class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val rot: Float) : QShape() {
        override val label = if (abs(rx - ry) < 0.02f * max(rx, ry)) L.t("원") else L.t("타원")
        override fun path(): List<PointF> {
            val n = (max(rx, ry) * 0.35f).toInt().coerceIn(48, 400)
            val c = cos(rot); val s = sin(rot)
            return (0..n).map { i ->
                val t = 2 * PI.toFloat() * i / n
                val x = cos(t) * rx; val y = sin(t) * ry
                PointF(cx + x * c - y * s, cy + x * s + y * c)
            }
        }
        override fun center() = PointF(cx, cy)
        override fun mapped(f: (PointF) -> PointF): QShape {
            // map centre and the two axis end points
            val c = cos(rot); val s = sin(rot)
            val p0 = f(PointF(cx, cy))
            val px = f(PointF(cx + rx * c, cy + rx * s))
            val py = f(PointF(cx - ry * s, cy + ry * c))
            return Ellipse(p0.x, p0.y, hypot(px.x - p0.x, px.y - p0.y), hypot(py.x - p0.x, py.y - p0.y), atan2(px.y - p0.y, px.x - p0.x))
        }
        override fun perfect(): QShape { val r = (rx + ry) / 2; return Ellipse(cx, cy, r, r, rot) }
    }

    data class Polygon(val pts: List<PointF>, val closed: Boolean) : QShape() {
        override val label = when {
            !closed -> L.t("폴리라인")
            pts.size == 3 -> L.t("삼각형")
            pts.size == 4 -> L.t("사각형")
            else -> L.t("다각형")
        }
        override fun path() = if (closed) pts + pts.first() else pts
        override fun center(): PointF {
            var x = 0f; var y = 0f
            for (p in pts) { x += p.x; y += p.y }
            return PointF(x / pts.size, y / pts.size)
        }
        override fun mapped(f: (PointF) -> PointF) = Polygon(pts.map(f), closed)
        override fun perfect(): QShape {
            if (!closed || pts.size !in 3..5) {
                // snap each segment of a polyline to 15°
                if (pts.size < 2) return this
                val out = ArrayList<PointF>()
                out.add(pts[0])
                for (i in 1 until pts.size) {
                    val a = out.last(); val b = pts[i]
                    val l = Line(a, b).perfect() as Line
                    out.add(l.b)
                }
                return Polygon(out, closed)
            }
            val c = center()
            val n = pts.size
            var r = 0f
            for (p in pts) r += hypot(p.x - c.x, p.y - c.y)
            r /= n
            val a0 = atan2(pts[0].y - c.y, pts[0].x - c.x)
            // keep winding direction of the user's drawing
            val area = signedArea(pts)
            val dir = if (area >= 0) 1f else -1f
            return Polygon((0 until n).map { i ->
                val a = a0 + dir * 2 * PI.toFloat() * i / n
                PointF(c.x + cos(a) * r, c.y + sin(a) * r)
            }, true)
        }
    }

    data class Arc(val cx: Float, val cy: Float, val r: Float, val start: Float, val sweep: Float) : QShape() {
        override val label = L.t("호")
        override fun path(): List<PointF> {
            val n = (r * abs(sweep) * 0.3f).toInt().coerceIn(16, 300)
            return (0..n).map { i ->
                val a = start + sweep * i / n
                PointF(cx + cos(a) * r, cy + sin(a) * r)
            }
        }
        override fun center() = PointF(cx, cy)
        override fun mapped(f: (PointF) -> PointF): QShape {
            val c = f(PointF(cx, cy))
            val s = f(PointF(cx + cos(start) * r, cy + sin(start) * r))
            val e = f(PointF(cx + cos(start + sweep) * r, cy + sin(start + sweep) * r))
            val nr = hypot(s.x - c.x, s.y - c.y)
            val ns = atan2(s.y - c.y, s.x - c.x)
            var ne = atan2(e.y - c.y, e.x - c.x) - ns
            if (sweep > 0 && ne < 0) ne += 2 * PI.toFloat()
            if (sweep < 0 && ne > 0) ne -= 2 * PI.toFloat()
            return Arc(c.x, c.y, nr, ns, ne)
        }
        override fun perfect(): QShape = Ellipse(cx, cy, r, r, 0f)
    }
}

private fun signedArea(p: List<PointF>): Float {
    var a = 0f
    for (i in p.indices) {
        val j = (i + 1) % p.size
        a += p[i].x * p[j].y - p[j].x * p[i].y
    }
    return a / 2
}

object QuickShapeRecognizer {

    fun recognize(raw: List<PointF>): QShape? {
        if (raw.size < 2) return null
        val len = pathLength(raw)
        if (len < 12f) return null
        val pts = resample(raw, 96)
        val a = pts.first(); val b = pts.last()
        val chord = hypot(b.x - a.x, b.y - a.y)

        // 1) Line
        val lineErr = pts.maxOf { distToSegment(it, a, b) }
        if (chord > 0.8f * len && lineErr < max(4f, 0.07f * len)) return QShape.Line(PointF(a.x, a.y), PointF(b.x, b.y))

        val closed = chord < 0.2f * len
        if (closed) {
            val ell = fitEllipse(pts)
            val ellErr = ell?.let { ellipseError(pts, it) } ?: Float.MAX_VALUE
            val loop = pts.dropLast(1)
            val corners = closedCorners(loop, len)
            if (corners != null && corners.size in 3..5) {
                val polyErr = polygonError(pts, corners)
                if (polyErr < ellErr * 0.85f) return QShape.Polygon(corners, true)
            }
            if (ell != null && ellErr < 0.06f * len / (2 * PI.toFloat()) + 6f) return ell
            if (corners != null && corners.size in 3..8) return QShape.Polygon(corners, true)
            return ell
        }

        // open shapes: polyline or arc
        val simple = douglasPeucker(pts, max(3f, 0.035f * len))
        if (simple.size in 3..6) {
            val err = pts.maxOf { p -> (0 until simple.size - 1).minOf { distToSegment(p, simple[it], simple[it + 1]) } }
            val angles = (1 until simple.size - 1).map { cornerAngle(simple[it - 1], simple[it], simple[it + 1]) }
            if (err < 0.04f * len && angles.all { it < 150f }) return QShape.Polygon(simple, false)
        }
        val arc = fitArc(pts)
        if (arc != null) return arc
        return if (simple.size in 2..8) QShape.Polygon(simple, false) else null
    }

    private fun pathLength(p: List<PointF>): Float {
        var l = 0f
        for (i in 1 until p.size) l += hypot(p[i].x - p[i - 1].x, p[i].y - p[i - 1].y)
        return l
    }

    fun resample(p: List<PointF>, n: Int): List<PointF> {
        val total = pathLength(p)
        if (total <= 0f) return listOf(p.first(), p.last())
        val step = total / (n - 1)
        val out = ArrayList<PointF>(n)
        out.add(PointF(p[0].x, p[0].y))
        var acc = 0f
        var prev = p[0]
        var i = 1
        while (i < p.size) {
            val cur = p[i]
            val d = hypot(cur.x - prev.x, cur.y - prev.y)
            if (acc + d >= step && d > 0f) {
                val t = (step - acc) / d
                val q = PointF(prev.x + (cur.x - prev.x) * t, prev.y + (cur.y - prev.y) * t)
                out.add(q)
                prev = q
                acc = 0f
            } else {
                acc += d
                prev = cur
                i++
            }
        }
        while (out.size < n) out.add(PointF(p.last().x, p.last().y))
        return out
    }

    private fun distToSegment(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x; val dy = b.y - a.y
        val l2 = dx * dx + dy * dy
        if (l2 <= 1e-6f) return hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    private fun cornerAngle(a: PointF, b: PointF, c: PointF): Float {
        val a1 = atan2(a.y - b.y, a.x - b.x); val a2 = atan2(c.y - b.y, c.x - b.x)
        var d = abs(a1 - a2)
        if (d > PI) d = (2 * PI).toFloat() - d
        return Math.toDegrees(d.toDouble()).toFloat()
    }

    fun douglasPeucker(p: List<PointF>, eps: Float): List<PointF> {
        if (p.size < 3) return p
        var maxD = 0f; var idx = 0
        for (i in 1 until p.size - 1) {
            val d = distToSegment(p[i], p.first(), p.last())
            if (d > maxD) { maxD = d; idx = i }
        }
        return if (maxD > eps) {
            val l = douglasPeucker(p.subList(0, idx + 1), eps)
            val r = douglasPeucker(p.subList(idx, p.size), eps)
            l.dropLast(1) + r
        } else listOf(p.first(), p.last())
    }

    /** Finds polygon corners of a closed loop. Rotates the loop so it starts at the sharpest corner. */
    private fun closedCorners(loop: List<PointF>, len: Float): List<PointF>? {
        if (loop.size < 8) return null
        // start from the point farthest from the centroid → likely a corner
        var cx = 0f; var cy = 0f
        for (p in loop) { cx += p.x; cy += p.y }
        cx /= loop.size; cy /= loop.size
        var far = 0; var farD = 0f
        for (i in loop.indices) { val d = hypot(loop[i].x - cx, loop[i].y - cy); if (d > farD) { farD = d; far = i } }
        val rot = loop.subList(far, loop.size) + loop.subList(0, far) + loop[far]
        val simple = douglasPeucker(rot, max(4f, 0.045f * len)).dropLast(1)
        if (simple.size < 3) return null
        // drop near-straight vertices
        val filtered = simple.filterIndexed { i, p ->
            val a = simple[(i - 1 + simple.size) % simple.size]; val c = simple[(i + 1) % simple.size]
            cornerAngle(a, p, c) < 160f
        }
        return filtered.map { PointF(it.x, it.y) }
    }

    private fun polygonError(pts: List<PointF>, poly: List<PointF>): Float {
        var e = 0f
        for (p in pts) {
            var m = Float.MAX_VALUE
            for (i in poly.indices) m = min(m, distToSegment(p, poly[i], poly[(i + 1) % poly.size]))
            e += m
        }
        return e / pts.size
    }

    private fun fitEllipse(pts: List<PointF>): QShape.Ellipse? {
        val n = pts.size
        var cx = 0f; var cy = 0f
        for (p in pts) { cx += p.x; cy += p.y }
        cx /= n; cy /= n
        var sxx = 0f; var syy = 0f; var sxy = 0f
        for (p in pts) {
            val dx = p.x - cx; val dy = p.y - cy
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        sxx /= n; syy /= n; sxy /= n
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val disc = sqrt(max(0f, tr * tr / 4 - det))
        val l1 = tr / 2 + disc; val l2 = tr / 2 - disc
        if (l1 <= 0f) return null
        val angle = 0.5f * atan2(2 * sxy, sxx - syy)
        val rx = sqrt(2 * l1); val ry = sqrt(max(1f, 2 * l2))
        return QShape.Ellipse(cx, cy, rx, ry, angle)
    }

    private fun ellipseError(pts: List<PointF>, e: QShape.Ellipse): Float {
        val c = cos(-e.rot); val s = sin(-e.rot)
        var err = 0f
        for (p in pts) {
            val dx = p.x - e.cx; val dy = p.y - e.cy
            val x = dx * c - dy * s; val y = dx * s + dy * c
            val ang = atan2(y / e.ry, x / e.rx)
            val ex = cos(ang) * e.rx; val ey = sin(ang) * e.ry
            err += abs(hypot(x, y) - hypot(ex, ey))
        }
        return err / pts.size
    }

    private fun fitArc(pts: List<PointF>): QShape.Arc? {
        val a = pts.first(); val m = pts[pts.size / 2]; val b = pts.last()
        val d = 2 * (a.x * (m.y - b.y) + m.x * (b.y - a.y) + b.x * (a.y - m.y))
        if (abs(d) < 1e-3f) return null
        val ux = ((a.x * a.x + a.y * a.y) * (m.y - b.y) + (m.x * m.x + m.y * m.y) * (b.y - a.y) + (b.x * b.x + b.y * b.y) * (a.y - m.y)) / d
        val uy = ((a.x * a.x + a.y * a.y) * (b.x - m.x) + (m.x * m.x + m.y * m.y) * (a.x - b.x) + (b.x * b.x + b.y * b.y) * (m.x - a.x)) / d
        val r = hypot(a.x - ux, a.y - uy)
        if (r > 20000f) return null
        var err = 0f
        for (p in pts) err += abs(hypot(p.x - ux, p.y - uy) - r)
        err /= pts.size
        if (err > 0.06f * r + 2f) return null
        val start = atan2(a.y - uy, a.x - ux)
        val midA = atan2(m.y - uy, m.x - ux)
        val end = atan2(b.y - uy, b.x - ux)
        fun norm(v: Float): Float { var x = v; while (x < 0) x += (2 * PI).toFloat(); while (x >= 2 * PI) x -= (2 * PI).toFloat(); return x }
        val ccwMid = norm(midA - start); val ccwEnd = norm(end - start)
        val sweep = if (ccwMid < ccwEnd) ccwEnd else -(2 * PI.toFloat() - ccwEnd)
        return QShape.Arc(ux, uy, r, start, sweep)
    }
}
