package dev.windsaloft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
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
import dev.windsaloft.wind.Wind
import dev.windsaloft.wind.WindMath
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val HOURS = 48

/**
 * The next 48 hours for the pinned spot, on its own page. "Winds": one strip per altitude, stacked like
 * the map chart; the line's height is the speed and it's drawn with arrows showing the direction, each
 * strip labelled with its high and low, and shaded grey where the model has cloud at that altitude.
 * Tap a model chip (or swipe the chart) to switch models. "Clouds, all models": every model's cloud
 * forecast as a small time-by-altitude picture, one under another, for the quickest comparison.
 */
@Composable
fun TwoDayScreen(state: UiState, onDismiss: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onDismiss)
    val s = state.settings
    val models = OpenMeteo.MODELS.map { it.id }
    val loaded = models.filter { state.forecastFor(it) != null }
    var modelId by rememberSaveable { mutableStateOf(state.visibleModelIds.first()) }
    if (state.forecastFor(modelId) == null && loaded.isNotEmpty() && modelId !in state.moreLoading) modelId = loaded.first()
    var cloudsAll by rememberSaveable { mutableStateOf(false) }
    var cursor by rememberSaveable { mutableIntStateOf(state.hourOffset.coerceIn(0, HOURS)) }
    val zone = remember { ZoneId.systemDefault() }

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
                if (state.moreLoading.isNotEmpty()) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                SegmentedButton(!cloudsAll, { cloudsAll = false }, SegmentedButtonDefaults.itemShape(0, 2), icon = {}) { Text("Winds") }
                SegmentedButton(cloudsAll, { cloudsAll = true }, SegmentedButtonDefaults.itemShape(1, 2), icon = {}) { Text("Clouds, all models") }
            }
            Text(
                "Selected time: " + Format.time(state.nowHourEpoch + cursor * 3600L) + "  (+$cursor h) · tap the chart to move it",
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            if (!cloudsAll) {
                // Model chips: one tap to switch. Models that don't cover this spot are greyed out.
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    models.forEach { id ->
                        val has = state.forecastFor(id) != null
                        val na = id in state.unavailableModels
                        FilterChip(
                            selected = id == modelId, enabled = has,
                            onClick = { modelId = id },
                            label = { Text(OpenMeteo.short(id) + when { na -> " · n/a"; !has && id in state.moreLoading -> " …"; !has -> " · offline"; else -> "" }) },
                        )
                    }
                }
                val fp = state.forecastFor(modelId)
                if (fp == null) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (state.moreLoading.isNotEmpty()) "Loading forecasts…" else "No forecast for this model here.")
                        if (!state.online) Text("Offline: only models downloaded earlier are available.", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry) { Text("Try again") }
                    }
                } else {
                    WindStrips(
                        state, modelId, fp, cursor, zone, Modifier.weight(1f).fillMaxWidth(),
                        onCursor = { cursor = it },
                        onSwipe = { dir ->
                            val i = loaded.indexOf(modelId)
                            if (i >= 0 && loaded.isNotEmpty()) modelId = loaded[(i + dir + loaded.size) % loaded.size]
                        },
                    )
                }
            } else {
                AllModelClouds(state, loaded, cursor, zone, Modifier.weight(1f).fillMaxWidth(), onCursor = { cursor = it })
            }
        }
    }
}

/** Strip layout shared by both views. */
private class TimeAxis(val left: Float, val right: Float) {
    fun x(h: Float) = left + (right - left) * h / HOURS
    fun hourAt(px: Float) = (((px - left) / (right - left)) * HOURS).roundToInt().coerceIn(0, HOURS)
}

@Composable
private fun WindStrips(
    state: UiState, modelId: String, fp: ForecastPoint, cursor: Int, zone: ZoneId, modifier: Modifier,
    onCursor: (Int) -> Unit, onSwipe: (Int) -> Unit,
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
            val i = fp.hourIndex(state.nowHourEpoch + h * 3600L)
            val levels = fp.clouds.getOrNull(i).orEmpty()
            altitudes.map { ft ->
                val m = base + ft * M_PER_FT
                if (levels.isEmpty() || m < fp.elevationM) 0.0 else WindMath.cloudAt(levels, m) ?: 0.0
            }
        }
    }
    val maxKt = remember(winds) {
        winds.flatten().filterNotNull().maxOfOrNull { maxOf(it.speedMs, it.gustMs ?: 0.0) * KT_PER_MS }?.let { Math.ceil(it / 10) * 10 }?.coerceAtLeast(10.0) ?: 40.0
    }
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val night = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val cloudInk = Color(0xFF6E7C8C)
    val density = LocalDensity.current
    var swipe by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(modifier) {
        val stripH: Dp = maxOf(34.dp, (maxHeight - 48.dp) / rows.size.coerceAtLeast(1))
        val height = stripH * rows.size + 48.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                "Strip height = 0 to ${Format.speed(maxKt / KT_PER_MS, s.unit)} ${s.unit.label}. Numbers: each altitude's high and low. Grey: cloud at that altitude.",
                style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(horizontal = 16.dp),
            )
            Canvas(
                Modifier.fillMaxWidth().height(height)
                    .semantics { contentDescription = "Wind at each altitude over the next 48 hours, ${OpenMeteo.label(modelId)}" }
                    .pointerInput(Unit) { detectTapGestures { p -> onCursor(TimeAxis(44.dp.toPx(), size.width - 10.dp.toPx()).hourAt(p.x)) } }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { swipe = 0f },
                            onDragEnd = { if (abs(swipe) > 70.dp.toPx()) onSwipe(if (swipe < 0) 1 else -1); swipe = 0f },
                        ) { _, dx -> swipe += dx }
                    },
            ) {
                val ax = TimeAxis(44.dp.toPx(), size.width - 10.dp.toPx())
                val top = 22.dp.toPx()
                val sh = stripH.toPx()
                val bottom = top + sh * rows.size
                // Night and day/hour labels.
                for (h in 0 until HOURS) {
                    val t = state.nowHourEpoch + h * 3600L
                    if (state.pin != null && !Sun.isDay(state.pin.lat, state.pin.lon, t + 1800)) drawRect(night, Offset(ax.x(h.toFloat()), top), Size(ax.x(h + 1f) - ax.x(h.toFloat()) + 0.5f, bottom - top))
                }
                for (h in 0..HOURS) {
                    val zt = Instant.ofEpochSecond(state.nowHourEpoch + h * 3600L).atZone(zone)
                    if (zt.hour % 6 == 0) {
                        drawLine(line, Offset(ax.x(h.toFloat()), top), Offset(ax.x(h.toFloat()), bottom), if (zt.hour == 0) 1.2.dp.toPx() else 0.6.dp.toPx())
                        txt(measurer, HOUR.format(zt).lowercase(Locale.US), Offset(ax.x(h.toFloat()), bottom + 3.dp.toPx()), muted, 10f, center = true)
                    }
                    if (zt.hour == 0) txt(measurer, DAY.format(zt), Offset(ax.x(h.toFloat()) + 3.dp.toPx(), 3.dp.toPx()), ink, 11f, bold = true)
                }
                rows.forEachIndexed { k, ai ->
                    val yb = top + sh * (k + 1)
                    val yt = yb - sh
                    fun yv(kt: Double) = yb - 3.dp.toPx() - ((sh - 9.dp.toPx()) * (kt / maxKt).coerceAtMost(1.0)).toFloat()
                    // Cloud at this altitude: grey cells, darker for more cover.
                    for (h in 0 until HOURS) {
                        val pct = cloud[h][ai]
                        if (pct >= WindMath.CLOUD_LAYER_MIN_PCT) drawRect(cloudInk.copy(alpha = (0.12 + 0.4 * pct / 100).toFloat()), Offset(ax.x(h.toFloat()), yt), Size(ax.x(h + 1f) - ax.x(h.toFloat()) + 0.5f, sh))
                    }
                    drawLine(line, Offset(ax.left, yb), Offset(ax.right, yb), 1f)
                    val series = winds.map { it[ai] }
                    val label = if (series.any { it?.surface == true }) "SFC" else "${altitudes[ai] / 1000}k"
                    txt(measurer, label, Offset(ax.left - 6.dp.toPx(), yb - sh / 2 - 7.dp.toPx()), ink, 11f, bold = true, right = true)
                    // Speed area and line.
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
                    // Ground gusts as a dotted line, with their peak labelled.
                    if (series.any { it?.gustMs != null }) {
                        val g = Path(); var gs = false
                        series.forEachIndexed { h, w -> w?.gustMs?.let { v -> val px = ax.x(h.toFloat()); val py = yv(v * KT_PER_MS); if (gs) g.lineTo(px, py) else { g.moveTo(px, py); gs = true } } }
                        drawPath(g, accent, style = Stroke(1.3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))))
                    }
                    // Arrows along the line showing direction (downwind, like the map's windsocks).
                    val every = if (size.width > 900.dp.toPx()) 2 else 3
                    series.forEachIndexed { h, w -> if (w != null && h % every == 0) arrowAt(Offset(ax.x(h.toFloat()), yv(w.speedMs * KT_PER_MS)), w.dirFromDeg + 180, 12.dp.toPx(), ink, surface) }
                    // High and low for this altitude over the 48 hours.
                    val present = series.withIndex().filter { it.value != null }
                    if (present.isNotEmpty()) {
                        val hi = present.maxBy { it.value!!.speedMs }
                        val lo = present.minBy { it.value!!.speedMs }
                        val hiTxt = Format.speed(hi.value!!.speedMs, s.unit)
                        // Keep labels inside the plot so they never sit on the altitude labels.
                        fun lx(h: Int) = ax.x(h.toFloat()).coerceIn(ax.left + 9.dp.toPx(), ax.right - 9.dp.toPx())
                        txt(measurer, hiTxt, Offset(lx(hi.index), yv(hi.value!!.speedMs * KT_PER_MS) - 14.dp.toPx()), ink, 10.5f, bold = true, center = true, halo = surface, box = true)
                        if (abs(hi.index - lo.index) >= 4 && hi.value!!.speedMs - lo.value!!.speedMs > 1.0) {
                            txt(measurer, Format.speed(lo.value!!.speedMs, s.unit), Offset(lx(lo.index), yv(lo.value!!.speedMs * KT_PER_MS) + 3.dp.toPx()), muted, 10f, center = true, halo = surface, box = true)
                        }
                        series.withIndex().filter { it.value?.gustMs != null }.maxByOrNull { it.value!!.gustMs!! }?.let { gp ->
                            txt(measurer, "(${Format.speed(gp.value!!.gustMs!!, s.unit)})", Offset(ax.x(gp.index.toFloat()) + 8.dp.toPx(), yv(gp.value!!.gustMs!! * KT_PER_MS) - 13.dp.toPx()), ink, 10f, halo = surface, box = true)
                        }
                    }
                }
                // Cursor with the values at that time on every strip.
                val cx = ax.x(cursor.toFloat())
                drawLine(ink.copy(alpha = 0.7f), Offset(cx, top), Offset(cx, bottom), 1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                val onRight = cursor < HOURS * 0.75
                rows.forEachIndexed { k, ai ->
                    val w = winds[cursor][ai] ?: return@forEachIndexed
                    val yb = top + sh * (k + 1)
                    val v = "${Format.direction(w.dirFromDeg)} ${Format.speed(w.speedMs, s.unit)}" + (w.gustMs?.let { " (${Format.speed(it, s.unit)})" } ?: "")
                    txt(measurer, v, Offset(cx + if (onRight) 5.dp.toPx() else -5.dp.toPx(), yb - sh / 2 - 7.dp.toPx()), ink, 10.5f, bold = true, right = !onRight, halo = surface, box = true)
                }
            }
        }
    }
}

@Composable
private fun AllModelClouds(state: UiState, models: List<String>, cursor: Int, zone: ZoneId, modifier: Modifier, onCursor: (Int) -> Unit) {
    val s = state.settings
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val line = MaterialTheme.colorScheme.outlineVariant
    val night = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val cloudInk = Color(0xFF55657A)
    val ceiling = s.ceilingFt
    val step = 500
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Text(
            "Each picture is one model: time across, altitude up (0 to ${Format.feet(ceiling)} ft ${s.altitudeRef.name}), darker grey for more cloud. Tap to move the selected time.",
            style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (models.isEmpty()) Text(if (state.moreLoading.isNotEmpty()) "Loading forecasts…" else "No forecasts available.", Modifier.padding(16.dp))
        models.forEach { id ->
            val fp = state.forecastFor(id) ?: return@forEach
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
                    when { noData -> "no cloud data"; layers.isNullOrEmpty() -> "clear at selected time"; else -> Format.clouds(layers, short = true) },
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Canvas(
                Modifier.fillMaxWidth().height(86.dp).pointerInput(Unit) {
                    detectTapGestures { p -> onCursor(TimeAxis(44.dp.toPx(), size.width - 10.dp.toPx()).hourAt(p.x)) }
                },
            ) {
                val ax = TimeAxis(44.dp.toPx(), size.width - 10.dp.toPx())
                val top = 4.dp.toPx(); val bottom = size.height - 14.dp.toPx()
                fun y(ft: Float) = bottom - (bottom - top) * ft / ceiling
                for (h in 0 until HOURS) {
                    val t = state.nowHourEpoch + h * 3600L
                    if (state.pin != null && !Sun.isDay(state.pin.lat, state.pin.lon, t + 1800)) drawRect(night, Offset(ax.x(h.toFloat()), top), Size(ax.x(h + 1f) - ax.x(h.toFloat()) + 0.5f, bottom - top))
                    grid[h].forEachIndexed { k, pct ->
                        if (pct >= WindMath.CLOUD_LAYER_MIN_PCT) {
                            drawRect(
                                cloudInk.copy(alpha = (0.18 + 0.62 * pct / 100).toFloat().coerceAtMost(0.85f)),
                                Offset(ax.x(h.toFloat()), y((k + 0.5f) * step)), Size(ax.x(h + 1f) - ax.x(h.toFloat()) + 0.5f, y(0f) - y(step.toFloat()) + 0.5f),
                            )
                        }
                    }
                }
                drawRect(line, Offset(ax.left, top), Size(ax.right - ax.left, bottom - top), style = Stroke(1f))
                listOf(0, ceiling / 3 / 1000 * 1000, ceiling * 2 / 3 / 1000 * 1000, ceiling).forEach { ft ->
                    drawLine(line, Offset(ax.left, y(ft.toFloat())), Offset(ax.right, y(ft.toFloat())), 0.6f)
                    txt(measurer, if (ft == 0) "0" else "${ft / 1000}k", Offset(ax.left - 5.dp.toPx(), y(ft.toFloat()) - 6.dp.toPx()), muted, 9.5f, right = true)
                }
                for (h in 0..HOURS) {
                    val zt = Instant.ofEpochSecond(state.nowHourEpoch + h * 3600L).atZone(zone)
                    if (zt.hour % 12 == 0) txt(measurer, if (zt.hour == 0) DAY_SHORT.format(zt) else HOUR.format(zt).lowercase(Locale.US), Offset(ax.x(h.toFloat()), bottom + 1.dp.toPx()), muted, 9.5f, center = true)
                }
                val cx = ax.x(cursor.toFloat())
                drawLine(ink.copy(alpha = 0.7f), Offset(cx, top), Offset(cx, bottom), 1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                if (noData) txt(measurer, "This model gives no cloud levels", Offset((ax.left + ax.right) / 2, (top + bottom) / 2 - 7.dp.toPx()), muted, 11f, center = true)
            }
        }
        val missing = OpenMeteo.MODELS.map { it.id }.filter { it !in models }
        if (missing.isNotEmpty()) {
            Text(
                "Not shown: " + missing.joinToString(", ") { OpenMeteo.short(it) + if (it in state.unavailableModels) " (doesn't cover this spot)" else if (it in state.moreLoading) " (loading)" else " (not downloaded)" },
                style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(16.dp),
            )
        }
    }
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

private fun DrawScope.txt(
    m: TextMeasurer, s: String, at: Offset, color: Color, sizeSp: Float,
    bold: Boolean = false, center: Boolean = false, right: Boolean = false, halo: Color? = null, box: Boolean = false,
) {
    val style = TextStyle(fontSize = sizeSp.sp, color = color, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    val layout = m.measure(s, style)
    val x = when { center -> at.x - layout.size.width / 2f; right -> at.x - layout.size.width; else -> at.x }
    if (box && halo != null) {
        drawRoundRect(halo.copy(alpha = 0.85f), Offset(x - 2.dp.toPx(), at.y - 1.dp.toPx()), Size(layout.size.width + 4.dp.toPx(), layout.size.height + 2.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
    } else if (halo != null) {
        val h = TextStyle(fontSize = sizeSp.sp, color = halo, fontWeight = style.fontWeight, drawStyle = Stroke(3.dp.toPx()))
        drawText(m.measure(s, h), topLeft = Offset(x, at.y))
    }
    drawText(layout, topLeft = Offset(x, at.y))
}
