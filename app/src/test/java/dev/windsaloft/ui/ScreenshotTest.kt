package dev.windsaloft.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.core.app.ActivityScenario
import dev.windsaloft.MainActivity
import dev.windsaloft.UiState
import dev.windsaloft.data.Camera
import dev.windsaloft.data.Settings
import dev.windsaloft.map.MapHandle
import dev.windsaloft.map.attribution
import dev.windsaloft.wind.CloudSample
import dev.windsaloft.wind.ForecastPoint
import dev.windsaloft.wind.LatLon
import dev.windsaloft.wind.RawSample
import dev.windsaloft.currentHourEpoch
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.config.Configuration
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the app UI with synthetic wind data to a PNG (no emulator needed), and checks the real
 * activity starts. Output dir: -Dscreenshot.dir=... (defaults to build/screenshots).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val outDir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }

    /** Veering, strengthening winds with height — a typical post-frontal profile. */
    private fun syntheticPoint(at: LatLon, elevation: Double, now: Long, skew: Double): ForecastPoint {
        val hours = (0..48).map { now + it * 3600L }
        val profiles = hours.mapIndexed { h, _ ->
            (0..12).map { i ->
                val height = elevation + 10 + i * 700.0
                RawSample(height, 3.0 + i * 1.8 + skew, (205.0 + i * 7 + skew * 4 + h) % 360, 18.0 - i * 4.5)
            }
        }
        // A broken layer around 7,500–9,500 ft MSL and a scattered one near 12,500 ft.
        val clouds = hours.map {
            listOf(
                CloudSample(elevation + 300, 0.0), CloudSample(2200.0, 15.0), CloudSample(2350.0, 70.0), CloudSample(2850.0, 65.0),
                CloudSample(3000.0, 5.0), CloudSample(3700.0, 0.0), CloudSample(3820.0, 40.0), CloudSample(3980.0, 0.0),
                CloudSample(4500.0, 0.0), CloudSample(4700.0, 90.0), CloudSample(5000.0, 95.0), CloudSample(5200.0, 0.0),
            )
        }
        return ForecastPoint(at, elevation, hours.toLongArray(), profiles, clouds)
    }

    @Test
    fun renderSingleModel() = render("single_light", Settings(), expanded = false)

    @Test
    fun renderCompareLight() = render("compare_light", compareSettings, expanded = false)

    @Test
    fun renderTemperatureColour() = render("temperature_light", Settings(arrowColor = dev.windsaloft.data.ArrowColor.TEMPERATURE), expanded = false)

    @Test
    @Config(qualifiers = "+night")
    fun renderCompareDarkExpanded() = render("compare_dark_table", compareSettings, expanded = true)

    private val compareSettings = Settings(modelIds = listOf("ncep_gfs_seamless", "ecmwf_ifs025", "icon_seamless"))

    private fun render(name: String, settings: Settings, expanded: Boolean) {
        Configuration.getInstance().userAgentValue = "dev.windsaloft.test"
        val now = currentHourEpoch()
        val pin = LatLon(40.10, -105.20)
        // Each model gets a slightly different profile so the comparison shows some spread.
        val pinForecasts = settings.modelIds.mapIndexed { i, m -> m to syntheticPoint(pin, 1580.0, now, i * 1.6) }.toMap()
        val state = UiState(
            settings = settings, pin = pin, pinLabel = "Boulder, Colorado", pinForecasts = pinForecasts,
            pinFetchedAtMs = System.currentTimeMillis() - 4 * 60_000, nowHourEpoch = now,
            hourOffset = 3, altRangeK = 4..14,
        )
        val profiles = buildProfiles(state)

        lateinit var map: MapHandle
        compose.setContent {
            val ctx = LocalContext.current
            map = androidx.compose.runtime.remember { MapHandle(ctx, Camera(LatLon(40.0, -105.2), 10.0)) }
            WindsAloftTheme {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = {
                        map.setLayer(settings.layer)
                        map.windOverlay.location = pin
                        map.windOverlay.columns = buildColumns(state, profiles)
                        map.windOverlay.ceilingFt = settings.ceilingFt
                        map.windOverlay.layout = settings.arrowLayout
                        map.windOverlay.speedLabel = { ms -> Format.speed(ms, settings.unit) }
                        map.windOverlay.tempLabel = { c -> settings.tempUnit.format(c) }
                        map.windOverlay.colorMode = settings.arrowColor
                        map.windOverlay.showClouds = settings.showClouds
                        map.windOverlay.altitudesFt = state.altitudesFt
                        map.mapView
                    }, modifier = Modifier.fillMaxSize())
                    SearchBar(
                        emptyList(), {}, {}, {}, {},
                        Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth()
                            .onGloballyPositioned { map.obscuredTopPx = it.boundsInRoot().bottom.toInt() },
                    )
                    Column(
                        Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PinPanel(
                            state, profiles, expanded = expanded, onToggle = {}, onClose = {},
                            modifier = Modifier.fillMaxWidth()
                                .onGloballyPositioned { map.panelsTopPx = it.boundsInRoot().top.toInt() },
                        )
                        ControlsCard(state, settings.layer.attribution, {}, {}, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        repeat(5) { Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle() }
        compose.waitForIdle()
        // Same as the app: size the columns to the free map area and move the pin to its bottom-centre.
        map.mapView.controller.setCenter(map.mapView.projection.fromPixels(
            map.mapView.width / 2,
            map.mapView.height / 2 + (map.mapView.projection.toPixels(org.osmdroid.util.GeoPoint(pin.lat, pin.lon), null).y -
                (map.panelsTopPx - (28 * map.mapView.resources.displayMetrics.density).toInt())),
        ))
        repeat(10) { Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle(); map.mapView.invalidate() }
        compose.waitForIdle()
        val root = map.mapView.rootView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun mainActivityStarts() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onActivity { assertNotNull(it.window.decorView) }
        }
    }
}
