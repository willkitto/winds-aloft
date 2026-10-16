package dev.windsaloft.map

import android.graphics.Rect
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.windsaloft.data.Camera
import dev.windsaloft.data.ChartOrientation
import dev.windsaloft.wind.LatLon
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.config.Configuration
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrientationTest {
    @Test
    fun chartDirection() {
        // Phone upright.
        assertEquals(0, chartTurnDegrees(ChartOrientation.LONGEST, 400, 860, 0))
        assertEquals(0, chartTurnDegrees(ChartOrientation.VERTICAL, 400, 860, 0))
        assertEquals(90, chartTurnDegrees(ChartOrientation.HORIZONTAL, 400, 860, 0))
        // Phone turned left (top edge to the left, ROTATION_90): the chart keeps running toward the phone's top.
        assertEquals(270, chartTurnDegrees(ChartOrientation.LONGEST, 860, 400, 90))
        assertEquals(270, chartTurnDegrees(ChartOrientation.HORIZONTAL, 860, 400, 90))
        assertEquals(0, chartTurnDegrees(ChartOrientation.VERTICAL, 860, 400, 90))
        // Turned right (ROTATION_270).
        assertEquals(90, chartTurnDegrees(ChartOrientation.LONGEST, 860, 400, 270))
        // A tablet that is naturally landscape.
        assertEquals(90, chartTurnDegrees(ChartOrientation.LONGEST, 1280, 800, 0))
    }

    @Test
    fun clearAreaCutsAwayPanels() {
        val screen = Rect(0, 0, 400, 860)
        val free = clearArea(screen, listOf(Rect(12, 8, 388, 60), Rect(12, 500, 388, 850)))
        assertEquals(Rect(0, 60, 400, 500), free)
        // A side panel in landscape leaves the other side.
        assertEquals(Rect(0, 0, 460, 400), clearArea(Rect(0, 0, 860, 400), listOf(Rect(460, 0, 860, 400))))
    }

    @Test
    fun turningThePhoneLeavesTheMapWhereItWas() {
        Configuration.getInstance().userAgentValue = "dev.windsaloft.test"
        val map = MapHandle(ApplicationProvider.getApplicationContext(), Camera(LatLon(40.1, -105.2), 12.0))
        fun layout(w: Int, h: Int) {
            map.mapView.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            map.mapView.layout(0, 0, w, h)
        }
        layout(400, 860)
        map.onDisplayRotation(0)
        val before = map.mapView.mapCenter
        // The phone turns left: the screen is now landscape and has turned 90°.
        map.onDisplayRotation(90)
        layout(860, 400)
        assertEquals(270f, map.mapView.mapOrientation, 0.01f)
        assertEquals(before.latitude, map.mapView.mapCenter.latitude, 1e-6)
        assertEquals(before.longitude, map.mapView.mapCenter.longitude, 1e-6)
        // And back upright.
        map.onDisplayRotation(0)
        layout(400, 860)
        assertEquals(0f, map.mapView.mapOrientation, 0.01f)
        assertEquals(before.latitude, map.mapView.mapCenter.latitude, 1e-6)
    }
}
