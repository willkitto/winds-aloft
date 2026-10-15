package dev.windsaloft.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import dev.windsaloft.data.ArrowColor
import dev.windsaloft.data.ArrowLayout
import dev.windsaloft.wind.KT_PER_MS
import dev.windsaloft.wind.LatLon
import dev.windsaloft.wind.Wind
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/** One forecast source's winds, one slot per selected altitude (low to high; null = no data). */
data class WindColumn(val label: String?, val color: Int?, val winds: List<Wind?>)

/**
 * Draws the winds for the pinned location. Arrows point DOWNWIND (the way the air is moving).
 *
 * Stacked: one vertical column per forecast source rising from the pin, one row per altitude
 * (lowest at the bottom), at a fixed spacing so the display never resizes; pan the map to see
 * parts under the panels. Each arrow is centred on its row and scaled by speed; the speed and
 * temperature are printed beside it.
 * Fan: all arrows share the pin as origin, with length proportional to speed.
 */
class WindOverlay(private val density: Float) : Overlay() {
    var location: LatLon? = null
    var columns: List<WindColumn> = emptyList()
    var ceilingFt: Int = 18_000
    var layout: ArrowLayout = ArrowLayout.STACKED
    var colorMode: ArrowColor = ArrowColor.AUTO
    var showTemperature: Boolean = true
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

        val textSize = (cell * 0.36f).coerceIn(10f * density, 15f * density)
        val tempSize = textSize * 0.88f
        textHalo.strokeWidth = 3.5f * density
        text.textSize = textSize
        val speedWidth = text.measureText("888") + 4 * density
        val altWidth = text.measureText("14k") + 8 * density
        text.textSize = tempSize
        val tempWidth = if (showTemperature) text.measureText("-88°") + 6 * density else 0f
        val gap = 8 * density
        val colWidth = cell + speedWidth + tempWidth + gap
        val stroke = (cell * 0.085f).coerceIn(2.4f * density, 4.2f * density)

        // One column sits on the pin; several are centred as a block (altitude labels included).
        val groupWidth = columns.size * colWidth - gap
        val firstCx = if (columns.size == 1) x else x + cell / 2 - (groupWidth - altWidth) / 2
        val top = y - cell * (rows - skip)

        if (columns.size > 1) {
            // Base bar tying the columns to the pin.
            staffHalo.strokeWidth = 4f * density
            staff.strokeWidth = 2f * density
            staff.color = 0xEEFFFFFF.toInt()
            val lastCx = firstCx + (columns.size - 1) * colWidth
            c.drawLine(minOf(firstCx, x), y, maxOf(lastCx, x), y, staffHalo)
            c.drawLine(minOf(firstCx, x), y, maxOf(lastCx, x), y, staff)
        }

        val labelEvery = ceil(textSize * 1.15f / cell).toInt().coerceAtLeast(1)
        columns.forEachIndexed { j, col ->
            val cx = firstCx + j * colWidth
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
                    val len = cell * STACK_LENGTH * stackedFraction(kt)
                    // Centre the arrow on the cell: start half a length upwind of the centre.
                    val a = Math.toRadians(w.dirFromDeg + 180.0)
                    val sx = cx - sin(a).toFloat() * len / 2
                    val sy = cy + cos(a).toFloat() * len / 2
                    drawArrow(c, sx, sy, a, len, stroke, color)
                }
                if (i % labelEvery == 0) {
                    val ty = cy + textSize * 0.36f
                    text.textSize = textSize; textHalo.textSize = textSize
                    drawText(c, speedLabel(w.speedMs), cx + cell / 2 + 3 * density, ty, Paint.Align.LEFT, 0xFFFFFFFF.toInt())
                    if (showTemperature && w.tempC != null) {
                        text.textSize = tempSize; textHalo.textSize = tempSize
                        val tint = if (colorMode == ArrowColor.TEMPERATURE) 0xFFFFFFFF.toInt() else temperatureTint(w.tempC)
                        drawText(c, tempLabel(w.tempC), cx + cell / 2 + 5 * density + speedWidth, ty, Paint.Align.LEFT, tint)
                    }
                }
            }
            if (col.label != null) {
                text.textSize = textSize; textHalo.textSize = textSize
                drawText(c, col.label, cx + (speedWidth + tempWidth) / 2, top - 6 * density, Paint.Align.CENTER, col.color ?: 0xFFFFFFFF.toInt())
            }
        }

        // Altitude labels down the left side.
        text.textSize = textSize; textHalo.textSize = textSize
        val altX = firstCx - cell / 2 - 5 * density
        for (i in skip until rows) {
            if (i % labelEvery != 0) continue
            val alt = columns.firstNotNullOfOrNull { it.winds.getOrNull(i) }?.altitudeFt ?: continue
            val label = if (alt == 0) "SFC" else if (alt % 1000 == 0) "${alt / 1000}k" else "$alt"
            drawText(c, label, altX, rowY(i) + textSize * 0.36f, Paint.Align.RIGHT, 0xFFFFFFFF.toInt())
        }
        // When MSL rows were dropped, the ground is at the pin: mark it.
        if (skip > 0) {
            val x1 = firstCx + (columns.size - 1) * colWidth + cell / 2 + speedWidth + tempWidth
            ground.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
            ground.strokeWidth = 2f * density
            staffHalo.strokeWidth = 4f * density
            c.drawLine(firstCx - cell / 2, y, x1, y, staffHalo)
            c.drawLine(firstCx - cell / 2, y, x1, y, ground)
            drawText(c, "GND", altX, y + textSize * 0.36f, Paint.Align.RIGHT, 0xFFFFFFFF.toInt())
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
        /** Row spacing of the stacked columns, in dp. */
        const val ROW_DP = 38f

        /** Arrow length at full speed, as a fraction of the row spacing (a little longer than a row). */
        const val STACK_LENGTH = 1.25f

        /** In the stacked layout an arrow reaches full length at this speed. */
        const val STACK_FULL_KT = 40.0

        /** Stacked arrow length as a fraction of full length: half at calm, full from STACK_FULL_KT. */
        fun stackedFraction(kt: Double): Float = 0.5f + 0.5f * (kt / STACK_FULL_KT).toFloat().coerceIn(0f, 1f)

        /** Fan arrow length in dp for a speed in knots; shared with the on-screen legend. */
        fun arrowLengthDp(kt: Double): Float = (12.0 + kt * 2.2).coerceAtMost(160.0).toFloat()
    }
}
