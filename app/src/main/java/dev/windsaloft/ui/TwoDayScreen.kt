package dev.windsaloft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windsaloft.UiState
import dev.windsaloft.data.OpenMeteo
import dev.windsaloft.wind.AltitudeRef
import dev.windsaloft.wind.ForecastPoint
import dev.windsaloft.wind.KT_PER_MS
import dev.windsaloft.wind.M_PER_FT
import dev.windsaloft.wind.Sun
import dev.windsaloft.wind.WindMath
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val HOURS = 48
/** Hours across one screen width; scroll for the rest. */
private const val WINDOW = 24

/**
 * The next 48 hours for the pinned spot, on its own page, 24 hours to a screen. "Winds": one strip per
 * altitude, stacked like the map chart; the line's height is the speed and it's drawn with arrows showing
 * the direction, each strip labelled with its high and low, and shaded grey where the model has cloud at
 * that altitude. "Clouds, all models": every selected model's cloud forecast as a small time-by-altitude
 * picture, one under another. Drag (or tap) on a chart to move the time bar; drag the hour labels, push
 * the time bar past the edge, or use the 24 h buttons to see the other day.
 */
@Composable
fun TwoDayScreen(state: UiState, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onDismiss)
    val s = state.settings
    val models = s.modelIds
    var modelId by rememberSaveable { mutableStateOf(state.visibleModelIds.first()) }
    if (modelId !in models) modelId = models.first()
    var cloudsAll by rememberSaveable { mutableStateOf(false) }
    var cursor by rememberSaveable { mutableIntStateOf(state.hourOffset.coerceIn(0, HOURS)) }
    val zone = remember { ZoneId.systemDefault() }
    val scroll = remember { Animatable(if (cursor >= WINDOW) 1f else 0f) }   // 0 = first 24 h, 1 = next 24 h
    val scope = rememberCoroutineScope()
    val shade = DayNight.of(MaterialTheme.colorScheme.surface.luminance() < 0.5f)

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the map") }
                Column(Modifier.weight(1f)) {
                    Text("Next 48 hours", style = MaterialTheme.typography.titleMedium)
                    Text(
                        (state.pinLabel ?: state.pin?.let { Format.coords(it) } ?: "") + " · " + s.altitudeRef.name,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                }
            }
            @OptIn(ExperimentalMaterial3Api::class)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SegmentedButton(!cloudsAll, { cloudsAll = false }, SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text("Winds") }
                SegmentedButton(cloudsAll, { cloudsAll = true }, SegmentedButtonDefaults.itemShape(1, 2), icon = {}) { Text("Clouds, all models") }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Format.time(state.nowHourEpoch + cursor * 3600L) + "  (+$cursor h)",
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { scope.launch { scroll.animateTo(0f) } }, enabled = scroll.value > 0.01f) { Text("‹ First 24 h") }
                TextButton(onClick = { scope.launch { scroll.animateTo(1f) } }, enabled = scroll.value < 0.99f) { Text("Next 24 h ›") }
            }
            val axis = remember { AxisModel({ scroll.value }) { f -> scope.launch { scroll.snapTo(f.coerceIn(0f, 1f)) } } }
            if (!cloudsAll) {
                // Only the models picked in Settings; one tap switches.
                if (models.size > 1) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        models.forEach { id ->
                            val has = state.pinForecasts[id] != null
                            FilterChip(
                                selected = id == modelId, enabled = has, onClick = { modelId = id },
                                label = { Text(OpenMeteo.short(id) + when { id in state.unavailableModels -> " · n/a"; !has -> " …"; else -> "" }) },
                            )
                        }
                    }
                }
                val fp = state.pinForecasts[modelId]
                if (fp == null) {
                    Text(
                        when {
                            modelId in state.unavailableModels -> "${OpenMeteo.label(modelId)} doesn't cover this spot."
                            state.pinLoading -> "Loading forecast…"
                            else -> "No forecast for this model yet."
                        },
                        Modifier.padding(24.dp),
                    )
                } else {
                    WindStrips(state, modelId, fp, cursor, zone, axis, shade, Modifier.weight(1f).fillMaxWidth(), onCursor = { cursor = it })
                }
            } else {
                AllModelClouds(state, models.filter { state.pinForecasts[it] != null }, cursor, zone, axis, shade, Modifier.weight(1f).fillMaxWidth(), onCursor = { cursor = it })
            }
        }
    }
}

/** Day and night shading that stays clearly different in light and dark themes. */
private data class DayNight(val day: Color, val night: Color, val nightInk: Color) {
    companion object {
        fun of(dark: Boolean) = if (dark) {
            DayNight(day = Color(0xFFFFD66B).copy(alpha = 0.07f), night = Color.Black.copy(alpha = 0.5f), nightInk = Color(0xFF9FB3D1))
        } else {
            DayNight(day = Color(0xFFFFD54F).copy(alpha = 0.10f), night = Color(0xFF1E3054).copy(alpha = 0.13f), nightInk = Color(0xFF3B4F72))
        }
    }
}

/**
 * Which 24 hours are on screen: [scroll] reads the live position, from 0 (now to +24 h) to 1 (+24 to
 * +48 h). Reading it while drawing redraws the charts as it moves.
 */
private class AxisModel(private val get: () -> Float, val setScroll: (Float) -> Unit) {
    val scroll: Float get() = get()
}

/** Maps hours to x for a plot from [left] to [right] showing [WINDOW] hours, scrolled by [scroll]. */
private class TimeAxis(val left: Float, val right: Float, scroll: Float) {
    private val perHour = (right - left) / WINDOW
    private val offset = scroll * (HOURS - WINDOW) * perHour
    fun x(h: Float) = left + h * perHour - offset
    fun hourAt(px: Float) = ((px - left + offset) / perHour).roundToInt().coerceIn(0, HOURS)
    /** Scroll position after dragging the axis by [dx] pixels. */
    fun scrollAfter(scroll: Float, dx: Float) = scroll - dx / ((HOURS - WINDOW) * perHour)
    fun visible(h: Int) = x(h.toFloat()) in left..right
    /** Room for a centred label at hour [h] without being cut off at the edges. */
    fun labelFits(h: Int, half: Float) = x(h.toFloat()) in (left + half)..(right - half)
}

/**
 * Gestures shared by the charts: a tap or a drag on the plot moves the time bar (and pushes the view
 * along when it reaches an edge); a drag that starts on the hour labels scrolls instead.
 */
private fun Modifier.timeGestures(axis: AxisModel, labelZone: (Float, Float) -> Boolean, onCursor: (Int) -> Unit, left: Float, rightPad: Float): Modifier =
    pointerInput(Unit) {
        detectTapGestures { p -> onCursor(TimeAxis(left, size.width - rightPad, axis.scroll).hourAt(p.x)) }
    }.pointerInput(Unit) {
        var scrolling = false
        var current = 0f
        detectHorizontalDragGestures(
            onDragStart = { p -> scrolling = labelZone(p.y, size.height.toFloat()); current = axis.scroll },
        ) { change, dx ->
            val ax = TimeAxis(left, size.width - rightPad, current)
            if (scrolling) {
                current = ax.scrollAfter(current, dx).coerceIn(0f, 1f)
                axis.setScroll(current)
            } else {
                val x = change.position.x
                // Past an edge: move the view so the time bar stays on screen.
                val edge = 18.dp.toPx()
                if (x > ax.right - edge && current < 1f) { current = ax.scrollAfter(current, -(x - (ax.right - edge)) * 0.25f - 2f).coerceIn(0f, 1f); axis.setScroll(current) }
                if (x < ax.left + edge && current > 0f) { current = ax.scrollAfter(current, ((ax.left + edge) - x) * 0.25f + 2f).coerceIn(0f, 1f); axis.setScroll(current) }
                onCursor(TimeAxis(left, size.width - rightPad, current).hourAt(x))
            }
            change.consume()
        }
    }

@Composable
private fun WindStrips(
    state: UiState, modelId: String, fp: ForecastPoint, cursor: Int, zone: ZoneId, axis: AxisModel, shade: DayNight,
    modifier: Modifier, onCursor: (Int) -> Unit,
) {
    val s = state.settings
    // All altitudes up to the ceiling, ground at the bottom; the map's altitude range is ignored here.
    val altitudes = remember(s.ceilingFt) { (0..s.ceilingFt / 1000).map { it * 1000 } }
    val winds = remember(fp, state.nowHourEpoch, s.altitudeRef, altitudes) {
        (0..HOURS).map { h -> fp.windsAt(state.nowHourEpoch + h * 3600L, altitudes, s.altitudeRef) }
    }
    // Rows that have data at some hour (MSL rows under the ground drop out; the ground row stays).
    val rows = remember(winds) { altitudes.indices.filter { i -> winds.any { it[i] != null } }.reversed() }
    val cloud = remember(fp, state.nowHourEpoch, s.altitudeRef, altitudes) {
        val base = if (s.altitudeRef == AltitudeRef.AGL) fp.elevationM else 0.0
        (0..HOURS).map { h ->
            val levels = fp.clouds.getOrNull(fp.hourIndex(state.nowHourEpoch + h * 3600L)).orEmpty()
            altitudes.map { ft ->
                val m = base + ft * M_PER_FT
                if (levels.isEmpty() || m < fp.elevationM) 0.0 else WindMath.cloudAt(levels, m) ?: 0.0
            }
        }
    }
    val maxKt = remember(winds) {
        winds.flatten().filterNotNull().maxOfOrNull { maxOf(it.speedMs, it.gustMs ?: 0.0) * KT_PER_MS }?.let { Math.ceil(it / 10) * 10 }?.coerceAtLeast(10.0) ?: 40.0
    }
    val day = remember(state.pin, state.nowHourEpoch) { dayHours(state) }
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val cloudInk = Color(0xFF6E7C8C)
    val density = LocalDensity.current.density
    val left = 44 * density
    val rightPad = 10 * density
    val top = 30 * density
    val bottomAxis = 20 * density

    BoxWithConstraints(modifier) {
        val stripH: Dp = maxOf(36.dp, (maxHeight - 72.dp) / rows.size.coerceAtLeast(1))
        val height = stripH * rows.size + 30.dp + 20.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "Strip height = 0 to ${Format.speed(maxKt / KT_PER_MS, s.unit)} ${s.unit.label}. Numbers: each altitude's high and low on screen. Grey: cloud at that altitude. Drag to move the time bar; drag the hour labels to scroll.",
                style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(horizontal = 16.dp),
            )
            Canvas(
                Modifier.fillMaxWidth().height(height)
                    .semantics { contentDescription = "Wind at each altitude over the next 48 hours, ${OpenMeteo.label(modelId)}" }
                    .timeGestures(axis, { y, h -> y < top || y > h - bottomAxis }, onCursor, left, rightPad),
            ) {
                val ax = TimeAxis(left, size.width - rightPad, axis.scroll)
                val sh = stripH.toPx()
                val bottom = top + sh * rows.size
                clipRect(ax.left, 0f, ax.right, size.height) {
                    dayNight(ax, day, shade, top, bottom, measurer)
                    hourAxis(ax, state, zone, top, bottom, measurer, ink, muted, line)
                    rows.forEachIndexed { k, ai ->
                        val yb = top + sh * (k + 1)
                        val yt = yb - sh
                        fun yv(kt: Double) = yb - 3.dp.toPx() - ((sh - 9.dp.toPx()) * (kt / maxKt).coerceAtMost(1.0)).toFloat()
                        for (h in 0 until HOURS) {
                            val pct = cloud[h][ai]
                            if (pct >= WindMath.CLOUD_LAYER_MIN_PCT) drawRect(cloudInk.copy(alpha = (0.14 + 0.42 * pct / 100).toFloat()), Offset(ax.x(h.toFloat()), yt), Size(ax.x(h + 1f) - ax.x(h.toFloat()) + 0.5f, sh))
                        }
                        drawLine(line, Offset(ax.left, yb), Offset(ax.right, yb), 1f)
                        val series = winds.map { it[ai] }
                        val area = Path(); val path = Path(); var started = false; var lastH = 0
                        series.forEachIndexed { h, w ->
                            if (w == null) return@forEachIndexed
                            val px = ax.x(h.toFloat()); val py = yv(w.speedMs * KT_PER_MS)
                            if (!started) { area.moveTo(px, yb); area.lineTo(px, py); path.moveTo(px, py); started = true } else { area.lineTo(px, py); path.lineTo(px, py) }
                            lastH = h
                        }
                        if (started) {
                            area.lineTo(ax.x(lastH.toFloat()), yb); area.close()
                            drawPath(area, accent.copy(alpha = 0.13f))
                            drawPath(path, accent, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
                        }
                        if (series.any { it?.gustMs != null }) {
                            val g = Path(); var gs = false
                            series.forEachIndexed { h, w -> w?.gustMs?.let { v -> val px = ax.x(h.toFloat()); val py = yv(v * KT_PER_MS); if (gs) g.lineTo(px, py) else { g.moveTo(px, py); gs = true } } }
                            drawPath(g, accent, style = Stroke(1.3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))))
                        }
                        // Arrows along the line (downwind, like the map's windsocks): every 2 hours at 24 h a screen.
                        series.forEachIndexed { h, w -> if (w != null && h % 2 == 0) arrowAt(Offset(ax.x(h.toFloat()), yv(w.speedMs * KT_PER_MS)), w.dirFromDeg + 180, 13.dp.toPx(), ink, surface) }
                        // High and low for this altitude in the hours on screen (they update as you scroll), and the gust peak.
                        val present = series.withIndex().filter { it.value != null && ax.visible(it.index) }
                        if (present.isNotEmpty()) {
                            val hi = present.maxBy { it.value!!.speedMs }
                            val lo = present.minBy { it.value!!.speedMs }
                            fun lx(h: Int) = ax.x(h.toFloat()).coerceAtLeast(ax.left + 9.dp.toPx())
                            txt(measurer, Format.speed(hi.value!!.speedMs, s.unit), Offset(lx(hi.index), yv(hi.value!!.speedMs * KT_PER_MS) - 15.dp.toPx()), ink, 10.5f, bold = true, center = true, box = surface)
                            if (abs(hi.index - lo.index) >= 4 && hi.value!!.speedMs - lo.value!!.speedMs > 1.0) {
                                txt(measurer, Format.speed(lo.value!!.speedMs, s.unit), Offset(lx(lo.index), yv(lo.value!!.speedMs * KT_PER_MS) + 3.dp.toPx()), muted, 10f, center = true, box = surface)
                            }
                            series.withIndex().filter { it.value?.gustMs != null && ax.visible(it.index) }.maxByOrNull { it.value!!.gustMs!! }?.let { gp ->
                                txt(measurer, "(${Format.speed(gp.value!!.gustMs!!, s.unit)})", Offset(lx(gp.index) + 8.dp.toPx(), yv(gp.value!!.gustMs!! * KT_PER_MS) - 14.dp.toPx()), ink, 10f, box = surface)
                            }
                        }
                    }
                    // Time bar with the wind and temperature at that time on every strip.
                    val cx = ax.x(cursor.toFloat())
                    drawLine(ink.copy(alpha = 0.8f), Offset(cx, top), Offset(cx, bottom), 1.5.dp.toPx())
                    val onRight = cx < (ax.left + ax.right) / 2 + 40.dp.toPx()
                    rows.forEachIndexed { k, ai ->
                        val w = winds[cursor][ai] ?: return@forEachIndexed
                        val yb = top + sh * (k + 1)
                        val v = "${Format.direction(w.dirFromDeg)} ${Format.speed(w.speedMs, s.unit)}" + (w.gustMs?.let { " (${Format.speed(it, s.unit)})" } ?: "") +
                            (w.tempC?.let { " · " + s.tempUnit.format(it) } ?: "")
                        txt(measurer, v, Offset(cx + if (onRight) 5.dp.toPx() else -5.dp.toPx(), yb - sh / 2 - 7.dp.toPx()), ink, 10.5f, bold = true, right = !onRight, box = surface)
                    }
                }
                // Altitude labels, fixed on the left while the hours scroll.
                rows.forEachIndexed { k, ai ->
                    val yb = top + sh * (k + 1)
                    val label = if (winds.any { it[ai]?.surface == true }) "SFC" else "${altitudes[ai] / 1000}k"
                    txt(measurer, label, Offset(ax.left - 6.dp.toPx(), yb - sh / 2 - 7.dp.toPx()), ink, 11f, bold = true, right = true)
                }
                scrollBar(ax, axis.scroll, size.height - 3.dp.toPx(), muted)
            }
        }
    }
}

@Composable
private fun AllModelClouds(
    state: UiState, models: List<String>, cursor: Int, zone: ZoneId, axis: AxisModel, shade: DayNight,
    modifier: Modifier, onCursor: (Int) -> Unit,
) {
    val s = state.settings
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant
    val cloudInk = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) Color(0xFFC9D3DE) else Color(0xFF55657A)
    val ceiling = s.ceilingFt
    val step = 500
    val day = remember(state.pin, state.nowHourEpoch) { dayHours(state) }
    val density = LocalDensity.current.density
    val left = 44 * density
    val rightPad = 10 * density
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Text(
            "Each picture is one model: time across, altitude up (0 to ${Format.feet(ceiling)} ft ${s.altitudeRef.name}), stronger shade for more cloud. Drag to move the time bar.",
            style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (models.isEmpty()) Text(if (state.pinLoading) "Loading forecasts…" else "No forecasts available.", Modifier.padding(16.dp))
        models.forEach { id ->
            val fp = state.pinForecasts[id] ?: return@forEach
            val grid = remember(fp, state.nowHourEpoch, s.altitudeRef, ceiling) {
                val base = if (s.altitudeRef == AltitudeRef.AGL) fp.elevationM else 0.0
                (0..HOURS).map { h ->
                    val levels = fp.clouds.getOrNull(fp.hourIndex(state.nowHourEpoch + h * 3600L)).orEmpty()
                    (0..ceiling / step).map { k ->
                        val m = base + k * step * M_PER_FT
                        if (levels.isEmpty() || m < fp.elevationM) -1.0 else WindMath.cloudAt(levels, m) ?: 0.0
                    }
                }
            }
            val noData = grid.all { col -> col.all { it < 0 } }
            val layers = fp.cloudLayers(state.nowHourEpoch + cursor * 3600L, s.altitudeRef, 0, ceiling)
            Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(OpenMeteo.short(id), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.width(56.dp))
                Text(
                    when { noData -> "no cloud data"; layers.isNullOrEmpty() -> "clear at the time bar"; else -> Format.clouds(layers, short = true) },
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
            Canvas(Modifier.fillMaxWidth().height(92.dp).timeGestures(axis, { y, h -> y > h - 16 * density }, onCursor, left, rightPad)) {
                val ax = TimeAxis(left, size.width - rightPad, axis.scroll)
                val top = 4.dp.toPx(); val bottom = size.height - 16.dp.toPx()
                fun y(ft: Float) = bottom - (bottom - top) * ft / ceiling
                clipRect(ax.left, 0f, ax.right, size.height) {
                    dayNight(ax, day, shade, top, bottom, null)
                    for (h in 0 until HOURS) grid[h].forEachIndexed { k, pct ->
                        if (pct >= WindMath.CLOUD_LAYER_MIN_PCT) drawRect(
                            cloudInk.copy(alpha = (0.2 + 0.65 * pct / 100).toFloat().coerceAtMost(0.88f)),
                            Offset(ax.x(h.toFloat()), y((k + 0.5f) * step)), Size(ax.x(h + 1f) - ax.x(h.toFloat()) + 0.5f, y(0f) - y(step.toFloat()) + 0.5f),
                        )
                    }
                    for (h in 0..HOURS) {
                        val zt = Instant.ofEpochSecond(state.nowHourEpoch + h * 3600L).atZone(zone)
                        if (zt.hour % 6 == 0 && ax.labelFits(h, 12.dp.toPx())) txt(measurer, if (zt.hour == 0) DAY_SHORT.format(zt) else HOUR.format(zt).lowercase(Locale.US), Offset(ax.x(h.toFloat()), bottom + 1.dp.toPx()), muted, 9.5f, center = true)
                    }
                    val cx = ax.x(cursor.toFloat())
                    drawLine(ink.copy(alpha = 0.8f), Offset(cx, top), Offset(cx, bottom), 1.5.dp.toPx())
                }
                drawRect(line, Offset(ax.left, top), Size(ax.right - ax.left, bottom - top), style = Stroke(1f))
                listOf(0, ceiling / 3 / 1000 * 1000, ceiling * 2 / 3 / 1000 * 1000, ceiling).forEach { ft ->
                    drawLine(line, Offset(ax.left, y(ft.toFloat())), Offset(ax.right, y(ft.toFloat())), 0.6f)
                    txt(measurer, if (ft == 0) "0" else "${ft / 1000}k", Offset(ax.left - 5.dp.toPx(), y(ft.toFloat()) - 6.dp.toPx()), muted, 9.5f, right = true)
                }
                if (noData) txt(measurer, "This model gives no cloud levels", Offset((ax.left + ax.right) / 2, (top + bottom) / 2 - 7.dp.toPx()), muted, 11f, center = true)
            }
        }
        val missing = s.modelIds.filter { it !in models }
        if (missing.isNotEmpty()) {
            Text(
                "Not shown: " + missing.joinToString(", ") { OpenMeteo.short(it) + if (it in state.unavailableModels) " (doesn't cover this spot)" else " (not downloaded yet)" },
                style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** Daylight for each of the 48 hours at the pinned spot. */
private fun dayHours(state: UiState): List<Boolean> = (0 until HOURS).map { h ->
    state.pin?.let { Sun.isDay(it.lat, it.lon, state.nowHourEpoch + h * 3600L + 1800) } ?: true
}

/** Day and night bands, with a sun or moon at the start of each when [m] is given. */
private fun DrawScope.dayNight(ax: TimeAxis, day: List<Boolean>, shade: DayNight, top: Float, bottom: Float, m: TextMeasurer?) {
    for (h in 0 until HOURS) {
        drawRect(if (day[h]) shade.day else shade.night, Offset(ax.x(h.toFloat()), top), Size(ax.x(h + 1f) - ax.x(h.toFloat()) + 0.5f, bottom - top))
    }
    if (m == null) return
    var h = 0
    while (h < HOURS) {
        val start = h
        while (h < HOURS && day[h] == day[start]) h++
        val mid = (start + h) / 2f
        txt(m, if (day[start]) "☀ day" else "☾ night", Offset(ax.x(mid), top - 13.dp.toPx()), if (day[start]) Color(0xFFB07800) else shade.nightInk, 9.5f, center = true)
    }
}

private fun DrawScope.hourAxis(ax: TimeAxis, state: UiState, zone: ZoneId, top: Float, bottom: Float, m: TextMeasurer, ink: Color, muted: Color, line: Color) {
    for (h in 0..HOURS) {
        val zt = Instant.ofEpochSecond(state.nowHourEpoch + h * 3600L).atZone(zone)
        if (zt.hour % 3 == 0) {
            drawLine(line, Offset(ax.x(h.toFloat()), top), Offset(ax.x(h.toFloat()), bottom), if (zt.hour == 0) 1.4.dp.toPx() else 0.6.dp.toPx())
            if (ax.labelFits(h, 12.dp.toPx())) txt(m, HOUR.format(zt).lowercase(Locale.US), Offset(ax.x(h.toFloat()), bottom + 3.dp.toPx()), muted, 10f, center = true)
        }
        if (zt.hour == 0) txt(m, DAY.format(zt), Offset(ax.x(h.toFloat()) + 3.dp.toPx(), 1.dp.toPx()), ink, 11f, bold = true)
    }
    // Name the first day too, unless a midnight label is right there.
    val first = Instant.ofEpochSecond(state.nowHourEpoch).atZone(zone)
    if (first.hour in 1..20) txt(m, DAY.format(first), Offset(ax.x(0f) + 3.dp.toPx(), 1.dp.toPx()), ink, 11f, bold = true)
}

/** A thin bar showing which part of the 48 hours is on screen. */
private fun DrawScope.scrollBar(ax: TimeAxis, scroll: Float, y: Float, color: Color) {
    val w = ax.right - ax.left
    val thumb = w * WINDOW / HOURS
    drawLine(color.copy(alpha = 0.15f), Offset(ax.left, y), Offset(ax.right, y), 3.dp.toPx(), StrokeCap.Round)
    val x0 = ax.left + (w - thumb) * scroll
    drawLine(color.copy(alpha = 0.55f), Offset(x0, y), Offset(x0 + thumb, y), 3.dp.toPx(), StrokeCap.Round)
}

private val HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("ha", Locale.US)
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
private val DAY_SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.US)

/** A small windsock arrow centred on [c], pointing toward [towardDeg] (0 = up/north). */
private fun DrawScope.arrowAt(c: Offset, towardDeg: Double, len: Float, color: Color, halo: Color) {
    val a = Math.toRadians(towardDeg)
    val dx = sin(a).toFloat(); val dy = -cos(a).toFloat()
    val start = Offset(c.x - dx * len / 2, c.y - dy * len / 2)
    val end = Offset(c.x + dx * len / 2, c.y + dy * len / 2)
    val head = len * 0.42f
    val bx = end.x - dx * head; val by = end.y - dy * head; val hw = head * 0.6f
    val tip = Path().apply { moveTo(end.x, end.y); lineTo(bx - dy * hw, by + dx * hw); lineTo(bx + dy * hw, by - dx * hw); close() }
    drawLine(halo, start, Offset(bx, by), 4f * density, StrokeCap.Round)
    drawPath(tip, halo, style = Stroke(2f * density))
    drawLine(color, start, Offset(bx, by), 1.7f * density, StrokeCap.Round)
    drawPath(tip, color)
}

/** Text at [at] (top-left, or centred / right-aligned), optionally on a small rounded [box] of that colour. */
private fun DrawScope.txt(
    m: TextMeasurer, s: String, at: Offset, color: Color, sizeSp: Float,
    bold: Boolean = false, center: Boolean = false, right: Boolean = false, box: Color? = null,
) {
    val layout = m.measure(s, TextStyle(fontSize = sizeSp.sp, color = color, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal))
    val x = when { center -> at.x - layout.size.width / 2f; right -> at.x - layout.size.width; else -> at.x }
    if (box != null) {
        drawRoundRect(box.copy(alpha = 0.88f), Offset(x - 2.dp.toPx(), at.y - 1.dp.toPx()), Size(layout.size.width + 4.dp.toPx(), layout.size.height + 2.dp.toPx()), CornerRadius(3.dp.toPx()))
    }
    drawText(layout, topLeft = Offset(x, at.y))
}
