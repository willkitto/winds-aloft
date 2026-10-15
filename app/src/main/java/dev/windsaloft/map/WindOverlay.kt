package dev.windsaloft.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
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
 * (lowest at the bottom). Each arrow is centred in its own row cell and scaled to it, so rows
 * never overlap; a bigger arrow means a stronger wind, and the speed is printed beside it.
 * Fan: all arrows share the pin as origin, with length proportional to speed.
 */
class WindOverlay(private val density: Float) : Overlay() {
    var location: LatLon? = null
    var columns: List<WindColumn> = emptyList()
    var ceilingFt: Int = 18_000
    var layout: ArrowLayout = ArrowLayout.STACKED
    /** Screen covered by UI at the top / bottom; the columns are sized to fit between them. */
    var insetTopPx: Int = 0
    var insetBottomPx: Int = 0
    /** Speed text in the user's unit. */
    var speedLabel: (Double) -> String = { ms -> (ms * KT_PER_MS).toInt().toString() }

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
                ArrowLayout.STACKED -> drawColumns(c, mapView, x, y)
                ArrowLayout.FAN -> drawFan(c, x, y)
            }
        }
        drawPin(c, x, y)
    }

    // ---------------------------------------------------------------- Stacked columns

    private fun drawColumns(c: Canvas, mapView: MapView, x: Float, y: Float) {
        val rows = columns.maxOf { it.winds.size }
        if (rows == 0) return
        val visible = (mapView.height - insetTopPx - insetBottomPx).coerceAtLeast(mapView.height / 4)
        val labelRoom = if (columns.size > 1) 22 * density else 0f
        // Row height: fill most of the free map height, within sensible bounds.
        val cell = ((visible * 0.88f - labelRoom) / rows).coerceIn(MIN_CELL_DP * density, MAX_CELL_DP * density)
        val textSize = (cell * 0.42f).coerceIn(10f * density, 15f * density)
        text.textSize = textSize
        textHalo.textSize = textSize
        textHalo.strokeWidth = 3.5f * density
        val speedWidth = text.measureText("888") + 4 * density
        val gap = 10 * density
        val colWidth = cell + speedWidth + gap
        val stroke = (cell * 0.085f).coerceIn(2.4f * density, 4.2f * density)

        // Columns sit side by side; a single column is centred on the pin.
        val groupWidth = columns.size * colWidth - gap
        val firstCx = if (columns.size == 1) x else x - groupWidth / 2 + cell / 2
        val top = y - cell * rows

        if (columns.size > 1) {
            // Base bar tying the columns to the pin.
            staffHalo.strokeWidth = 4f * density
            staff.strokeWidth = 2f * density
            staff.color = 0xEEFFFFFF.toInt()
            val lastCx = firstCx + (columns.size - 1) * colWidth
            c.drawLine(firstCx, y, lastCx, y, staffHalo)
            c.drawLine(firstCx, y, lastCx, y, staff)
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
                val cy = y - cell * (i + 0.5f)
                val color = col.color ?: AltitudeColors.argb(w.altitudeFt, ceilingFt)
                val kt = w.speedMs * KT_PER_MS
                if (kt < 1.0) {
                    line.color = color; line.strokeWidth = stroke * 0.8f
                    c.drawCircle(cx, cy, cell * 0.16f, line)
                } else {
                    val len = cell * 0.95f * stackedFraction(kt)
                    // Centre the arrow on the cell: start half a length upwind of the centre.
                    val a = Math.toRadians(w.dirFromDeg + 180.0)
                    val sx = cx - sin(a).toFloat() * len / 2
                    val sy = cy + cos(a).toFloat() * len / 2
                    drawArrow(c, sx, sy, a, len, stroke, color)
                }
                if (i % labelEvery == 0) {
                    drawText(c, speedLabel(w.speedMs), cx + cell / 2 + 3 * density, cy + textSize * 0.36f, Paint.Align.LEFT, 0xFFFFFFFF.toInt())
                }
            }
            if (col.label != null) {
                drawText(c, col.label, cx, top - 6 * density, Paint.Align.CENTER, col.color ?: 0xFFFFFFFF.toInt())
            }
        }

        // Altitude labels down the left side.
        val altX = firstCx - cell / 2 - 5 * density

        // Rows below the terrain (MSL altitudes under the ground) have no data: mark where the ground is.
        val firstWithData = (0 until rows).firstOrNull { i -> columns.any { it.winds.getOrNull(i) != null } }
        if (firstWithData != null && firstWithData > 0) {
            val gy = y - cell * firstWithData
            val x0 = firstCx - cell / 2
            val x1 = firstCx + (columns.size - 1) * colWidth + cell / 2 + speedWidth
            ground.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
            ground.strokeWidth = 2f * density
            staffHalo.strokeWidth = 4f * density
            c.drawLine(x0, gy, x1, gy, staffHalo)
            c.drawLine(x0, gy, x1, gy, ground)
            drawText(c, "GND", altX, gy + textSize * 0.36f, Paint.Align.RIGHT, 0xFFFFFFFF.toInt())
        }
        val altitudes = columns.first().winds.indices.map { i -> columns.firstNotNullOfOrNull { it.winds.getOrNull(i) }?.altitudeFt }
        altitudes.forEachIndexed { i, alt ->
            if (alt == null || i % labelEvery != 0) return@forEachIndexed
            val label = if (alt == 0) "SFC" else if (alt % 1000 == 0) "${alt / 1000}k" else "$alt"
            drawText(c, label, altX, y - cell * (i + 0.5f) + textSize * 0.36f, Paint.Align.RIGHT, 0xFFFFFFFF.toInt())
        }
    }

    // ---------------------------------------------------------------- Fan

    private fun drawFan(c: Canvas, x: Float, y: Float) {
        val stroke = 3f * density
        // First (primary) source last so it ends up on top.
        for (col in columns.asReversed()) for (w in col.winds.filterNotNull().sortedByDescending { it.altitudeFt }) {
            val color = col.color ?: AltitudeColors.argb(w.altitudeFt, ceilingFt)
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
        const val MIN_CELL_DP = 16f
        const val MAX_CELL_DP = 56f

        /** In the stacked layout an arrow fills its row cell at this speed. */
        const val STACK_FULL_KT = 40.0

        /** Stacked arrow length as a fraction of the row cell. */
        fun stackedFraction(kt: Double): Float = 0.45f + 0.55f * (kt / STACK_FULL_KT).toFloat().coerceIn(0f, 1f)

        /** Fan arrow length in dp for a speed in knots; shared with the on-screen legend. */
        fun arrowLengthDp(kt: Double): Float = (12.0 + kt * 2.2).coerceAtMost(160.0).toFloat()
    }
}
