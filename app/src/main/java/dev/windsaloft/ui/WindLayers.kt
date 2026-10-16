package dev.windsaloft.ui

import androidx.compose.ui.graphics.Color
import dev.windsaloft.UiState
import dev.windsaloft.map.ModelColors
import dev.windsaloft.data.OpenMeteo
import dev.windsaloft.map.WindColumn

/**
 * Pin winds per visible model (in selection order) for the chosen time and altitudes. Each model keeps
 * its colour from its place in the full selection, so isolating one doesn't repaint it.
 */
fun buildProfiles(state: UiState): List<ModelProfile> {
    val s = state.settings
    val altitudes = state.altitudesFt
    val several = state.showingSeveral
    return state.visibleModelIds.mapNotNull { model ->
        val fp = state.pinForecasts[model] ?: return@mapNotNull null
        val slot = s.modelIds.indexOf(model)
        val color = if (several || state.isolatedModel != null) Color(ModelColors.argb(slot)) else Color.Unspecified
        ModelProfile(
            model, color, altitudes.zip(fp.windsAt(state.selectedEpoch, altitudes, s.altitudeRef)),
            clouds = fp.cloudLayers(state.selectedEpoch, s.altitudeRef, 0, s.ceilingFt),
        )
    }
}

/**
 * Map columns for the pin, one per visible model in selection order. Single model: arrows coloured by
 * the colour setting. Several: each column takes its model's colour and is labelled with the model name.
 */
fun buildColumns(state: UiState, profiles: List<ModelProfile>): List<WindColumn> {
    val s = state.settings
    val several = profiles.size > 1
    // An isolated model keeps its name (tap it again to bring the others back) and its colour.
    val isolated = state.isolatedModel != null && s.comparing
    return profiles.map { p ->
        val slot = s.modelIds.indexOf(p.modelId)
        WindColumn(
            label = when { several -> OpenMeteo.short(p.modelId); isolated -> OpenMeteo.short(p.modelId) + " · show all"; else -> null },
            color = if (several || isolated) ModelColors.argb(slot) else null,
            winds = p.winds.map { it.second },
            // All layers up to the ceiling: those outside the shown altitudes get markers on the map.
            clouds = p.clouds,
            id = p.modelId,
        )
    }
}
