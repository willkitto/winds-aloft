package dev.windsaloft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.windsaloft.data.ArrowAnchor
import dev.windsaloft.data.ArrowColor
import dev.windsaloft.data.ArrowLayout
import dev.windsaloft.data.ChartOrientation
import dev.windsaloft.data.ChartStyle
import dev.windsaloft.data.Glyph
import dev.windsaloft.data.MapLayer
import dev.windsaloft.data.OpenMeteo
import dev.windsaloft.data.Settings
import dev.windsaloft.data.SpeedUnit
import dev.windsaloft.data.TempUnit
import dev.windsaloft.map.ModelColors
import dev.windsaloft.map.WindColumn
import dev.windsaloft.map.WindOverlay
import dev.windsaloft.wind.AltitudeRef
import dev.windsaloft.wind.Wind

/**
 * Full-screen settings in six groups, with a live preview of the chart pinned at the top so every
 * change shows straight away. Rarely needed chart options fold under "More chart options".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    onChange: (Settings) -> Unit,
    onDismiss: () -> Unit,
    previewColumns: List<WindColumn>,
    previewAltitudes: List<Int>,
    offlineInfo: String,
    onUpdateSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    val s = settings
    var more by rememberSaveable { mutableStateOf(false) }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            ChartPreview(s, previewColumns, previewAltitudes, Modifier.padding(horizontal = 12.dp).widthIn(max = 600.dp).fillMaxWidth().align(Alignment.CenterHorizontally))
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 12.dp)) {
                    Group("Units") {
                        SegRow("Wind speed", SpeedUnit.entries.map { it.label }, s.unit.ordinal) { onChange(s.copy(unit = SpeedUnit.entries[it])) }
                        Divider()
                        SegRow("Temperature", TempUnit.entries.map { it.name.lowercase() }, s.tempUnit.ordinal) { onChange(s.copy(tempUnit = TempUnit.entries[it])) }
                        Divider()
                        SegRow("Altitudes", listOf("MSL", "AGL"), if (s.altitudeRef == AltitudeRef.MSL) 0 else 1, "MSL from sea level, AGL from the ground") {
                            onChange(s.copy(altitudeRef = if (it == 0) AltitudeRef.MSL else AltitudeRef.AGL))
                        }
                    }
                    Group("Wind chart") {
                        SegRow("Comparing models", ChartStyle.entries.map { it.short }, s.chartStyle.ordinal, "Also on the map: the style button", stacked = true) {
                            onChange(s.copy(chartStyle = ChartStyle.entries[it]))
                        }
                        Divider()
                        SegRow("Symbol", Glyph.entries.map { it.short }, s.glyph.ordinal) {
                            val g = Glyph.entries[it]
                            onChange(s.copy(glyph = g, arrowAnchor = g.defaultAnchor))
                        }
                        Divider()
                        PickRow("Colour by", ArrowColor.entries.map { it.label }, s.arrowColor.ordinal) { onChange(s.copy(arrowColor = ArrowColor.entries[it])) }
                        Divider()
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text("Numbers on the chart", style = MaterialTheme.typography.bodyLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(s.showSpeed, { onChange(s.copy(showSpeed = !s.showSpeed)) }, label = { Text("Speed") })
                                FilterChip(s.showDirection, { onChange(s.copy(showDirection = !s.showDirection)) }, label = { Text("Direction") })
                                FilterChip(s.showTemperature, { onChange(s.copy(showTemperature = !s.showTemperature)) }, label = { Text("Temperature") })
                            }
                        }
                        Divider()
                        SwitchRow("Shear markers", "Flag sharp turns or speed changes between altitudes", s.showShear) { onChange(s.copy(showShear = it)) }
                        Divider()
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Background behind the chart", style = MaterialTheme.typography.bodyLarge)
                                    Text("Makes the numbers easier to read on a busy map", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(if (s.backdropPct == 0) "Off" else "${s.backdropPct} %", style = MaterialTheme.typography.labelLarge)
                            }
                            Slider(
                                value = s.backdropPct.toFloat(), onValueChange = { onChange(s.copy(backdropPct = (Math.round(it / 10f) * 10).coerceIn(0, 90))) },
                                valueRange = 0f..90f, steps = 8,
                            )
                        }
                        Divider()
                        Row(
                            Modifier.fillMaxWidth().clickable { more = !more }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (more) "▾  Fewer chart options" else "▸  More chart options", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                        if (more) {
                            SegRow("On the column line", ArrowAnchor.entries.map { it.short }, s.arrowAnchor.ordinal, s.glyph.anchorHint) {
                                onChange(s.copy(arrowAnchor = ArrowAnchor.entries[it]))
                            }
                            Divider()
                            SegRow("Layout", ArrowLayout.entries.map { it.label }, s.arrowLayout.ordinal, "Fan: all arrows from the pin, length = speed") {
                                onChange(s.copy(arrowLayout = ArrowLayout.entries[it]))
                            }
                            Divider()
                            SegRow("Phone turned sideways", listOf("Long side", "Vertical", "Horizontal"), s.chartOrientation.ordinal, "Which way the chart runs", stacked = true) {
                                onChange(s.copy(chartOrientation = ChartOrientation.entries[it]))
                            }
                        }
                    }
                    Group("Forecast") {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Models", style = MaterialTheme.typography.bodyLarge)
                                Text("${s.modelIds.size} of ${OpenMeteo.MODELS.size} shown side by side", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onChange(s.copy(modelIds = OpenMeteo.MODELS.map { it.id })) }) { Text("Select all") }
                        }
                        OpenMeteo.MODELS.forEach { m ->
                            val slot = s.modelIds.indexOf(m.id)
                            val selected = slot >= 0
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(selected, enabled = !(selected && s.modelIds.size == 1), role = Role.Checkbox) { on ->
                                        // Keep the menu's order, so a model's colour doesn't jump around as others are toggled.
                                        val ids = if (on) OpenMeteo.MODELS.map { it.id }.filter { it in s.modelIds || it == m.id } else s.modelIds - m.id
                                        onChange(s.copy(modelIds = ids))
                                    }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(if (selected && s.modelIds.size > 1) Color(ModelColors.argb(slot)) else Color.Transparent))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(m.label, style = MaterialTheme.typography.bodyLarge)
                                    if (m.region != null) Text(m.region, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(m.short, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                            }
                        }
                        Divider()
                        SegRow("Highest altitude", Settings.CEILINGS.map { "${it / 1000}k ft" }, Settings.CEILINGS.indexOf(s.ceilingFt).coerceAtLeast(0)) {
                            onChange(s.copy(ceilingFt = Settings.CEILINGS[it]))
                        }
                    }
                    Group("Map & observations") {
                        Text("Map style", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                        MapLayer.entries.forEach { l ->
                            Row(
                                Modifier.fillMaxWidth().selectable(s.layer == l, role = Role.RadioButton) { onChange(s.copy(layer = l)) }.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = s.layer == l, onClick = null, modifier = Modifier.padding(8.dp))
                                Text(l.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                if (l.region != null) Text(l.region, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
                            }
                        }
                        Divider()
                        SwitchRow("Cloud layers", "Bands on the chart, bases and tops in the panel", s.showClouds) { onChange(s.copy(showClouds = it)) }
                        Divider()
                        SwitchRow("Airport reports", "Observed wind and gusts at nearby airports", s.showMetars) { onChange(s.copy(showMetars = it)) }
                    }
                    Group("Offline") {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Winds for saved places download whenever you're online (and refresh when over an hour old), " +
                                    "and the last 20 spots you looked at are kept too, so they work in the plane. Map tiles you've " +
                                    "looked at are saved: open your drop zone and zoom around once before you go.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (offlineInfo.isNotEmpty()) Text(offlineInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = onUpdateSaved) { Text("Download winds for saved places now") }
                        }
                    }
                    Group("How to read the chart") {
                        Text(
                            "Each altitude is a row, lowest at the pin; a longer symbol is a stronger wind. Directions are the " +
                                "degrees the wind comes from (°); temperatures end in c or f. The SFC row is the ground wind, with " +
                                "forecast gusts in brackets: 4 (18). Long-press the map to move the forecast spot, tap a model's name " +
                                "to show only that model, and use the style button to switch how several models are drawn.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

private val ChartStyle.short: String get() = when (this) {
    ChartStyle.FULL -> "Full"; ChartStyle.SLIM -> "Slim"; ChartStyle.ONE_LINE -> "One line"; ChartStyle.CONSENSUS -> "Average"
}
private val Glyph.short: String get() = when (this) { Glyph.ARROW -> "Arrow"; Glyph.TRIANGLE -> "Triangle"; Glyph.BARB -> "Barb" }
private val ArrowAnchor.short: String get() = when (this) { ArrowAnchor.TAIL -> "Tail"; ArrowAnchor.NOSE -> "Nose"; ArrowAnchor.CENTRE -> "Centre" }
private val Glyph.anchorHint: String get() = when (this) {
    Glyph.ARROW -> "Arrows point downwind; tail on the line works like a windsock"
    Glyph.TRIANGLE -> "Triangles point into the wind; nose on the line works like a tetrahedron"
    Glyph.BARB -> "Barbs point into the wind; tail on the line is the weather-chart convention"
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(
        title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 18.dp, bottom = 6.dp),
    )
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegRow(label: String, options: List<String>, selected: Int, sub: String? = null, stacked: Boolean = false, onSelect: (Int) -> Unit) {
    val buttons = @Composable { modifier: Modifier ->
        SingleChoiceSegmentedButtonRow(modifier) {
            options.forEachIndexed { i, o ->
                SegmentedButton(
                    selected = i == selected, onClick = { onSelect(i) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    icon = {},
                ) { Text(o, maxLines = 1) }
            }
        }
    }
    val title = @Composable { modifier: Modifier ->
        Column(modifier) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (stacked || options.size > 3 || options.sumOf { it.length } > 14) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title(Modifier)
            buttons(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            title(Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            buttons(Modifier)
        }
    }
}

@Composable
private fun SwitchRow(label: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A row that opens a short menu of choices. */
@Composable
private fun PickRow(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(options[selected] + "  ▾", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, modifier = Modifier.align(Alignment.CenterEnd)) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(i); open = false }) }
        }
    }
}

/**
 * The real chart, drawn by the same code as the map, on a plain map-like background: a few rows of the
 * current forecast (or a sample when nothing is pinned).
 */
@Composable
private fun ChartPreview(s: Settings, columns: List<WindColumn>, altitudes: List<Int>, modifier: Modifier) {
    val density = LocalDensity.current.density
    val overlay = remember { WindOverlay(density) }
    val (cols, alts) = remember(columns, altitudes, s.modelIds) { previewData(columns, altitudes, s.modelIds) }
    overlay.columns = cols
    overlay.altitudesFt = alts
    overlay.ceilingFt = s.ceilingFt
    overlay.layout = s.arrowLayout
    overlay.colorMode = s.arrowColor
    overlay.showTemperature = s.showTemperature
    overlay.showClouds = s.showClouds
    overlay.anchor = s.arrowAnchor
    overlay.glyph = s.glyph
    overlay.showSpeed = s.showSpeed
    overlay.showDirection = s.showDirection
    overlay.style = s.chartStyle
    overlay.backdropAlpha = s.backdropPct / 100f
    overlay.showShear = s.showShear
    overlay.unitLabel = s.unit.label
    overlay.speedLabel = { ms -> Format.speed(ms, s.unit) }
    overlay.tempLabel = { c -> s.tempUnit.format(c) }
    Box(modifier.height(210.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))) {
        // The overlay's settings aren't Compose state, so key the canvas on them: any change redraws it.
        key(s, cols, alts) { Canvas(Modifier.fillMaxSize()) {
            // A soft topo-like ground with a few contour lines, standing in for the map.
            drawRect(Color(0xFFE7E9DC))
            val contour = Color(0xFFCDD1BB)
            for (k in 1..7) {
                drawOval(contour, Offset(size.width * 0.62f - k * 26f * density, size.height * 0.2f - k * 10f * density), Size(k * 52f * density, k * 30f * density), style = Stroke(1f * density))
            }
            drawLine(Color(0xFFF3DD8E), Offset(0f, size.height * 0.78f), Offset(size.width, size.height * 0.62f), strokeWidth = 4f * density)
            drawIntoCanvas { c -> overlay.drawPreview(c.nativeCanvas, size.width, size.height) }
        } }
        Text(
            "Preview", style = MaterialTheme.typography.labelMedium, color = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0x99101820), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Every other altitude from the ground up, at most four rows, so the preview fits its box. */
private fun previewData(columns: List<WindColumn>, altitudes: List<Int>, modelIds: List<String>): Pair<List<WindColumn>, List<Int>> {
    val real = columns.takeIf { cs -> cs.any { c -> c.winds.any { it != null } } }
    val source = real ?: sample(modelIds)
    val alts = if (real == null) SAMPLE_ALTS else altitudes
    val rows = source.maxOf { it.winds.size }
    val first = (0 until rows).firstOrNull { i -> source.any { it.winds.getOrNull(i) != null } } ?: 0
    val picked = (first until rows).filter { (it - first) % 2 == 0 }.take(4)
    return source.map { c -> c.copy(winds = picked.map { c.winds.getOrNull(it) }, clouds = emptyList()) } to picked.map { alts.getOrElse(it) { 0 } }
}

private val SAMPLE_ALTS = (0..12).map { it * 1000 }
/** Made-up winds for each selected model (slightly different per model), for when nothing is pinned. */
private fun sample(modelIds: List<String>): List<WindColumn> = modelIds.mapIndexed { j, id ->
    val several = modelIds.size > 1
    WindColumn(
        if (several) OpenMeteo.short(id) else null, if (several) ModelColors.argb(j) else null,
        SAMPLE_ALTS.mapIndexed { i, ft -> Wind(ft, 230.0 + i * 6 + j * 7, 3.0 + i * 1.6 + j * 0.8, 16.0 - i * 2.0, gustMs = if (i == 0) 7.0 + j else null, surface = i == 0) },
        id = id,
    )
}
