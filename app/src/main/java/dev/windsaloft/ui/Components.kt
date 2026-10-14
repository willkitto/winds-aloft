package dev.windsaloft.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.RadioButton
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
private fun panelColors() = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))

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
        if (state.gridLoading || state.pinLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        } else {
            Spacer(Modifier.height(2.dp))
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp)) {
            LabelRow("Forecast", "${Format.time(state.selectedEpoch)}  (+${state.hourOffset} h)")
            Slider(
                value = state.hourOffset.toFloat(),
                onValueChange = { onHourOffset(it.roundToInt()) },
                valueRange = 0f..state.maxHourOffset.coerceAtLeast(1).toFloat(),
                modifier = Modifier.height(36.dp),
            )
            val lo = state.altRangeK.first * 1000
            val hi = state.altRangeK.last * 1000
            LabelRow("Altitude", "${Format.feet(lo)} – ${Format.feet(hi)} ft ${s.altitudeRef.name}")
            RangeSlider(
                value = state.altRangeK.first.toFloat()..state.altRangeK.last.toFloat(),
                onValueChange = { r -> onAltRange(r.start.roundToInt()..r.endInclusive.roundToInt()) },
                valueRange = 0f..ceilK.toFloat(),
                modifier = Modifier.height(36.dp),
            )
            AltitudeScale(s.ceilingFt, state.altRangeK)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                SpeedLegend(s.unit)
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

@Composable
private fun LabelRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}

/** Colour ramp matching the arrow colours, with the selected band highlighted. */
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

/** Shows how long an arrow is for a reference speed. */
@Composable
private fun SpeedLegend(unit: SpeedUnit) {
    val refKt = 20.0
    val len = WindOverlay.arrowLengthDp(refKt).dp
    val color = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(len).height(10.dp)) {
            val y = size.height / 2
            val head = 6.dp.toPx()
            drawLine(color, Offset(0f, y), Offset(size.width - head, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            val p = Path().apply {
                moveTo(size.width, y); lineTo(size.width - head, y - head * 0.6f); lineTo(size.width - head, y + head * 0.6f); close()
            }
            drawPath(p, color)
        }
        Spacer(Modifier.width(6.dp))
        val label = (refKt / KT_PER_MS * unit.perMs).roundToInt()
        Text("$label ${unit.label}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- Pinned location profile

@Composable
fun PinPanel(
    state: UiState,
    winds: List<Pair<Int, Wind?>>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = state.settings
    val fp = state.pinForecast
    val pin = state.pin ?: return
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
                val model = OpenMeteo.MODELS.firstOrNull { it.id == s.modelId }?.label ?: s.modelId
                val sub = buildList {
                    if (fp != null) add("Elev ${Format.elevation(fp.elevationM)}")
                    add(model)
                    if (state.pinFetchedAtMs > 0) add(Format.age(state.pinFetchedAtMs))
                }.joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (state.pinLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Icon(
                if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = if (expanded) "Hide profile" else "Show profile",
                modifier = Modifier.padding(start = 8.dp),
            )
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Remove pin") }
        }
        if (expanded) {
            HorizontalDivider()
            ProfileHeader(s.unit)
            if (fp == null) {
                Text(
                    if (state.pinLoading) "Loading forecast…" else "No forecast loaded",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 200.dp)) {
                    items(winds.asReversed(), key = { it.first }) { (ft, w) -> ProfileRow(ft, w, s) }
                }
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
private fun ProfileRow(ft: Int, w: Wind?, s: Settings) {
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawCircle(altitudeColor(ft, s.ceilingFt)) }
        Spacer(Modifier.width(8.dp))
        Text(
            Format.feet(ft) + if (ft == 0 && s.altitudeRef == AltitudeRef.AGL) " (sfc)" else "",
            Modifier.width(ColAlt),
            style = mono,
            maxLines = 1,
        )
        if (w == null) {
            Text(
                if (s.altitudeRef == AltitudeRef.MSL) "below ground / no data" else "no data",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Row
        }
        Row(Modifier.width(ColDir), verticalAlignment = Alignment.CenterVertically) {
            WindGlyph(w.dirFromDeg, altitudeColor(ft, s.ceilingFt))
            Spacer(Modifier.width(6.dp))
            Text(Format.direction(w.dirFromDeg), style = mono)
        }
        Text(Format.speed(w.speedMs, s.unit), Modifier.width(ColSpd), style = mono, fontWeight = FontWeight.SemiBold)
        Text(w.tempC?.let { "${it.roundToInt()}°C" } ?: "", style = mono)
    }
}

/** Small arrow pointing downwind, matching the map arrows. */
@Composable
fun WindGlyph(dirFromDeg: Double, color: Color) {
    val outline = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(18.dp)) {
        rotate((dirFromDeg + 180).toFloat()) {
            val cx = size.width / 2
            val top = 1.dp.toPx()
            val bottom = size.height - 1.dp.toPx()
            val head = 6.dp.toPx()
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
                Section("Map arrow grid")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Settings.GRID_SIZES.forEach { n ->
                        FilterChip(settings.gridSize == n, { onChange(settings.copy(gridSize = n)) },
                            label = { Text(if (n == 0) "Off" else "$n × $n") })
                    }
                }
                Section("Map style")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapLayer.entries.forEach { l ->
                        FilterChip(settings.layer == l, { onChange(settings.copy(layer = l)) }, label = { Text(l.label) })
                    }
                }
                Section("Forecast model")
                OpenMeteo.MODELS.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = settings.modelId == m.id, onClick = { onChange(settings.copy(modelId = m.id)) })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = settings.modelId == m.id, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(m.label)
                    }
                }
                Text(
                    "Arrows point downwind (the way the air is moving). Length is proportional to speed; " +
                        "colour shows altitude. Tap or long-press the map to pin a location and see its full profile.",
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
