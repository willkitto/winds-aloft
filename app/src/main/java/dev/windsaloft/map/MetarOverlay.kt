package dev.windsaloft.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import dev.windsaloft.data.Metar
import dev.windsaloft.wind.KT_PER_MS
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Airports near the forecast spot with what their latest report (METAR) says the ground wind is
 * doing: a windsock arrow streaming downwind, the station id and "speed (gust)". Drawn level on
 * screen however the map is rotated.
 */
class MetarOverlay(private val density: Float) : Overlay() {
    var stations: List<Metar> = emptyList()
    /** Speed text in the user's unit, from m/s. */
    var speedLabel: (Double) -> String = { ms -> Math.round(ms * KT_PER_MS).toString() }

    private val pt = Point()
    private val path = Path()
    private val box = RectF()
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xFF111111.toInt() }
    private val arrowHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xEEFFFFFF.toInt() }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF111111.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF1565C0.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xE61C2733.toInt() }
    private val idText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB3D4FF.toInt(); isFakeBoldText = true }
    private val windText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); isFakeBoldText = true }

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow || stations.isEmpty()) return
        val rotation = mapView.mapOrientation
        for (m in stations) {
            mapView.projection.toPixels(GeoPoint(m.location.lat, m.location.lon), pt)
            val x = pt.x.toFloat()
            val y = pt.y.toFloat()
            c.save()
            c.rotate(-rotation, x, y) // level on screen; directions add the rotation back
            var labelLeft = false
            if (!m.calm && m.dirDeg != null) {
                // Windsock: tail on the airport, streaming downwind; longer for stronger wind.
                val len = (16f + m.speedKt * 1.1f).coerceAtMost(48f) * density
                val a = Math.toRadians(m.dirDeg + 180.0 + rotation)
                val dx = sin(a).toFloat()
                val dy = -cos(a).toFloat()
                val ex = x + dx * len
                val ey = y + dy * len
                val hl = 7 * density
                arrowHalo.strokeWidth = 5f * density
                arrow.strokeWidth = 2.4f * density
                c.drawLine(x, y, ex - dx * hl, ey - dy * hl, arrowHalo)
                c.drawLine(x, y, ex - dx * hl, ey - dy * hl, arrow)
                path.reset()
                path.moveTo(ex, ey)
                path.lineTo(ex - dx * hl - dy * hl * 0.6f, ey - dy * hl + dx * hl * 0.6f)
                path.lineTo(ex - dx * hl + dy * hl * 0.6f, ey - dy * hl - dx * hl * 0.6f)
                path.close()
                arrowHalo.strokeWidth = 2f * density
                c.drawPath(path, arrowHalo)
                c.drawPath(path, head)
                labelLeft = dx > 0.3f // keep the label clear of the arrow
            }
            ring.strokeWidth = 1.6f * density
            c.drawCircle(x, y, 4.5f * density, dot)
            c.drawCircle(x, y, 4.5f * density, ring)

            val wind = when {
                m.calm -> "Calm"
                else -> (if (m.dirDeg == null) "VRB " else "") + speedLabel(m.speedKt / KT_PER_MS) +
                    (m.gustKt?.let { " (${speedLabel(it / KT_PER_MS)})" } ?: "")
            }
            idText.textSize = 10.5f * density
            windText.textSize = 12.5f * density
            val w = maxOf(idText.measureText(m.icao), windText.measureText(wind)) + 10 * density
            val h = 30 * density
            val left = if (labelLeft) x - 9 * density - w else x + 9 * density
            box.set(left, y - h / 2, left + w, y + h / 2)
            c.drawRoundRect(box, 6 * density, 6 * density, bg)
            c.drawText(m.icao, left + 5 * density, y - 3 * density, idText)
            c.drawText(wind, left + 5 * density, y + 11 * density, windText)
            c.restore()
        }
    }
}
