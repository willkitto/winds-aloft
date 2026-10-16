package dev.windsaloft.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import dev.windsaloft.data.ArrowAnchor
import dev.windsaloft.data.ArrowColor
import dev.windsaloft.data.ArrowLayout
import dev.windsaloft.wind.CloudCover
import dev.windsaloft.wind.CloudLayer
import dev.windsaloft.wind.KT_PER_MS
import dev.windsaloft.wind.LatLon
import dev.windsaloft.wind.Wind
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * One forecast source's winds, one slot per selected altitude (low to high; null = no data), and the
 * cloud layers within the shown altitudes (null = the model has no cloud data).
 */
data class WindColumn(val label: String?, val color: Int?, val winds: List<Wind?>, val clouds: List<CloudLayer>? = null)

/**
 * Draws the winds for the pinned location. Arrows point DOWNWIND (the way the air is moving).
 *
 * Stacked: one vertical column per forecast source rising from the pin, one row per altitude
 * (lowest at the bottom), at a fixed spacing so the display never resizes; pan the map to see
 * parts under the panels. Each arrow works like a windsock: its tail sits on the column's centre
 * line and it streams downwind, longer for stronger wind. The layout is fixed (each column reserves
 * the farthest an arrow could reach, then a speed/temperature slot), so nothing moves as the forecast
 * time changes. Cloud layers are bands behind the rows; layers outside the shown altitudes get markers.
 * Fan: all arrows share the pin as origin, with length proportional to speed.
 */
class WindOverlay(private val density: Float) : Overlay() {
    var location: LatLon? = null
    var columns: List<WindColumn> = emptyList()
    var ceilingFt: Int = 18_000
    var layout: ArrowLayout = ArrowLayout.STACKED
    var colorMode: ArrowColor = ArrowColor.AUTO
    var showTemperature: Boolean = true
    var showClouds: Boolean = true
    var anchor: ArrowAnchor = ArrowAnchor.TAIL
    /** The altitude of each row (low to high), in the display reference. */
    var altitudesFt: List<Int> = emptyList()
    /** Speed text in the user's unit. */
    var speedLabel: (Double) -> String = { ms -> (ms * KT_PER_MS).toInt().toString() }
    /** Temperature text in the user's unit. */
    var tempLabel: (Double) -> String = { c -> "${c.toInt()}°" }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0xAA000000.toInt()
    }
    private val headFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val headHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; color = 0xAA000000.toInt()
    }
    private val staff = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val staffHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0x88000000.toInt()
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF202020.toInt() }
    private val pinRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFE53935.toInt() }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
    private val textHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE6000000.toInt(); style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; isFakeBoldText = true
    }
    private val ground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt()
    }
    private val cloudFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFECF2F8.toInt() }
    private val cloudStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xF2FFFFFF.toInt() }
    private val rect = android.graphics.RectF()
    private val markerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C2733.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val path = Path()
    private val pt = Point()

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val loc = location ?: return
        mapView.projection.toPixels(GeoPoint(loc.lat, loc.lon), pt)
        val x = pt.x.toFloat()
        val y = pt.y.toFloat()
        if (columns.isNotEmpty()) {
            when (layout) {
                ArrowLayout.STACKED -> drawColumns(c, x, y)
                ArrowLayout.FAN -> drawFan(c, x, y)
            }
        }
        drawPin(c, x, y)
    }

    // ---------------------------------------------------------------- Stacked columns

    private fun drawColumns(c: Canvas, x: Float, y: Float) {
        val rows = columns.maxOf { it.winds.size }
        if (rows == 0) return
        // Fixed row spacing: panels opening over the map cover the columns instead of shrinking them.
        val cell = ROW_DP * density
        // Rows below the terrain (MSL altitudes under the ground) are left out, so the column starts at the pin.
        val skip = (0 until rows).firstOrNull { i -> columns.any { it.winds.getOrNull(i) != null } } ?: return
        fun rowY(i: Int) = y - cell * (i - skip + 0.5f)
        fun rowAlt(i: Int): Int? = altitudesFt.getOrNull(i) ?: columns.firstNotNullOfOrNull { it.winds.getOrNull(i) }?.altitudeFt
        val loShown = rowAlt(skip) ?: return
        val hiShown = rowAlt(rows - 1) ?: loShown
        fun altY(ft: Int) = y - cell * ((ft - loShown) / 1000f + 0.5f)
        val top = y - cell * (rows - skip)

        val textSize = (cell * 0.36f).coerceIn(10f * density, 15f * density)
        val tempSize = textSize * 0.86f
        textHalo.strokeWidth = 3.5f * density
        val stroke = (cell * 0.085f).coerceIn(2.4f * density, 4.2f * density)
        val labelEvery = ceil(textSize * 1.15f / cell).toInt().coerceAtLeast(1)
        val comparing = columns.size > 1
        fun width(s: String, size: Float): Float { text.textSize = size; return text.measureText(s) }

        // Fixed geometry, independent of the forecast, so nothing moves as the time changes: each
        // column reserves the farthest a windsock arrow could reach in any direction, then a label slot.
        val lenScale = if (comparing) COMPARE_LENGTH else STACK_LENGTH
        val reach = (if (anchor == ArrowAnchor.CENTRE) cell * lenScale / 2 else cell * lenScale) + stroke * 1.5f + density
        val labelW = maxOf(width("888", textSize), if (showTemperature) width("-88°", tempSize) else 0f) + 6 * density
        val gap = 4 * density
        val colW = 2 * reach + labelW + gap
        val altW = width("14k", textSize) + 12 * density
        val blockW = altW + columns.size * colW - gap
        val cx0 = if (comparing) x - blockW / 2 + altW + reach else x // one column sits on the pin
        fun cxOf(j: Int) = cx0 + j * colW
        fun colL(j: Int) = cxOf(j) - reach
        fun colR(j: Int) = cxOf(j) + reach + labelW
        val blockRight = colR(columns.lastIndex)

        // Clouds: bands for layers within the shown altitudes, markers for layers above or below them.
        val above = mutableListOf<Pair<Int, CloudLayer>>()
        val below = mutableListOf<Pair<Int, CloudLayer>>()
        if (showClouds) columns.forEachIndexed { j, col ->
            for (layer in col.clouds.orEmpty()) {
                if (layer.baseFt > hiShown) { above += j to layer; continue }
                if (layer.topFt < loShown) { below += j to layer; continue }
                val yb = minOf(y, altY(layer.baseFt))
                val yt = maxOf(top, altY(layer.topFt + 100))
                if (yb <= yt) continue
                rect.set(colL(j), yt, colR(j), yb)
                cloudFill.alpha = (CLOUD_ALPHA.getValue(layer.cover) * 255).toInt()
                cloudStroke.strokeWidth = 1.2f * density
                c.drawRoundRect(rect, 6 * density, 6 * density, cloudFill)
                c.drawRoundRect(rect, 6 * density, 6 * density, cloudStroke)
                if (!comparing) {
                    val mid = (yb + yt) / 2
                    text.textSize = textSize * 0.9f; textHalo.textSize = textSize * 0.9f
                    drawText(c, layer.cover.name, blockRight + 8 * density, mid - 2 * density, Paint.Align.LEFT, 0xFFFFFFFF.toInt())
                    text.textSize = textSize * 0.8f; textHalo.textSize = textSize * 0.8f
                    drawText(c, "${shortFt(layer.baseFt)}–${shortFt(layer.topFt)}", blockRight + 8 * density, mid + textSize * 0.85f, Paint.Align.LEFT, 0xFFE6EEF5.toInt())
                }
            }
        }

        if (comparing) {
            // Base bar tying the columns to the pin.
            staffHalo.strokeWidth = 4f * density
            staff.strokeWidth = 2f * density
            staff.color = 0xEEFFFFFF.toInt()
            c.drawLine(minOf(cxOf(0), x), y, maxOf(cxOf(columns.lastIndex), x), y, staffHalo)
            c.drawLine(minOf(cxOf(0), x), y, maxOf(cxOf(columns.lastIndex), x), y, staff)
        }

        columns.forEachIndexed { j, col ->
            val cx = cxOf(j)
            staffHalo.strokeWidth = 4f * density
            staff.strokeWidth = 1.6f * density
            staff.color = col.color ?: 0xEEFFFFFF.toInt()
            c.drawLine(cx, y, cx, top, staffHalo)
            c.drawLine(cx, y, cx, top, staff)

            col.winds.forEachIndexed { i, w ->
                if (w == null) return@forEachIndexed
                val cy = rowY(i)
                val color = colorFor(w, col)
                val kt = w.speedMs * KT_PER_MS
                if (kt < 1.0) {
                    line.color = color; line.strokeWidth = stroke * 0.8f
                    c.drawCircle(cx, cy, cell * 0.16f, line)
                } else {
                    // Windsock by default: the tail sits on the centre line and the arrow streams downwind.
                    val len = cell * lenScale * stackedFraction(kt)
                    val a = Math.toRadians(w.dirFromDeg + 180.0)
                    val back = when (anchor) { ArrowAnchor.TAIL -> 0f; ArrowAnchor.NOSE -> len; ArrowAnchor.CENTRE -> len / 2 }
                    drawArrow(c, cx - sin(a).toFloat() * back, cy + cos(a).toFloat() * back, a, len, stroke, color)
                }
                if (i % labelEvery != 0) return@forEachIndexed
                // Label slot: speed on top, temperature underneath, always at the same spot.
                val lx = cx + reach + 4 * density
                val hasTemp = showTemperature && w.tempC != null
                text.textSize = textSize; textHalo.textSize = textSize
                drawText(c, speedLabel(w.speedMs), lx, if (hasTemp) cy - density else cy + textSize * 0.36f, Paint.Align.LEFT, 0xFFFFFFFF.toInt())
                if (hasTemp) {
                    text.textSize = tempSize; textHalo.textSize = tempSize
                    val tint = if (colorMode == ArrowColor.TEMPERATURE) 0xFFFFFFFF.toInt() else temperatureTint(w.tempC!!)
                    drawText(c, tempLabel(w.tempC), lx, cy + tempSize + density, Paint.Align.LEFT, tint)
                }
            }
            if (col.label != null) {
                text.textSize = textSize; textHalo.textSize = textSize
                drawText(c, col.label, cx + labelW / 2, top - 6 * density, Paint.Align.CENTER, col.color ?: 0xFFFFFFFF.toInt())
            }
        }

        // Out-of-range cloud markers: stacked upward above the columns, downward below the pin.
        val pillH = textSize + 8 * density
        above.groupBy { it.first }.forEach { (j, list) ->
            list.forEachIndexed { n, (_, layer) ->
                val yc = top - (if (comparing) textSize + 12 * density else 8 * density) - pillH / 2 - n * (pillH + 3 * density)
                drawCloudMarker(c, (colL(j) + colR(j)) / 2, yc, "▲ ${layer.cover.name} ${shortFt(layer.baseFt)}–${shortFt(layer.topFt)}", layer.cover, textSize * 0.82f, pillH)
            }
        }
        below.groupBy { it.first }.forEach { (j, list) ->
            list.forEachIndexed { n, (_, layer) ->
                val yc = y + 12 * density + pillH / 2 + n * (pillH + 3 * density)
                drawCloudMarker(c, (colL(j) + colR(j)) / 2, yc, "▼ ${layer.cover.name} ${shortFt(layer.baseFt)}–${shortFt(layer.topFt)}", layer.cover, textSize * 0.82f, pillH)
            }
        }

        // Altitude labels down the left side.
        text.textSize = textSize; textHalo.textSize = textSize
        val altX = colL(0) - 6 * density
        for (i in skip until rows) {
            if (i % labelEvery != 0) continue
            val alt = rowAlt(i) ?: continue
            val label = if (alt == 0) "SFC" else if (alt % 1000 == 0) "${alt / 1000}k" else "$alt"
            drawText(c, label, altX, rowY(i) + textSize * 0.36f, Paint.Align.RIGHT, 0xFFFFFFFF.toInt())
        }
        // When MSL rows were dropped, the ground is at the pin: mark it.
        if (skip > 0) {
            ground.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
            ground.strokeWidth = 2f * density
            staffHalo.strokeWidth = 4f * density
            c.drawLine(colL(0), y, blockRight, y, staffHalo)
            c.drawLine(colL(0), y, blockRight, y, ground)
            drawText(c, "GND", altX, y + textSize * 0.36f, Paint.Align.RIGHT, 0xFFFFFFFF.toInt())
        }
    }

    /** A small pill naming a cloud layer that lies outside the shown altitudes. */
    private fun drawCloudMarker(c: Canvas, cx: Float, yc: Float, label: String, cover: CloudCover, size: Float, h: Float) {
        markerText.textSize = size
        val w = markerText.measureText(label) + 14 * density
        rect.set(cx - w / 2, yc - h / 2, cx + w / 2, yc + h / 2)
        cloudFill.alpha = ((0.55f + CLOUD_ALPHA.getValue(cover) * 0.6f).coerceAtMost(1f) * 255).toInt()
        c.drawRoundRect(rect, h / 2, h / 2, cloudFill)
        cloudStroke.strokeWidth = 1.2f * density
        c.drawRoundRect(rect, h / 2, h / 2, cloudStroke)
        c.drawText(label, cx, yc - (markerText.ascent() + markerText.descent()) / 2, markerText)
    }

    private fun shortFt(ft: Int): String {
        val h = Math.round(ft / 100.0).toInt()
        return when {
            ft == 0 -> "SFC"
            h >= 10 -> if (h % 10 == 0) "${h / 10}k" else "${h / 10}.${h % 10}k"
            else -> "${h * 100}"
        }
    }

    private fun colorFor(w: Wind, col: WindColumn): Int = when (colorMode) {
        ArrowColor.AUTO -> col.color ?: AltitudeColors.argb(w.altitudeFt, ceilingFt)
        ArrowColor.ALTITUDE -> AltitudeColors.argb(w.altitudeFt, ceilingFt)
        ArrowColor.SPEED -> AltitudeColors.speed(w.speedMs * KT_PER_MS)
        ArrowColor.TEMPERATURE -> w.tempC?.let { AltitudeColors.temperature(it) } ?: 0xFFFFFFFF.toInt()
    }

    /** Label tint: pale blue below freezing, pale orange above, white around 0 °C. */
    private fun temperatureTint(c: Double): Int = when {
        c <= -1 -> 0xFFB8E4FF.toInt()
        c >= 1 -> 0xFFFFD6AD.toInt()
        else -> 0xFFFFFFFF.toInt()
    }

    // ---------------------------------------------------------------- Fan

    private fun drawFan(c: Canvas, x: Float, y: Float) {
        val stroke = 3f * density
        // First (primary) source last so it ends up on top.
        for (col in columns.asReversed()) for (w in col.winds.filterNotNull().sortedByDescending { it.altitudeFt }) {
            val color = colorFor(w, col)
            val kt = w.speedMs * KT_PER_MS
            if (kt < 1.0) {
                line.color = color; line.strokeWidth = stroke * 0.8f
                c.drawCircle(x, y, 8f * density, line)
                continue
            }
            drawArrow(c, x, y, Math.toRadians(w.dirFromDeg + 180.0), arrowLengthDp(kt) * density, stroke, color)
        }
    }

    // ---------------------------------------------------------------- Primitives

    private fun drawText(c: Canvas, s: String, tx: Float, ty: Float, align: Paint.Align, color: Int) {
        text.textAlign = align
        textHalo.textAlign = align
        text.color = color
        c.drawText(s, tx, ty, textHalo)
        c.drawText(s, tx, ty, text)
    }

    private fun drawPin(c: Canvas, x: Float, y: Float) {
        val r = 5f * density
        dotRing.strokeWidth = 1.5f * density
        c.drawCircle(x, y, r, dotFill)
        c.drawCircle(x, y, r, dotRing)
        pinRing.strokeWidth = 2.5f * density
        c.drawCircle(x, y, r + 4 * density, pinRing)
    }

    /** Arrow from (x, y) heading [angleRad] (0 = north, clockwise) for [len] pixels. */
    private fun drawArrow(c: Canvas, x: Float, y: Float, angleRad: Double, len: Float, stroke: Float, color: Int) {
        val dx = sin(angleRad).toFloat()
        val dy = -cos(angleRad).toFloat()
        val head = (stroke * 3f).coerceAtMost(len * 0.45f)
        val ex = x + dx * len
        val ey = y + dy * len
        val bx = ex - dx * head
        val by = ey - dy * head

        halo.strokeWidth = stroke + 2.4f * density
        c.drawLine(x, y, bx, by, halo)
        line.color = color; line.strokeWidth = stroke
        c.drawLine(x, y, bx, by, line)

        val hw = head * 0.62f
        path.reset()
        path.moveTo(ex, ey)
        path.lineTo(bx - dy * hw, by + dx * hw)
        path.lineTo(bx + dy * hw, by - dx * hw)
        path.close()
        headHalo.strokeWidth = 1.8f * density
        c.drawPath(path, headHalo)
        headFill.color = color
        c.drawPath(path, headFill)
    }

    companion object {
        /** Cloud band opacity by coverage. */
        private val CLOUD_ALPHA = mapOf(CloudCover.SCT to 0.32f, CloudCover.BKN to 0.5f, CloudCover.OVC to 0.66f)

        /** Row spacing of the stacked columns, in dp. */
        const val ROW_DP = 38f

        /** Arrow length at full speed, as a fraction of the row spacing (a little longer than a row). */
        const val STACK_LENGTH = 1.25f

        /** Shorter arrows when comparing, so three fixed-width columns fit on a phone. */
        const val COMPARE_LENGTH = 0.85f

        /** In the stacked layout an arrow reaches full length at this speed. */
        const val STACK_FULL_KT = 40.0

        /** Stacked arrow length as a fraction of full length: half at calm, full from STACK_FULL_KT. */
        fun stackedFraction(kt: Double): Float = 0.5f + 0.5f * (kt / STACK_FULL_KT).toFloat().coerceIn(0f, 1f)

        /** Fan arrow length in dp for a speed in knots; shared with the on-screen legend. */
        fun arrowLengthDp(kt: Double): Float = (12.0 + kt * 2.2).coerceAtMost(160.0).toFloat()
    }
}
