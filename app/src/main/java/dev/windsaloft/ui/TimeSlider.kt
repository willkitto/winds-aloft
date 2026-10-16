package dev.windsaloft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windsaloft.UiState
import dev.windsaloft.wind.AltitudeRef
import dev.windsaloft.wind.KT_PER_MS
import dev.windsaloft.wind.Sun
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** What the slider's track shows for each hour: night, ground wind and gust (knots), cloud cover (0–1). */
private data class HourStrip(val day: Boolean, val windKt: Double?, val gustKt: Double?, val cloud: Double)

/**
 * The forecast-time slider with a preview of what's coming drawn on the track: night shaded, a tiny
 * graph of the ground wind (dotted line: gusts), and a strip that darkens with cloud cover. Tap or
 * drag anywhere on it.
 */
@Composable
fun ForecastTimeSlider(state: UiState, onHourOffset: (Int) -> Unit, modifier: Modifier = Modifier) {
    val maxH = state.maxHourOffset.coerceAtLeast(1)
    val model = state.visibleModelIds.firstOrNull()
    val fp = model?.let { state.pinForecasts[it] }
    val s = state.settings
    val strip = remember(fp, state.nowHourEpoch, maxH, s.ceilingFt, state.pin) {
        (0..maxH).map { h ->
            val t = state.nowHourEpoch + h * 3600L
            val day = state.pin?.let { Sun.isDay(it.lat, it.lon, t + 1800) } ?: true
            val g = fp?.windsAt(t, listOf(0), AltitudeRef.AGL)?.firstOrNull()
            val cloud = fp?.cloudLayers(t, AltitudeRef.AGL, 0, s.ceilingFt)?.maxOfOrNull { it.maxPct }?.div(100.0) ?: 0.0
            HourStrip(day, g?.speedMs?.times(KT_PER_MS), g?.gustMs?.times(KT_PER_MS), cloud)
        }
    }
    val zone = remember { ZoneId.systemDefault() }
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val night = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val thumbRing = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    val pad = 12.dp
    fun hourAt(x: Float, width: Float, padPx: Float) = (((x - padPx) / (width - 2 * padPx)) * maxH).roundToInt().coerceIn(0, maxH)
    Box(
        modifier.fillMaxWidth().height(58.dp)
            .semantics {
                contentDescription = "Forecast time"
                stateDescription = "${Format.time(state.selectedEpoch)}, ${state.hourOffset} hours ahead"
                progressBarRangeInfo = ProgressBarRangeInfo(state.hourOffset.toFloat(), 0f..maxH.toFloat(), maxH - 1)
                setProgress { v -> onHourOffset(v.roundToInt()); true }
            }
            .pointerInput(maxH) { detectTapGestures { onHourOffset(hourAt(it.x, size.width.toFloat(), pad.toPx())) } }
            .pointerInput(maxH) { detectHorizontalDragGestures { change, _ -> onHourOffset(hourAt(change.position.x, size.width.toFloat(), pad.toPx())) } },
    ) {
        Canvas(Modifier.fillMaxWidth().height(58.dp)) {
            val p = pad.toPx()
            val w = size.width - 2 * p
            fun x(h: Float) = p + w * h / maxH
            val windTop = 13.dp.toPx(); val windBottom = 31.dp.toPx()
            val cloudTop = 34.dp.toPx(); val cloudH = 6.dp.toPx()
            val trackY = 49.dp.toPx()
            // Night bands behind the indicators.
            strip.forEachIndexed { h, it ->
                if (!it.day && h < maxH) drawRect(night, Offset(x(h.toFloat()), windTop), Size(x(h + 1f) - x(h.toFloat()) + 0.5f, cloudTop + cloudH - windTop))
            }
            // Local midnight ticks.
            for (h in 0..maxH) {
                val lh = Instant.ofEpochSecond(state.nowHourEpoch + h * 3600L).atZone(zone).hour
                if (lh == 0) drawLine(ink.copy(alpha = 0.35f), Offset(x(h.toFloat()), windTop), Offset(x(h.toFloat()), cloudTop + cloudH), 1.dp.toPx())
            }
            // Ground wind: area for sustained, dotted line for gusts, on a 0–max scale.
            val maxKt = (strip.maxOfOrNull { maxOf(it.windKt ?: 0.0, it.gustKt ?: 0.0) } ?: 10.0).coerceAtLeast(10.0)
            fun y(kt: Double) = windBottom - ((windBottom - windTop) * (kt / maxKt)).toFloat()
            if (strip.any { it.windKt != null }) {
                val area = Path().apply {
                    moveTo(x(0f), windBottom)
                    strip.forEachIndexed { h, it -> lineTo(x(h.toFloat()), y(it.windKt ?: 0.0)) }
                    lineTo(x(maxH.toFloat()), windBottom); close()
                }
                drawPath(area, accent.copy(alpha = 0.22f))
                val gust = Path(); var started = false
                strip.forEachIndexed { h, it -> it.gustKt?.let { g -> if (started) gust.lineTo(x(h.toFloat()), y(g)) else { gust.moveTo(x(h.toFloat()), y(g)); started = true } } }
                drawPath(gust, accent, style = Stroke(1.3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5.dp.toPx(), 2.5.dp.toPx()))))
            }
            // Cloud: one cell per hour, darker for more cover.
            drawRoundRect(ink.copy(alpha = 0.08f), Offset(p, cloudTop), Size(w, cloudH), CornerRadius(2.dp.toPx()))
            strip.forEachIndexed { h, it ->
                if (it.cloud > 0.05 && h < maxH) drawRect(Color(0xFF5B6B7C).copy(alpha = (0.15 + 0.75 * it.cloud).toFloat().coerceAtMost(0.9f)), Offset(x(h.toFloat()), cloudTop), Size(x(h + 1f) - x(h.toFloat()) + 0.5f, cloudH))
            }
            label(measurer, "ground wind 0–${Format.speed(maxKt / KT_PER_MS, s.unit)} ${s.unit.label} · cloud · night", Offset(p, 0f), ink)
            // Track and thumb.
            drawLine(track, Offset(p, trackY), Offset(p + w, trackY), 4.dp.toPx(), StrokeCap.Round)
            val tx = x(state.hourOffset.toFloat())
            drawLine(accent, Offset(p, trackY), Offset(tx, trackY), 4.dp.toPx(), StrokeCap.Round)
            drawLine(accent.copy(alpha = 0.5f), Offset(tx, windTop), Offset(tx, cloudTop + cloudH), 1.dp.toPx())
            drawCircle(thumbRing, 10.dp.toPx(), Offset(tx, trackY))
            drawCircle(accent, 8.dp.toPx(), Offset(tx, trackY))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.label(m: TextMeasurer, s: String, at: Offset, color: Color) {
    drawText(m, s, at, TextStyle(fontSize = 9.sp, color = color.copy(alpha = 0.8f)))
}
