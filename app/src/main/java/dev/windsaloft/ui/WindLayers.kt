package dev.windsaloft.ui

import androidx.compose.ui.graphics.Color
import dev.windsaloft.UiState
import dev.windsaloft.map.ModelColors
import dev.windsaloft.map.WindFan

/** Pin winds per selected model (in selection order) for the chosen time and altitudes. */
fun buildProfiles(state: UiState): List<ModelProfile> {
    val s = state.settings
    val altitudes = state.altitudesFt
    return s.modelIds.mapIndexedNotNull { slot, model ->
        val fp = state.pinForecasts[model] ?: return@mapIndexedNotNull null
        val color = if (s.comparing) Color(ModelColors.argb(slot)) else Color.Unspecified
        ModelProfile(model, color, altitudes.zip(fp.windsAt(state.selectedEpoch, altitudes, s.altitudeRef)))
    }
}

/**
 * Arrow columns for the map. Single model: coloured by altitude. Comparing: coloured by model
 * (altitude is then shown by row position); the grid uses the first model's colour.
 */
fun buildFans(state: UiState, profiles: List<ModelProfile>): List<WindFan> {
    val s = state.settings
    val gridColor = if (s.comparing) ModelColors.argb(0) else null
    val grid = state.grid.map {
        WindFan(it.location, it.windsAt(state.selectedEpoch, state.altitudesFt, s.altitudeRef), false, gridColor)
    }
    val pin = state.pin ?: return grid
    // In selection order: the overlay lays compared models out side by side in this order.
    val pinFans = profiles.mapIndexed { i, p ->
        val slot = s.modelIds.indexOf(p.modelId)
        WindFan(pin, p.winds.map { it.second }, true, if (s.comparing) ModelColors.argb(slot) else null, labels = i == 0)
    }
    return grid + pinFans.ifEmpty { listOf(WindFan(pin, emptyList(), true)) }
}
