package dev.windsaloft

import dev.windsaloft.data.ApiException
import dev.windsaloft.data.OpenMeteo
import dev.windsaloft.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStateTest {
    private val three = Settings(modelIds = listOf("ncep_gfs_seamless", "ecmwf_ifs025", "icon_seamless"))

    @Test
    fun isolatingOneModelShowsOnlyIt() {
        val all = UiState(settings = three)
        assertEquals(three.modelIds, all.visibleModelIds)
        assertTrue(all.showingSeveral)
        val one = all.copy(isolatedModel = "ecmwf_ifs025")
        assertEquals(listOf("ecmwf_ifs025"), one.visibleModelIds)
        assertFalse(one.showingSeveral)
        // A model that has since been unselected can't stay isolated.
        assertEquals(three.modelIds, all.copy(isolatedModel = "gem_seamless").visibleModelIds)
    }

    @Test
    fun anyNumberOfModels() {
        val every = Settings(modelIds = OpenMeteo.MODELS.map { it.id })
        assertEquals(OpenMeteo.MODELS.size, UiState(settings = every).visibleModelIds.size)
    }

    @Test
    fun regionalModelOutsideItsAreaIsNotAnError() {
        assertTrue(OpenMeteo.isNotCovered(ApiException("No data is available for this location")))
        assertFalse(OpenMeteo.isNotCovered(ApiException("Daily API request limit exceeded")))
    }
}
