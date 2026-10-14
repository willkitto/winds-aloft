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

/** The arrows for one map location: one slot per selected altitude, low to high. */
data class WindFan(
    val location: LatLon,
    /** Null where there's no data (e.g. below ground), so slots line up across locations. */
    val winds: List<Wind?>,
    val emphasized: Boolean,
    /** Fixed colour (model comparison); null colours each arrow by altitude. */
    val color: Int? = null,
    /** Draw altitude (and speed) labels beside a stacked column. */
    val labels: Boolean = false,
)

/**
 * Draws wind arrows on the map. Each arrow points DOWNWIND (the way the air is moving) with
 * length proportional to speed. In the stacked layout each altitude gets its own row in a
 * vertical column rising from the location; in the fan layout all arrows share one origin.
 */
class WindOverlay(private val density: Float) : Overlay() {
    var fans: List<WindFan> = emptyList()
    var ceilingFt: Int = 18_000
    var layout: ArrowLayout = ArrowLayout.STACKED
    var gridSize: Int = 4
    var insetTopPx: Int = 0
    /** Screen covered at the bottom by the controls (sizes grid columns). */
    var insetBottomPx: Int = 0
    /** Screen covered at the bottom by all panels incl. the pin profile (sizes the pinned column). */
    var pinInsetBottomPx: Int = 0

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0x99000000.toInt()
    }
    private val headFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val headHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; color = 0x99000000.toInt()
    }
    private val staff = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xCCFFFFFF.toInt() }
    private val staffHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x88000000.toInt() }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF202020.toInt() }
    private val pinRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFE53935.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); isFakeBoldText = true }
    private val labelHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDD000000.toInt(); style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; isFakeBoldText = true
    }
    private val path = Path()
    private val pt = Point()

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        val gridHeight = (mapView.height - insetTopPx - insetBottomPx).coerceAtLeast(mapView.height / 4)
        val pinHeight = (mapView.height - insetTopPx - maxOf(insetBottomPx, pinInsetBottomPx)).coerceAtLeast(mapView.height / 4)
        val margin = 160 * density
        // Group fans by location: in comparison mode several models share one pinned column.
        val columns = fans.groupBy { it.location to it.emphasized }.values.sortedBy { it.first().emphasized }
        for (column in columns) {
            val first = column.first()
            proj.toPixels(GeoPoint(first.location.lat, first.location.lon), pt)
            val x = pt.x.toFloat()
            val y = pt.y.toFloat()
            val rows = column.maxOf { it.winds.size }
            val spacing = rowSpacing(first.emphasized, rows, if (first.emphasized) pinHeight else gridHeight)
            val height = if (layout == ArrowLayout.STACKED) spacing * rows else 0f
            if (x < -margin || x > mapView.width + margin || y < -margin || y - height > mapView.height + margin) continue

            when (layout) {
                ArrowLayout.STACKED -> drawStack(c, x, y, column, rows, spacing)
                ArrowLayout.FAN -> {
                    val stroke = (if (first.emphasized) 2.8f else 2.0f) * density
                    // Primary model last so it ends up on top.
                    for (fan in column.asReversed()) for (w in fan.winds.filterNotNull().sortedByDescending { it.altitudeFt }) {
                        drawArrow(c, x, y, w, stroke, fan.color, arrowLengthDp(w.speedMs * KT_PER_MS) * density)
                    }
                }
            }
            drawDot(c, x, y, first.emphasized)
        }
    }

    /**
     * A vertical column rising from the location, one row per altitude (lowest at the bottom).
     * Each arrow is centred in its own row cell and sized to fit it, so rows never overlap;
     * bigger arrow = stronger wind. Compared models sit side by side within a row.
     */
    private fun drawStack(c: Canvas, x: Float, y: Float, column: List<WindFan>, rows: Int, spacing: Float) {
        val emphasized = column.first().emphasized
        val cols = column.size
        val top = y - spacing * rows
        staffHalo.strokeWidth = 3f * density
        staff.strokeWidth = 1.2f * density
        c.drawLine(x, y, x, top, staffHalo)
        c.drawLine(x, y, x, top, staff)

        val stroke = (if (emphasized) 2.4f else 1.8f) * density
        column.forEachIndexed { slot, fan ->
            val cx = x + (slot - (cols - 1) / 2f) * spacing
            fan.winds.forEachIndexed { i, w ->
                if (w == null) return@forEachIndexed
                val cy = y - spacing * (i + 0.5f)
                val kt = w.speedMs * KT_PER_MS
                val len = spacing * 0.95f * (0.4f + 0.6f * (kt / STACK_FULL_KT).toFloat().coerceIn(0f, 1f))
                if (kt < 1.0) {
                    line.color = fan.color ?: AltitudeColors.argb(w.altitudeFt, ceilingFt); line.strokeWidth = stroke * 0.8f
                    c.drawCircle(cx, cy, spacing * 0.18f, line)
                    return@forEachIndexed
                }
                // Centre the arrow on the cell: start half a length upwind of the centre.
                val a = Math.toRadians(w.dirFromDeg + 180.0)
                val sx = cx - sin(a).toFloat() * len / 2
                val sy = cy + cos(a).toFloat() * len / 2
                drawArrow(c, sx, sy, w, stroke, fan.color, len)
            }
        }

        val labelled = column.firstOrNull { it.labels } ?: return
        val half = cols * spacing / 2
        drawLabels(c, x - half - 4 * density, y, spacing, labelled.winds, Paint.Align.RIGHT) { w ->
            val alt = w.altitudeFt
            if (alt == 0) "SFC" else if (alt % 1000 == 0) "${alt / 1000}k" else "$alt"
        }
        if (cols == 1) {
            drawLabels(c, x + half + 4 * density, y, spacing, labelled.winds, Paint.Align.LEFT) { w ->
                speedLabel(w.speedMs)
            }
        }
    }

    /** Rows are as tall as fits: half the visible map for the pin, one grid cell for grid points. */
    private fun rowSpacing(emphasized: Boolean, rows: Int, visibleHeight: Int): Float {
        val n = rows.coerceAtLeast(1)
        val room = if (emphasized || gridSize <= 0) visibleHeight * 0.75f else visibleHeight * 0.9f / gridSize
        val (min, max) = if (emphasized) 9f to 24f else 5f to 16f
        return (room / n).coerceIn(min * density, max * density)
    }

    private fun drawLabels(
        c: Canvas, tx: Float, y: Float, spacing: Float, winds: List<Wind?>, align: Paint.Align, text: (Wind) -> String,
    ) {
        val size = (spacing * 0.62f).coerceIn(8f * density, 11f * density)
        label.textSize = size; label.textAlign = align
        labelHalo.textSize = size; labelHalo.textAlign = align
        labelHalo.strokeWidth = 3f * density
        val every = ceil(size * 1.1f / spacing).toInt().coerceAtLeast(1)
        winds.forEachIndexed { i, w ->
            if (w == null || i % every != 0) return@forEachIndexed
            val ty = y - spacing * (i + 0.5f) + size * 0.36f
            val t = text(w)
            c.drawText(t, tx, ty, labelHalo)
            c.drawText(t, tx, ty, label)
        }
    }

    private fun drawDot(c: Canvas, x: Float, y: Float, emphasized: Boolean) {
        val r = (if (emphasized) 5f else 3f) * density
        dotRing.strokeWidth = 1.5f * density
        c.drawCircle(x, y, r, dotFill)
        c.drawCircle(x, y, r, dotRing)
        if (emphasized) {
            pinRing.strokeWidth = 2.5f * density
            c.drawCircle(x, y, r + 4 * density, pinRing)
        }
    }

    private fun drawArrow(c: Canvas, x: Float, y: Float, w: Wind, stroke: Float, fixedColor: Int?, len: Float) {
        val color = fixedColor ?: AltitudeColors.argb(w.altitudeFt, ceilingFt)
        val kt = w.speedMs * KT_PER_MS
        if (kt < 1.0) {
            // Calm: a small open circle.
            line.color = color; line.strokeWidth = stroke * 0.8f
            c.drawCircle(x, y, 7f * density, line)
            return
        }
        val a = Math.toRadians(w.dirFromDeg + 180.0) // downwind
        val dx = sin(a).toFloat()
        val dy = -cos(a).toFloat()
        val head = (stroke * 3f).coerceAtMost(len * 0.45f)
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

    /** Speed text for the pinned column, in the user's unit. */
    var speedLabel: (Double) -> String = { ms -> (ms * KT_PER_MS).toInt().toString() }

    companion object {
        /** In the stacked layout an arrow fills its row cell at this speed. */
        const val STACK_FULL_KT = 50.0

        /** Arrow length in dp for a speed in knots; shared with the on-screen legend. */
        fun arrowLengthDp(kt: Double): Float = (8.0 + kt * 1.3).coerceAtMost(110.0).toFloat()
    }
}
