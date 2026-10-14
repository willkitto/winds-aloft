package dev.windsaloft.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import dev.windsaloft.wind.KT_PER_MS
import dev.windsaloft.wind.LatLon
import dev.windsaloft.wind.Wind
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.cos
import kotlin.math.sin

/** A cluster of wind arrows (one per altitude) radiating from one map location. */
data class WindFan(val location: LatLon, val winds: List<Wind>, val emphasized: Boolean)

/**
 * Draws wind arrows on the map. Each arrow starts at its location and points DOWNWIND (the way
 * the air is moving), with length proportional to speed and colour keyed to altitude.
 */
class WindOverlay(private val density: Float) : Overlay() {
    var fans: List<WindFan> = emptyList()
    var ceilingFt: Int = 18_000

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0x99000000.toInt()
    }
    private val headFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val headHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; color = 0x99000000.toInt()
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF202020.toInt() }
    private val pinRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFE53935.toInt() }
    private val path = Path()
    private val pt = Point()

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        val margin = 140 * density
        // Grid first, emphasized (pinned) fan last so it is on top.
        for (fan in fans.sortedBy { it.emphasized }) {
            proj.toPixels(GeoPoint(fan.location.lat, fan.location.lon), pt)
            if (pt.x < -margin || pt.y < -margin || pt.x > mapView.width + margin || pt.y > mapView.height + margin) continue
            val x = pt.x.toFloat()
            val y = pt.y.toFloat()
            val stroke = (if (fan.emphasized) 3.2f else 2.2f) * density
            // High altitudes first so the low ones end up on top.
            for (w in fan.winds.sortedByDescending { it.altitudeFt }) {
                drawArrow(c, x, y, w, stroke)
            }
            val r = (if (fan.emphasized) 5f else 3f) * density
            dotRing.strokeWidth = 1.5f * density
            c.drawCircle(x, y, r, dotFill)
            c.drawCircle(x, y, r, dotRing)
            if (fan.emphasized) {
                pinRing.strokeWidth = 2.5f * density
                c.drawCircle(x, y, r + 4 * density, pinRing)
            }
        }
    }

    private fun drawArrow(c: Canvas, x: Float, y: Float, w: Wind, stroke: Float) {
        val color = AltitudeColors.argb(w.altitudeFt, ceilingFt)
        val kt = w.speedMs * KT_PER_MS
        if (kt < 1.0) {
            // Calm: a small open circle.
            line.color = color; line.strokeWidth = stroke * 0.8f
            c.drawCircle(x, y, 7 * density, line)
            return
        }
        val len = arrowLengthDp(kt) * density
        val a = Math.toRadians(w.dirFromDeg + 180.0) // downwind
        val dx = sin(a).toFloat()
        val dy = -cos(a).toFloat()
        val head = stroke * 3.2f
        val ex = x + dx * len
        val ey = y + dy * len
        val bx = ex - dx * head
        val by = ey - dy * head

        halo.strokeWidth = stroke + 2.2f * density
        c.drawLine(x, y, bx, by, halo)
        line.color = color; line.strokeWidth = stroke
        c.drawLine(x, y, bx, by, line)

        val hw = head * 0.6f
        path.reset()
        path.moveTo(ex, ey)
        path.lineTo(bx - dy * hw, by + dx * hw)
        path.lineTo(bx + dy * hw, by - dx * hw)
        path.close()
        headHalo.strokeWidth = 1.6f * density
        c.drawPath(path, headHalo)
        headFill.color = color
        c.drawPath(path, headFill)
    }

    companion object {
        /** Arrow length in dp for a speed in knots; shared with the on-screen legend. */
        fun arrowLengthDp(kt: Double): Float = (10.0 + kt * 1.4).coerceAtMost(120.0).toFloat()
    }
}
