package dev.windsaloft.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.view.MotionEvent
import dev.windsaloft.data.ArrowAnchor
import dev.windsaloft.data.ArrowColor
import dev.windsaloft.data.ArrowLayout
import dev.windsaloft.data.Glyph
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
import kotlin.math.hypot
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
    /** Where the chart is drawn when dragged away from [location] (null = on the forecast spot). */
    var chartLocation: LatLon? = null
    /** Called when the user drops the chart somewhere (null = re-attached to the forecast spot). */
    var onChartMoved: (LatLon?) -> Unit = {}
    /** A long-pressed spot awaiting confirmation. */
    var pendingLocation: LatLon? = null
    /** Called when the map's rotation changes (degrees clockwise; 0 = north up). */
    var onRotation: (Float) -> Unit = {}
    /** The map's current rotation; symbol directions are offset by it so they match the rotated map. */
    private var rotation = 0f
    /**
     * Which way the chart runs on screen, degrees clockwise (0 = rising straight up). The whole chart
     * turns, text included; the symbols in it still point along their real directions on the map.
     */
    var chartTurn: Float = 0f
    var glyph: Glyph = Glyph.ARROW
    var showSpeed: Boolean = true
    /** The altitude of each row (low to high), in the display reference. */
    var altitudesFt: List<Int> = emptyList()
    /** Speed text in the user's unit. */
    var speedLabel: (Double) -> String = { ms -> (ms * KT_PER_MS).toInt().toString() }
    /** Temperature text in the user's unit. */
    var tempLabel: (Double) -> String = { c -> "${c.toInt()}C" }
    /** Show the direction the wind comes from ("270°") in front of each speed. */
    var showDirection: Boolean = false

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
    private val barbStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val leader = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt() }
    private val leaderHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x99000000.toInt() }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val pt = Point()

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        pendingLocation?.let { p ->
            mapView.projection.toPixels(GeoPoint(p.lat, p.lon), pt)
            drawPendingMarker(c, pt.x.toFloat(), pt.y.toFloat())
        }
        val loc = location ?: return
        mapView.projection.toPixels(GeoPoint(loc.lat, loc.lon), pt)
        val pinX = pt.x.toFloat()
        val pinY = pt.y.toFloat()
        // The chart can be dragged away from the forecast spot; a leader line ties them together.
        val chart = dragTo ?: chartLocation
        val (x, y) = if (chart == null) pinX to pinY else {
            mapView.projection.toPixels(GeoPoint(chart.lat, chart.lon), pt)
            pt.x.toFloat() to pt.y.toFloat()
        }
        val detached = chart != null
        // The map may be rotated (the canvas arrives rotated with it). Track it so symbols can follow it.
        val orientation = mapView.mapOrientation
        if (orientation != rotation) { rotation = orientation; onRotation(orientation) }
        if (detached) {
            leader.pathEffect = android.graphics.DashPathEffect(floatArrayOf(7 * density, 5 * density), 0f)
            leaderHalo.strokeWidth = 5f * density
            leader.strokeWidth = 2.2f * density
            c.drawLine(x, y, pinX, pinY, leaderHalo)
            c.drawLine(x, y, pinX, pinY, leader)
        }
        drawPin(c, pinX, pinY)
        // Keep the chart upright on screen (or turned by chartTurn): undo the map's rotation around the
        // chart's anchor. The symbols inside add it back, so each still points along its real direction.
        c.save()
        c.rotate(-rotation + chartTurn, x, y)
        if (columns.isNotEmpty()) {
            when (layout) {
                ArrowLayout.STACKED -> drawColumns(c, x, y)
                ArrowLayout.FAN -> drawFan(c, x, y)
            }
        }
        val handleOffset = drawHandle(c, x, y, detached)
        c.restore()
        // Touch events arrive in the same (rotated) canvas frame: place the handle's hit spot there.
        val o = Math.toRadians((chartTurn - rotation).toDouble())
        handleX = x - handleOffset * sin(o).toFloat()
        handleY = y + handleOffset * cos(o).toFloat()
        pinPx = pinX to pinY
    }

    // ---------------------------------------------------------------- Dragging the chart

    private var handleX = Float.NaN
    private var handleY = Float.NaN
    private var pinPx = Float.NaN to Float.NaN
    private var dragging = false
    private var dragTo: LatLon? = null

    /** Drag the chart's handle to move only the chart; drop it near the forecast spot to re-attach. */
    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (location == null || handleX.isNaN() || event.pointerCount > 1) return false
                if (hypot(event.x - handleX, event.y - handleY) > HANDLE_TOUCH_DP * density) return false
                dragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val g = mapView.projection.fromPixels(event.x.toInt(), event.y.toInt())
                dragTo = LatLon(g.latitude, g.longitude)
                mapView.invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                val target = dragTo
                dragTo = null
                if (target != null) {
                    val nearPin = hypot(event.x - pinPx.first, event.y - pinPx.second) < SNAP_DP * density
                    chartLocation = if (nearPin) null else target
                    onChartMoved(chartLocation)
                }
                mapView.invalidate()
                return true
            }
        }
        return false
    }

    /** A round grip just below the chart's base: drag it to move the chart. Returns its distance below the base. */
    private fun drawHandle(c: Canvas, x: Float, y: Float, detached: Boolean): Float {
        val r = 9 * density
        handleFill.color = if (detached) 0xFF1C2733.toInt() else 0xE61C2733.toInt()
        c.drawCircle(x, y + r + 6 * density, r, handleFill)
        handleRing.strokeWidth = 1.5f * density
        c.drawCircle(x, y + r + 6 * density, r, handleRing)
        // Four-way move arrows.
        val cy = y + r + 6 * density
        val a = r * 0.55f
        handleRing.strokeWidth = 1.6f * density
        c.drawLine(x - a, cy, x + a, cy, handleRing)
        c.drawLine(x, cy - a, x, cy + a, handleRing)
        return r + 6 * density
    }

    /** A provisional spot from a long-press, shown until the user confirms or cancels. */
    private fun drawPendingMarker(c: Canvas, x: Float, y: Float) {
        leader.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5 * density, 4 * density), 0f)
        leaderHalo.strokeWidth = 5f * density
        leader.strokeWidth = 2.2f * density
        c.drawCircle(x, y, 14 * density, leaderHalo)
        c.drawCircle(x, y, 14 * density, leader)
        c.drawCircle(x, y, 3 * density, dotFill)
        c.drawCircle(x, y, 3 * density, dotRing)
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
        // Sized for the widest possible text, so the slot never changes with the numbers.
        // The ground row also shows gusts, "12 (25)": reserve room for that whenever there is a ground row.
        val hasSurface = columns.any { c -> c.winds.any { it?.surface == true } }
        val speedTemplate = if (hasSurface) "88 (88)" else "888"
        val topTemplate = listOfNotNull(if (showDirection) "888°" else null, if (showSpeed) speedTemplate else null).joinToString(" ")
        val topW = if (topTemplate.isEmpty()) 0f else width(topTemplate, textSize)
        val tempW = if (showTemperature) width("-88F", tempSize) else 0f
        val labelW = if (topW == 0f && tempW == 0f) 0f else maxOf(topW, tempW) + 6 * density
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
                drawGlyph(c, cx, cy, w, cell * lenScale, cell * 0.16f, stroke, color, anchor)
                if (i % labelEvery != 0 && !w.surface) return@forEachIndexed
                // Label slot: direction + speed on top, temperature underneath, always at the same spot.
                val lx = cx + reach + 4 * density
                val hasTemp = showTemperature && w.tempC != null
                val topLine = listOfNotNull(
                    if (showDirection) "%03d°".format(Math.round(w.dirFromDeg).toInt() % 360) else null,
                    if (showSpeed) speedLabel(w.speedMs) + (w.gustMs?.let { " (${speedLabel(it)})" } ?: "") else null,
                ).joinToString(" ")
                if (topLine.isNotEmpty()) {
                    text.textSize = textSize; textHalo.textSize = textSize
                    drawText(c, topLine, lx, if (hasTemp) cy - density else cy + textSize * 0.36f, Paint.Align.LEFT, 0xFFFFFFFF.toInt())
                }
                if (hasTemp) {
                    text.textSize = tempSize; textHalo.textSize = tempSize
                    val tint = if (colorMode == ArrowColor.TEMPERATURE) 0xFFFFFFFF.toInt() else temperatureTint(w.tempC!!)
                    drawText(c, tempLabel(w.tempC), lx, if (topLine.isEmpty()) cy + tempSize * 0.36f else cy + tempSize + density, Paint.Align.LEFT, tint)
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
        val surfaceRow = (skip until rows).firstOrNull { i -> columns.any { it.winds.getOrNull(i)?.surface == true } }
        for (i in skip until rows) {
            if (i % labelEvery != 0 && i != surfaceRow) continue
            val alt = rowAlt(i) ?: continue
            val label = if (alt == 0 || i == surfaceRow) "SFC" else if (alt % 1000 == 0) "${alt / 1000}k" else "$alt"
            drawText(c, label, altX, rowY(i) + textSize * 0.36f, Paint.Align.RIGHT, 0xFFFFFFFF.toInt())
        }
        // When MSL rows were dropped, the ground is at the pin: mark it (unless the ground-wind row already does).
        if (skip > 0 && surfaceRow != skip) {
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

    private fun colorFor(w: Wind, col: WindColumn): Int = WindColors.of(colorMode, w, col.color, ceilingFt)

    /**
     * Draws one wind glyph. Each runs from its tail to its nose (arrowhead, triangle apex, or a barb's
     * feathered end); [anchor] picks which end sits at ([cx], [cy]). Arrows point downwind (a windsock),
     * triangles point into the wind (a landing tetrahedron), barbs follow the weather-chart convention.
     */
    private fun drawGlyph(
        c: Canvas, cx: Float, cy: Float, w: Wind, fullLen: Float, calmRadius: Float, stroke: Float, color: Int,
        anchor: ArrowAnchor, scaleBySpeed: Boolean = true,
    ) {
        val kt = w.speedMs * KT_PER_MS
        if (glyph == Glyph.BARB) return drawBarb(c, cx, cy, w, fullLen, calmRadius, stroke, color, anchor)
        if (kt < 1.0) {
            line.color = color; line.strokeWidth = stroke * 0.8f
            c.drawCircle(cx, cy, calmRadius, line)
            return
        }
        val len = if (scaleBySpeed) fullLen * stackedFraction(kt) else fullLen
        val a = Math.toRadians(w.dirFromDeg + rotation - chartTurn + if (glyph == Glyph.TRIANGLE) 0.0 else 180.0)
        val dx = sin(a).toFloat()
        val dy = -cos(a).toFloat()
        val back = when (anchor) { ArrowAnchor.TAIL -> 0f; ArrowAnchor.NOSE -> len; ArrowAnchor.CENTRE -> len / 2 }
        val sx = cx - dx * back
        val sy = cy - dy * back
        if (glyph == Glyph.ARROW) return drawArrow(c, sx, sy, a, len, stroke, color)
        // Triangle: apex at the nose, base at the tail.
        val ex = sx + dx * len
        val ey = sy + dy * len
        val hw = len * 0.22f
        path.reset()
        path.moveTo(ex, ey)
        path.lineTo(sx - dy * hw, sy + dx * hw)
        path.lineTo(sx + dy * hw, sy - dx * hw)
        path.close()
        headHalo.strokeWidth = 2.2f * density
        headHalo.color = AltitudeColors.haloFor(color)
        c.drawPath(path, headHalo)
        headFill.color = color
        c.drawPath(path, headFill)
    }

    /** Wind barb: the staff points into the wind; 50 kt pennants, 10 kt barbs, 5 kt half-barbs. */
    private fun drawBarb(c: Canvas, cx: Float, cy: Float, w: Wind, len: Float, calmRadius: Float, stroke: Float, color: Int, anchor: ArrowAnchor) {
        val kt = Math.round(w.speedMs * KT_PER_MS / 5) * 5
        if (kt < 5) {
            line.color = color; line.strokeWidth = stroke * 0.8f
            c.drawCircle(cx, cy, calmRadius, line)
            return
        }
        val a = Math.toRadians(w.dirFromDeg + rotation - chartTurn) // toward where the wind comes from
        val dx = sin(a).toFloat()
        val dy = -cos(a).toFloat()
        // Tail = the plain (station) end, nose = the feathered end, which faces into the wind.
        val back = when (anchor) { ArrowAnchor.TAIL -> 0f; ArrowAnchor.NOSE -> len; ArrowAnchor.CENTRE -> len / 2 }
        val sx = cx - dx * back
        val sy = cy - dy * back
        val ex = sx + dx * len
        val ey = sy + dy * len
        val px = -dy
        val py = dx // feathers on the clockwise side
        val fl = len * 0.42f
        val step = len * 0.15f
        var rest = kt.toInt()
        var pos = 0f
        val pennants = mutableListOf<Float>()
        path.reset()
        path.moveTo(sx, sy); path.lineTo(ex, ey)
        while (rest >= 50) { pennants += pos; pos += step * 1.3f; rest -= 50 }
        fun feather(at: Float, l: Float) {
            val bx = ex - dx * at
            val by = ey - dy * at
            path.moveTo(bx, by); path.lineTo(bx + px * l + dx * l * 0.35f, by + py * l + dy * l * 0.35f)
        }
        while (rest >= 10) { feather(pos, fl); pos += step; rest -= 10 }
        if (rest >= 5) feather(if (pos == 0f) step else pos, fl * 0.55f)
        val sw = maxOf(1.6f * density, stroke * 0.7f)
        barbStroke.strokeWidth = sw + 2.4f * density; barbStroke.color = AltitudeColors.haloFor(color)
        c.drawPath(path, barbStroke)
        barbStroke.strokeWidth = sw; barbStroke.color = color
        c.drawPath(path, barbStroke)
        headFill.color = color
        for (p in pennants) {
            val bx = ex - dx * p
            val by = ey - dy * p
            path.reset()
            path.moveTo(bx, by); path.lineTo(bx + px * fl, by + py * fl); path.lineTo(bx - dx * step * 1.1f, by - dy * step * 1.1f); path.close()
            c.drawPath(path, headFill)
        }
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
            // In the fan every glyph starts at the pin; arrows and triangles are sized by speed.
            val len = if (glyph == Glyph.BARB) 44 * density else arrowLengthDp(kt) * density
            drawGlyph(c, x, y, w, len, 8f * density, stroke, color, ArrowAnchor.TAIL, scaleBySpeed = false)
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
        halo.color = AltitudeColors.haloFor(color)
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
        headHalo.color = AltitudeColors.haloFor(color)
        c.drawPath(path, headHalo)
        headFill.color = color
        c.drawPath(path, headFill)
    }

    companion object {
        /** How close (dp) a touch must be to the chart handle to start dragging it. */
        private const val HANDLE_TOUCH_DP = 28f

        /** Dropping the chart within this distance (dp) of the forecast spot re-attaches it. */
        private const val SNAP_DP = 36f

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
