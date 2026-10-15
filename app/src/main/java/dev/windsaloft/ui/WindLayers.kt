package dev.windsaloft.ui

import androidx.compose.ui.graphics.Color
import dev.windsaloft.UiState
import dev.windsaloft.map.ModelColors
import dev.windsaloft.data.OpenMeteo
import dev.windsaloft.map.WindColumn

/** Pin winds per selected model (in selection order) for the chosen time and altitudes. */
fun buildProfiles(state: UiState): List<ModelProfile> {
    val s = state.settings
    val altitudes = state.altitudesFt
    return s.modelIds.mapIndexedNotNull { slot, model ->
        val fp = state.pinForecasts[model] ?: return@mapIndexedNotNull null
        val color = if (s.comparing) Color(ModelColors.argb(slot)) else Color.Unspecified
        ModelProfile(
            model, color, altitudes.zip(fp.windsAt(state.selectedEpoch, altitudes, s.altitudeRef)),
            clouds = fp.cloudLayers(state.selectedEpoch, s.altitudeRef, 0, s.ceilingFt),
        )
    }
}

/**
 * Map columns for the pin, one per model in selection order. Single model: arrows coloured by
 * altitude. Comparing: each column takes its model's colour and is labelled with the model name.
 */
fun buildColumns(state: UiState, profiles: List<ModelProfile>): List<WindColumn> {
    val s = state.settings
    return profiles.map { p ->
        val slot = s.modelIds.indexOf(p.modelId)
        WindColumn(
            label = if (s.comparing) OpenMeteo.short(p.modelId) else null,
            color = if (s.comparing) ModelColors.argb(slot) else null,
            winds = p.winds.map { it.second },
            clouds = state.pinForecasts[p.modelId]?.cloudLayers(
                state.selectedEpoch, s.altitudeRef, state.altRangeK.first * 1000, state.altRangeK.last * 1000,
            ),
        )
    }
}
