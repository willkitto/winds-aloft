package dev.windsaloft.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.Button
import androidx.compose.ui.graphics.toArgb
import dev.windsaloft.map.WindColors
import dev.windsaloft.data.Glyph
import dev.windsaloft.data.ArrowAnchor
import dev.windsaloft.wind.CloudLayer
import dev.windsaloft.data.TempUnit
import dev.windsaloft.data.ArrowColor
import dev.windsaloft.wind.WindMath
import dev.windsaloft.map.ModelColors
import dev.windsaloft.data.ArrowLayout
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windsaloft.UiState
import dev.windsaloft.data.MapLayer
import dev.windsaloft.data.OpenMeteo
import dev.windsaloft.data.Place
import dev.windsaloft.data.Settings
import dev.windsaloft.data.SpeedUnit
import dev.windsaloft.map.AltitudeColors
import dev.windsaloft.map.WindOverlay
import dev.windsaloft.wind.AltitudeRef
import dev.windsaloft.wind.KT_PER_MS
import dev.windsaloft.wind.Wind
import kotlin.math.roundToInt

fun altitudeColor(ft: Int, ceilingFt: Int) = Color(AltitudeColors.argb(ft, ceilingFt))

private val PanelShape = RoundedCornerShape(20.dp)

@Composable
private fun panelColors() = CardDefaults.cardColors(
    // contentColor must be explicit: a translucent container isn't a theme colour, so Material can't
    // pick a matching text colour and would fall back to black (invisible in dark mode).
    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    contentColor = MaterialTheme.colorScheme.onSurface,
)

// ---------------------------------------------------------------- Search bar

@Composable
fun SearchBar(
    results: List<Place>,
    onSearch: (String) -> Unit,
    onPick: (Place) -> Unit,
    onClearResults: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Column(modifier) {
        Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 6.dp, tonalElevation = 2.dp) {
            Row(Modifier.height(52.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    if (query.isEmpty()) {
                        Text("Search place or lat, lon", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch(query); focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; onClearResults() }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
            }
        }
        if (results.isNotEmpty()) {
            Card(Modifier.padding(top = 8.dp).fillMaxWidth(), shape = PanelShape, colors = panelColors()) {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    results.forEach { place ->
                        ListItem(
                            headlineContent = { Text(place.name) },
                            supportingContent = { if (place.detail.isNotEmpty()) Text(place.detail) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { query = place.name; onPick(place) },
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Time + altitude controls

/** Forecast time label + slider; shared by the full controls and the time-only card. */
@Composable
private fun TimeControl(state: UiState, onHourOffset: (Int) -> Unit) {
    LabelRow("Forecast", "${Format.time(state.selectedEpoch)}  (+${state.hourOffset} h)")
    Slider(
        value = state.hourOffset.toFloat(),
        onValueChange = { onHourOffset(it.roundToInt()) },
        valueRange = 0f..state.maxHourOffset.coerceAtLeast(1).toFloat(),
        modifier = Modifier.height(36.dp),
    )
}

/** Only the forecast-time slider, for stepping through hours with the map otherwise clear. */
@Composable
fun TimeOnlyCard(state: UiState, onHourOffset: (Int) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier, shape = PanelShape, colors = panelColors(), elevation = CardDefaults.cardElevation(6.dp)) {
        if (state.pinLoading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp)) { TimeControl(state, onHourOffset) }
    }
}

/** Asks before moving the forecast spot to a long-pressed location. */
@Composable
fun ConfirmSpotCard(location: dev.windsaloft.wind.LatLon, onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier, shape = PanelShape, colors = panelColors(), elevation = CardDefaults.cardElevation(6.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Get winds here?", style = MaterialTheme.typography.titleSmall)
                Text(Format.coords(location), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = onConfirm) { Text("Get winds") }
        }
    }
}

@Composable
fun ControlsCard(
    state: UiState,
    attribution: String,
    onHourOffset: (Int) -> Unit,
    onAltRange: (IntRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = state.settings
    val ceilK = s.ceilingFt / 1000
    Card(modifier, shape = PanelShape, colors = panelColors(), elevation = CardDefaults.cardElevation(6.dp)) {
        if (state.pinLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        } else {
            Spacer(Modifier.height(2.dp))
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp)) {
            TimeControl(state, onHourOffset)
            val lo = state.altRangeK.first * 1000
            val hi = state.altRangeK.last * 1000
            LabelRow("Altitude", "${Format.feet(lo)} – ${Format.feet(hi)} ft ${s.altitudeRef.name}")
            RangeSlider(
                value = state.altRangeK.first.toFloat()..state.altRangeK.last.toFloat(),
                onValueChange = { r -> onAltRange(r.start.roundToInt()..r.endInclusive.roundToInt()) },
                valueRange = 0f..ceilK.toFloat(),
                modifier = Modifier.height(36.dp),
            )
            when {
                s.arrowColor == ArrowColor.TEMPERATURE -> ColorScale(
                    stops = (0..32).map { Color(AltitudeColors.temperature(-40.0 + 80.0 * it / 32)) },
                    labels = listOf(-40.0, -20.0, 0.0, 20.0, 40.0).map { s.tempUnit.format(it) },
                )
                s.arrowColor == ArrowColor.SPEED -> ColorScale(
                    stops = (0..20).map { Color(AltitudeColors.speed(50.0 * it / 20)) },
                    labels = listOf(0.0, 25.0, 50.0).map { Format.speed(it / KT_PER_MS, s.unit) + if (it == 50.0) "+ ${s.unit.label}" else "" },
                )
                s.comparing -> ModelLegend(s.modelIds)
                else -> AltitudeScale(s.ceilingFt, state.altRangeK)
            }
            if (s.comparing && s.arrowColor != ArrowColor.AUTO) ModelLegend(s.modelIds)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SpeedLegend(s.unit, s.arrowLayout)
                Spacer(Modifier.width(12.dp))
                Text(
                    "$attribution · Weather: Open-Meteo.com",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Colour key for compared models (replaces the altitude colour bar in comparison mode). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelLegend(modelIds: List<String>) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        modelIds.forEachIndexed { i, id ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = Color(ModelColors.argb(i))
                Canvas(Modifier.width(18.dp).height(10.dp)) {
                    drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    OpenMeteo.short(id),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun LabelRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Colour ramp matching the arrow colours, with the selected band highlighted. */
/** A colour ramp with evenly spaced labels underneath. */
@Composable
private fun ColorScale(stops: List<Color>, labels: List<String>) {
    Column(Modifier.padding(horizontal = 10.dp)) {
        Canvas(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) { drawRect(Brush.horizontalGradient(stops)) }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (i < labels.lastIndex) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AltitudeScale(ceilingFt: Int, selectedK: IntRange) {
    val ceilK = ceilingFt / 1000
    val stops = remember(ceilingFt) { (0..20).map { altitudeColor(ceilingFt * it / 20, ceilingFt) } }
    val dim = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
    Column(Modifier.padding(horizontal = 10.dp)) {
        Canvas(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
            drawRect(Brush.horizontalGradient(stops))
            val x0 = size.width * selectedK.first / ceilK
            val x1 = size.width * selectedK.last / ceilK
            drawRect(dim, topLeft = Offset.Zero, size = size.copy(width = x0))
            drawRect(dim, topLeft = Offset(x1, 0f), size = size.copy(width = size.width - x1))
        }
        Row(Modifier.fillMaxWidth()) {
            val labels = listOf(0, ceilK / 3, 2 * ceilK / 3, ceilK)
            labels.forEachIndexed { i, k ->
                Text(
                    if (k == 0) "0" else "${k}k",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (i < labels.lastIndex) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Arrow scale key: a reference-length arrow (fan), or arrow sizes at a few speeds (stacked). */
@Composable
private fun SpeedLegend(unit: SpeedUnit, layout: ArrowLayout) {
    val color = MaterialTheme.colorScheme.onSurface
    val style = MaterialTheme.typography.labelSmall
    fun inUnit(kt: Double) = (kt / KT_PER_MS * unit.perMs).roundToInt()
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (layout == ArrowLayout.STACKED) {
            val cell = 18.dp
            listOf(5.0, 20.0, WindOverlay.STACK_FULL_KT).forEach { kt ->
                val frac = WindOverlay.stackedFraction(kt)
                LegendArrow(color, cell * WindOverlay.STACK_LENGTH * frac, Modifier.width(cell * WindOverlay.STACK_LENGTH).height(10.dp))
                Text("${inUnit(kt)}${if (kt >= WindOverlay.STACK_FULL_KT) "+" else ""}", style = style)
                Spacer(Modifier.width(6.dp))
            }
            Text(unit.label, style = style, fontWeight = FontWeight.SemiBold)
        } else {
            val refKt = 20.0
            LegendArrow(color, WindOverlay.arrowLengthDp(refKt).dp, Modifier.width(WindOverlay.arrowLengthDp(refKt).dp).height(10.dp))
            Spacer(Modifier.width(6.dp))
            Text("${inUnit(refKt)} ${unit.label}", style = style, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun LegendArrow(color: Color, length: Dp, modifier: Modifier) {
    Canvas(modifier) {
        val y = size.height / 2
        val len = length.toPx().coerceAtMost(size.width)
        val head = (len * 0.4f).coerceAtMost(6.dp.toPx())
        val x0 = (size.width - len) / 2
        val x1 = x0 + len
        drawLine(color, Offset(x0, y), Offset(x1 - head, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        val p = Path().apply { moveTo(x1, y); lineTo(x1 - head, y - head * 0.6f); lineTo(x1 - head, y + head * 0.6f); close() }
        drawPath(p, color)
    }
}

// ---------------------------------------------------------------- Pinned location profile

/** One model's winds at the pinned location, one entry per selected altitude (low → high). */
data class ModelProfile(
    val modelId: String,
    val color: Color,
    val winds: List<Pair<Int, Wind?>>,
    /** Cloud layers from the surface to the ceiling (null = the model has no cloud data). */
    val clouds: List<CloudLayer>? = null,
)

@Composable
fun PinPanel(
    state: UiState,
    profiles: List<ModelProfile>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    /** Shown when the chart has been dragged away from the forecast spot. */
    onReattach: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val s = state.settings
    val pin = state.pin ?: return
    val elevation = state.pinForecasts.values.firstOrNull()?.elevationM
    Card(modifier, shape = PanelShape, colors = panelColors(), elevation = CardDefaults.cardElevation(6.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    state.pinLabel ?: Format.coords(pin),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = buildList {
                    if (elevation != null) add("Elev ${Format.elevation(elevation)}")
                    add(if (s.comparing) "${s.modelIds.size} models" else OpenMeteo.label(s.primaryModelId))
                    if (state.pinFetchedAtMs > 0) add(Format.age(state.pinFetchedAtMs))
                }.joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (s.showClouds && profiles.isNotEmpty()) {
                    // Exact layer bases and tops, readable even with the table closed.
                    // One line per model when comparing (so a long list can't push the next model out of view).
                    profiles.forEach { p ->
                        Text(
                            (if (s.comparing) OpenMeteo.short(p.modelId) + ": " else "☁ ") + Format.clouds(p.clouds, short = s.comparing),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = if (s.comparing) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (state.pinLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = if (expanded) "Hide profile" else "Show profile",
                modifier = Modifier.padding(start = 8.dp),
            )
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Remove pin") }
        }
        if (onReattach != null) {
            TextButton(onClick = onReattach, modifier = Modifier.padding(start = 6.dp)) { Text("Put the chart back on the forecast spot") }
        }
        if (!expanded) return@Card
        HorizontalDivider()
        if (state.pinForecasts.isEmpty()) {
            Text(
                if (state.pinLoading) "Loading forecast…" else "No forecast loaded",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Card
        }
        if (s.comparing) {
            CompareHeader(profiles, s.unit)
            val rows = profiles.first().winds.indices.reversed().toList()
            LazyColumn(Modifier.heightIn(max = 200.dp)) {
                items(rows, key = { it }) { i -> CompareRow(profiles, i, s) }
            }
        } else {
            ProfileHeader(s.unit)
            LazyColumn(Modifier.heightIn(max = 200.dp)) {
                items(profiles.first().winds.asReversed(), key = { it.first }) { (ft, w) -> ProfileRow(ft, w, s) }
            }
        }
    }
}

private val ColAlt = 92.dp
private val ColDir = 84.dp
private val ColSpd = 64.dp

@Composable
private fun ProfileHeader(unit: SpeedUnit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        val style = MaterialTheme.typography.labelMedium
        val c = MaterialTheme.colorScheme.onSurfaceVariant
        Text("Altitude", Modifier.width(ColAlt + 18.dp), style = style, color = c)
        Text("From", Modifier.width(ColDir), style = style, color = c)
        Text("Speed", Modifier.width(ColSpd), style = style, color = c)
        Text("Temp", style = style, color = c)
        Spacer(Modifier.weight(1f))
        Text(unit.label, style = style, color = c)
    }
}

@Composable
private fun altitudeText(ft: Int, s: Settings) = Format.feet(ft) + if (ft == 0 && s.altitudeRef == AltitudeRef.AGL) " sfc" else ""

@Composable
private fun ProfileRow(ft: Int, w: Wind?, s: Settings) {
    // Same colour as the symbol on the map, whatever the colour setting.
    val rowColor = w?.let { Color(WindColors.of(s.arrowColor, it, null, s.ceilingFt)) } ?: altitudeColor(ft, s.ceilingFt)
    val num = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawCircle(rowColor) }
        Spacer(Modifier.width(8.dp))
        Text(altitudeText(ft, s), Modifier.width(ColAlt), style = num, maxLines = 1)
        if (w == null) {
            Text(
                if (s.altitudeRef == AltitudeRef.MSL) "below ground / no data" else "no data",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Row
        }
        Row(Modifier.width(ColDir), verticalAlignment = Alignment.CenterVertically) {
            WindGlyph(w.dirFromDeg, rowColor)
            Spacer(Modifier.width(6.dp))
            Text(Format.direction(w.dirFromDeg), style = num)
        }
        Text(Format.speed(w.speedMs, s.unit), Modifier.width(ColSpd), style = num, fontWeight = FontWeight.SemiBold)
        Text(w.tempC?.let { s.tempUnit.format(it) } ?: "", style = num)
    }
}

private val ColAltCompare = 50.dp
private val ColSpread = 32.dp

@Composable
private fun CompareHeader(profiles: List<ModelProfile>, unit: SpeedUnit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val style = MaterialTheme.typography.labelMedium
        val c = MaterialTheme.colorScheme.onSurfaceVariant
        Text("Alt", Modifier.width(ColAltCompare), style = style, color = c)
        profiles.forEach { p ->
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(9.dp)) { drawCircle(p.color) }
                Spacer(Modifier.width(4.dp))
                Text(OpenMeteo.short(p.modelId), style = style, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        Text("Δ ${unit.label}", Modifier.width(ColSpread), style = style, color = c, maxLines = 1)
    }
}

/** One altitude: each model's direction/speed side by side, plus the largest vector disagreement between them. */
@Composable
private fun CompareRow(profiles: List<ModelProfile>, index: Int, s: Settings) {
    val num = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum", fontSize = 11.sp)
    val ft = profiles.first().winds[index].first
    val winds = profiles.map { it.winds.getOrNull(index)?.second }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Format.feet(ft), Modifier.width(ColAltCompare), style = num, maxLines = 1)
        profiles.forEachIndexed { i, p ->
            val w = winds[i]
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (w == null) {
                    Text("—", style = num, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    WindGlyph(w.dirFromDeg, Color(WindColors.of(s.arrowColor, w, p.color.toArgb(), s.ceilingFt)), size = 12.dp)
                    Spacer(Modifier.width(2.dp))
                    // Aviation-style "direction/speed", then temperature.
                    Text(
                        "%03d/%s".format(Math.round(w.dirFromDeg) % 360, Format.speed(w.speedMs, s.unit)),
                        style = num, maxLines = 1,
                    )
                    if (w.tempC != null) {
                        Spacer(Modifier.width(3.dp))
                        Text(
                            s.tempUnit.format(w.tempC),
                            style = num,
                            color = if (w.tempC < 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        val spreadMs = WindMath.maxVectorDifference(winds.filterNotNull())
        val big = spreadMs != null && spreadMs * KT_PER_MS >= 10.0
        Text(
            spreadMs?.let { Format.speed(it, s.unit) } ?: "",
            Modifier.width(ColSpread),
            style = num,
            fontWeight = if (big) FontWeight.Bold else FontWeight.Normal,
            color = if (big) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Compass needle (red end = north on screen); tap to turn the map back to north-up. */
@Composable
fun CompassButton(rotation: Float, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SmallFloatingActionButton(onClick = onClick, modifier = modifier.semantics { contentDescription = "North up" }) {
        val south = MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(Modifier.size(26.dp)) {
            // The map is rotated clockwise by [rotation], so north sits at that bearing on screen.
            rotate(rotation) {
                val cx = size.width / 2
                val cy = size.height / 2
                val half = size.height * 0.46f
                val w = size.width * 0.2f
                drawPath(Path().apply { moveTo(cx, cy - half); lineTo(cx - w, cy); lineTo(cx + w, cy); close() }, Color(0xFFE53935))
                drawPath(Path().apply { moveTo(cx, cy + half); lineTo(cx - w, cy); lineTo(cx + w, cy); close() }, south)
            }
        }
    }
}

/** Small arrow pointing downwind, matching the map arrows. */
@Composable
fun WindGlyph(dirFromDeg: Double, color: Color, size: Dp = 18.dp) {
    val outline = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(size)) {
        rotate((dirFromDeg + 180).toFloat()) {
            val cx = this.size.width / 2
            val top = 1.dp.toPx()
            val bottom = this.size.height - 1.dp.toPx()
            val head = this.size.height * 0.33f
            drawLine(outline, Offset(cx, bottom), Offset(cx, top + head), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            drawLine(color, Offset(cx, bottom), Offset(cx, top + head), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            val p = Path().apply { moveTo(cx, top); lineTo(cx - head * 0.6f, top + head); lineTo(cx + head * 0.6f, top + head); close() }
            drawPath(p, color)
        }
    }
}

// ---------------------------------------------------------------- Settings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(settings: Settings, onChange: (Settings) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Section("Wind speed units")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SpeedUnit.entries.forEach { u ->
                        FilterChip(settings.unit == u, { onChange(settings.copy(unit = u)) }, label = { Text(u.label) })
                    }
                }
                Section("Altitude reference")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(settings.altitudeRef == AltitudeRef.MSL, { onChange(settings.copy(altitudeRef = AltitudeRef.MSL)) },
                        label = { Text("MSL (sea level)") })
                    FilterChip(settings.altitudeRef == AltitudeRef.AGL, { onChange(settings.copy(altitudeRef = AltitudeRef.AGL)) },
                        label = { Text("AGL (ground)") })
                }
                Section("Altitude ceiling")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Settings.CEILINGS.forEach { c ->
                        FilterChip(settings.ceilingFt == c, { onChange(settings.copy(ceilingFt = c)) },
                            label = { Text("${Format.feet(c)} ft") })
                    }
                }
                Section("Map style")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapLayer.entries.forEach { l ->
                        FilterChip(settings.layer == l, { onChange(settings.copy(layer = l)) }, label = { Text(l.label) })
                    }
                }
                Section("Wind symbol")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Glyph.entries.forEach { g ->
                        // Picking a symbol also applies its natural anchor (windsock, tetrahedron, chart barb).
                        FilterChip(settings.glyph == g, { onChange(settings.copy(glyph = g, arrowAnchor = g.defaultAnchor)) },
                            label = { Text(g.label) })
                    }
                }
                Section("On the column line")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArrowAnchor.entries.forEach { a ->
                        FilterChip(settings.arrowAnchor == a, { onChange(settings.copy(arrowAnchor = a)) }, label = { Text(a.label) })
                    }
                }
                Text(
                    when (settings.glyph) {
                        Glyph.ARROW -> "Arrows point downwind. Tail on the line works like a windsock."
                        Glyph.TRIANGLE -> "Triangles point into the wind. Nose on the line works like a landing tetrahedron."
                        Glyph.BARB -> "Barbs point into the wind, feathers at the far end. Tail (station) on the line is the weather-chart convention."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Section("Colour")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArrowColor.entries.forEach { c ->
                        FilterChip(settings.arrowColor == c, { onChange(settings.copy(arrowColor = c)) }, label = { Text(c.label) })
                    }
                }
                Section("Numbers on the map")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(settings.showSpeed, { onChange(settings.copy(showSpeed = !settings.showSpeed)) }, label = { Text("Speed") })
                    FilterChip(settings.showDirection, { onChange(settings.copy(showDirection = !settings.showDirection)) },
                        label = { Text("Direction (from)") })
                    FilterChip(settings.showTemperature, { onChange(settings.copy(showTemperature = !settings.showTemperature)) },
                        label = { Text("Temperature") })
                }
                Section("Temperature unit")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TempUnit.entries.forEach { u ->
                        FilterChip(settings.tempUnit == u, { onChange(settings.copy(tempUnit = u)) }, label = { Text(u.label) })
                    }
                }
                Section("Cloud layers")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(settings.showClouds, { onChange(settings.copy(showClouds = !settings.showClouds)) },
                        label = { Text("Show cloud bases & tops") })
                }
                Section("Layout")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArrowLayout.entries.forEach { l ->
                        FilterChip(settings.arrowLayout == l, { onChange(settings.copy(arrowLayout = l)) }, label = { Text(l.label) })
                    }
                }
                Section("Forecast models (pick up to ${Settings.MAX_MODELS} to compare)")
                OpenMeteo.MODELS.forEach { m ->
                    val slot = settings.modelIds.indexOf(m.id)
                    val selected = slot >= 0
                    val enabled = selected || settings.modelIds.size < Settings.MAX_MODELS
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(
                                value = selected,
                                enabled = enabled && !(selected && settings.modelIds.size == 1),
                                onValueChange = { on ->
                                    onChange(settings.copy(modelIds = if (on) settings.modelIds + m.id else settings.modelIds - m.id))
                                },
                            )
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            m.label,
                            Modifier.weight(1f),
                            color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (selected && settings.comparing) {
                            Canvas(Modifier.size(12.dp)) { drawCircle(Color(ModelColors.argb(slot))) }
                        }
                    }
                }
                Text(
                    "Each picked model gets its own column of arrows on the map and in the table.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Each altitude is a row, lowest at the pin; a longer symbol is a stronger wind. Directions are " +
                        "shown in degrees (°) the wind comes from; temperatures end in C or F. Tap or long-press the " +
                        "map to move the pin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
    )
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
}
