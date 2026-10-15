package dev.windsaloft.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
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
 * line and it streams downwind, longer for stronger wind. Speed and temperature are printed on the
 * side the arrows reach least, and cloud layers are drawn as bands behind the rows.
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

    /** One row's arrow reach (relative to the column's centre line) and its label texts. */
    private class RowGeo(val w: Wind, val i: Int, val x0: Float, val x1: Float, val dx: Float, val speed: String, val temp: String?, val sw: Float, val tw: Float)

    /** A column's rows, how far it reaches left/right of its centre line, and where its labels start. */
    private class ColGeo(val col: WindColumn, val rows: List<RowGeo>, val left: Float, val right: Float, val labelX: Float, val labelsLeft: Boolean)

    private fun drawColumns(c: Canvas, x: Float, y: Float) {
        val rows = columns.maxOf { it.winds.size }
        if (rows == 0) return
        // Fixed row spacing: panels opening over the map cover the columns instead of shrinking them.
        val cell = ROW_DP * density
        // Rows below the terrain (MSL altitudes under the ground) are left out, so the column starts at the pin.
        val skip = (0 until rows).firstOrNull { i -> columns.any { it.winds.getOrNull(i) != null } } ?: return
        fun rowY(i: Int) = y - cell * (i - skip + 0.5f)
        val firstAlt = columns.firstNotNullOf { it.winds.getOrNull(skip) }.altitudeFt
        fun altY(ft: Int) = y - cell * ((ft - firstAlt) / 1000f + 0.5f)
        val top = y - cell * (rows - skip)

        val textSize = (cell * 0.36f).coerceIn(10f * density, 15f * density)
        val tempSize = textSize * 0.88f
        textHalo.strokeWidth = 3.5f * density
        val stroke = (cell * 0.085f).coerceIn(2.4f * density, 4.2f * density)
        val labelEvery = ceil(textSize * 1.15f / cell).toInt().coerceAtLeast(1)
        val gap4 = 4 * density
        fun width(s: String, size: Float): Float { text.textSize = size; return text.measureText(s) }

        // Pass 1: each arrow's reach. Windsock: the tail sits on the centre line and the arrow streams downwind.
        val geos = columns.map { col ->
            val rg = col.winds.mapIndexedNotNull { i, w ->
                if (w == null) return@mapIndexedNotNull null
                val len = cell * STACK_LENGTH * stackedFraction(w.speedMs * KT_PER_MS)
                val dx = (sin(Math.toRadians(w.dirFromDeg + 180.0)) * len).toFloat()
                val pad = stroke * 2 + 2 * density
                val labelled = i % labelEvery == 0
                val sp = speedLabel(w.speedMs)
                val tp = if (showTemperature && w.tempC != null) tempLabel(w.tempC) else null
                RowGeo(
                    w, i, minOf(0f, dx) - pad, maxOf(0f, dx) + pad, dx, sp, tp,
                    if (labelled) width(sp, textSize) else 0f,
                    if (labelled && tp != null) width(tp, tempSize) + gap4 else 0f,
                )
            }
            // All of a column's labels go on one side: the side its arrows reach into least.
            val labelsLeft = rg.sumOf { maxOf(0f, it.dx).toDouble() } > rg.sumOf { maxOf(0f, -it.dx).toDouble() }
            val labelW = rg.maxOfOrNull { it.sw + it.tw } ?: 0f
            val labelX = if (labelsLeft) minOf(-gap4, rg.minOfOrNull { it.x0 } ?: 0f) - gap4
            else maxOf(gap4, rg.maxOfOrNull { it.x1 } ?: 0f) + gap4
            val left = maxOf(cell / 2, rg.maxOfOrNull { -it.x0 } ?: 0f, if (labelsLeft) -labelX + labelW else 0f)
            val right = maxOf(cell / 2, rg.maxOfOrNull { it.x1 } ?: 0f, if (labelsLeft) 0f else labelX + labelW)
            ColGeo(col, rg, left, right, labelX, labelsLeft)
        }

        // Pass 2: place columns side by side, packed by their actual width. One column sits on the pin;
        // several are centred as a block (altitude labels included).
        val altW = width("14k", textSize) + 12 * density
        val gap = 8 * density
        val cxs = FloatArray(geos.size)
        for (j in 1 until geos.size) cxs[j] = cxs[j - 1] + geos[j - 1].right + gap + geos[j].left
        val blockL = -geos.first().left - altW
        val blockR = cxs.last() + geos.last().right
        val shift = if (geos.size == 1) x else x - (blockL + blockR) / 2
        for (j in cxs.indices) cxs[j] += shift
        val blockRight = cxs.last() + geos.last().right

        // Cloud layers behind everything: one band per layer per column, spanning its base and top.
        if (showClouds) geos.forEachIndexed { j, g ->
            for (layer in g.col.clouds.orEmpty()) {
                val yb = minOf(y, altY(layer.baseFt))
                val yt = maxOf(top, altY(layer.topFt + 100))
                if (yb <= yt) continue
                rect.set(cxs[j] - g.left, yt, cxs[j] + g.right, yb)
                cloudFill.alpha = (CLOUD_ALPHA.getValue(layer.cover) * 255).toInt()
                cloudStroke.strokeWidth = 1.2f * density
                c.drawRoundRect(rect, 6 * density, 6 * density, cloudFill)
                c.drawRoundRect(rect, 6 * density, 6 * density, cloudStroke)
                if (geos.size == 1) {
                    val mid = (yb + yt) / 2
                    text.textSize = textSize * 0.9f; textHalo.textSize = textSize * 0.9f
                    drawText(c, layer.cover.name, blockRight + 8 * density, mid - 2 * density, Paint.Align.LEFT, 0xFFFFFFFF.toInt())
                    text.textSize = textSize * 0.8f; textHalo.textSize = textSize * 0.8f
                    drawText(c, "${shortFt(layer.baseFt)}–${shortFt(layer.topFt)}", blockRight + 8 * density, mid + textSize * 0.85f, Paint.Align.LEFT, 0xFFE6EEF5.toInt())
                }
            }
        }

        if (geos.size > 1) {
            // Base bar tying the columns to the pin.
            staffHalo.strokeWidth = 4f * density
            staff.strokeWidth = 2f * density
            staff.color = 0xEEFFFFFF.toInt()
            c.drawLine(minOf(cxs.first(), x), y, maxOf(cxs.last(), x), y, staffHalo)
            c.drawLine(minOf(cxs.first(), x), y, maxOf(cxs.last(), x), y, staff)
        }

        geos.forEachIndexed { j, g ->
            val cx = cxs[j]
            val col = g.col
            staffHalo.strokeWidth = 4f * density
            staff.strokeWidth = 1.6f * density
            staff.color = col.color ?: 0xEEFFFFFF.toInt()
            c.drawLine(cx, y, cx, top, staffHalo)
            c.drawLine(cx, y, cx, top, staff)

            for (r in g.rows) {
                val w = r.w
                val cy = rowY(r.i)
                val color = colorFor(w, col)
                val kt = w.speedMs * KT_PER_MS
                if (kt < 1.0) {
                    line.color = color; line.strokeWidth = stroke * 0.8f
                    c.drawCircle(cx, cy, cell * 0.16f, line)
                } else {
                    val len = cell * STACK_LENGTH * stackedFraction(kt)
                    drawArrow(c, cx, cy, Math.toRadians(w.dirFromDeg + 180.0), len, stroke, color)
                }
                if (r.sw == 0f && r.tw == 0f) continue
                val ty = cy + textSize * 0.36f
                val tint = if (colorMode == ArrowColor.TEMPERATURE || w.tempC == null) 0xFFFFFFFF.toInt() else temperatureTint(w.tempC)
                if (!g.labelsLeft) {
                    var tx = cx + g.labelX
                    text.textSize = textSize; textHalo.textSize = textSize
                    drawText(c, r.speed, tx, ty, Paint.Align.LEFT, 0xFFFFFFFF.toInt())
                    tx += r.sw + gap4
                    if (r.temp != null && r.tw > 0f) {
                        text.textSize = tempSize; textHalo.textSize = tempSize
                        drawText(c, r.temp, tx, ty, Paint.Align.LEFT, tint)
                    }
                } else {
                    var tx = cx + g.labelX
                    if (r.temp != null && r.tw > 0f) {
                        text.textSize = tempSize; textHalo.textSize = tempSize
                        drawText(c, r.temp, tx, ty, Paint.Align.RIGHT, tint)
                        tx -= r.tw
                    }
                    text.textSize = textSize; textHalo.textSize = textSize
                    drawText(c, r.speed, tx, ty, Paint.Align.RIGHT, 0xFFFFFFFF.toInt())
                }
            }
            if (col.label != null) {
                text.textSize = textSize; textHalo.textSize = textSize
                drawText(c, col.label, cx, top - 6 * density, Paint.Align.CENTER, col.color ?: 0xFFFFFFFF.toInt())
            }
        }

        // Altitude labels down the left side.
        text.textSize = textSize; textHalo.textSize = textSize
        val altX = cxs.first() - geos.first().left - 2 * gap4
        for (i in skip until rows) {
            if (i % labelEvery != 0) continue
            val alt = columns.firstNotNullOfOrNull { it.winds.getOrNull(i) }?.altitudeFt ?: continue
            val label = if (alt == 0) "SFC" else if (alt % 1000 == 0) "${alt / 1000}k" else "$alt"
            drawText(c, label, altX, rowY(i) + textSize * 0.36f, Paint.Align.RIGHT, 0xFFFFFFFF.toInt())
        }
        // When MSL rows were dropped, the ground is at the pin: mark it.
        if (skip > 0) {
            val x0 = cxs.first() - geos.first().left
            ground.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
            ground.strokeWidth = 2f * density
            staffHalo.strokeWidth = 4f * density
            c.drawLine(x0, y, blockRight, y, staffHalo)
            c.drawLine(x0, y, blockRight, y, ground)
            drawText(c, "GND", altX, y + textSize * 0.36f, Paint.Align.RIGHT, 0xFFFFFFFF.toInt())
        }
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

        /** In the stacked layout an arrow reaches full length at this speed. */
        const val STACK_FULL_KT = 40.0

        /** Stacked arrow length as a fraction of full length: half at calm, full from STACK_FULL_KT. */
        fun stackedFraction(kt: Double): Float = 0.5f + 0.5f * (kt / STACK_FULL_KT).toFloat().coerceIn(0f, 1f)

        /** Fan arrow length in dp for a speed in knots; shared with the on-screen legend. */
        fun arrowLengthDp(kt: Double): Float = (12.0 + kt * 2.2).coerceAtMost(160.0).toFloat()
    }
}
