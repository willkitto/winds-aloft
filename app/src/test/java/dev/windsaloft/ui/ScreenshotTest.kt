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
import dev.windsaloft.wind.ForecastPoint
import dev.windsaloft.wind.Bbox
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
                RawSample(height, 3.0 + i * 1.8 + skew, (170.0 + i * 11 + skew * 4 + h) % 360, 18.0 - i * 4.5)
            }
        }
        return ForecastPoint(at, elevation, hours.toLongArray(), profiles)
    }

    @Test
    fun renderSingleModel() = render("single_light", Settings(gridSize = 4))

    @Test
    fun renderCompareLight() = render("compare_light", compareSettings)

    @Test
    @Config(qualifiers = "+night")
    fun renderCompareDark() = render("compare_dark", compareSettings)

    private val compareSettings = Settings(gridSize = 3, modelIds = listOf("ncep_gfs_seamless", "ecmwf_ifs025", "icon_seamless"))

    private fun render(name: String, settings: Settings) {
        Configuration.getInstance().userAgentValue = "dev.windsaloft.test"
        val now = currentHourEpoch()
        val pin = LatLon(40.10, -105.20)
        // Each model gets a slightly different profile so the comparison shows some spread.
        val pinForecasts = settings.modelIds.mapIndexed { i, m -> m to syntheticPoint(pin, 1580.0, now, i * 1.6) }.toMap()
        val state = UiState(
            settings = settings, pin = pin, pinLabel = "Boulder, Colorado", pinForecasts = pinForecasts,
            pinFetchedAtMs = System.currentTimeMillis() - 4 * 60_000, nowHourEpoch = now,
            hourOffset = 3, altRangeK = 6..16,
        )
        val profiles = buildProfiles(state)
        val fans = buildFans(state, profiles)

        lateinit var map: MapHandle
        compose.setContent {
            val ctx = LocalContext.current
            map = androidx.compose.runtime.remember { MapHandle(ctx, Camera(LatLon(40.0, -105.2), 9.0)) }
            WindsAloftTheme {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = {
                        map.setLayer(settings.layer)
                        map.windOverlay.fans = fans
                        map.windOverlay.ceilingFt = settings.ceilingFt
                        map.windOverlay.layout = settings.arrowLayout
                        map.windOverlay.gridSize = settings.gridSize
                        map.windOverlay.insetTopPx = (80 * ctx.resources.displayMetrics.density).toInt()
                        map.windOverlay.insetBottomPx = (220 * ctx.resources.displayMetrics.density).toInt()
                        map.windOverlay.pinInsetBottomPx = (540 * ctx.resources.displayMetrics.density).toInt()
                        map.windOverlay.speedLabel = { ms -> Format.speed(ms, settings.unit) }
                        map.mapView
                    }, modifier = Modifier.fillMaxSize())
                    SearchBar(emptyList(), {}, {}, {}, {}, Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth())
                    Column(
                        Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PinPanel(state, profiles, expanded = true, onToggle = {}, onClose = {}, modifier = Modifier.fillMaxWidth())
                        ControlsCard(state, settings.layer.attribution, {}, {}, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        repeat(5) { Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle() }
        compose.waitForIdle()

        // Build the grid from the map's real viewport, exactly as the app does.
        var bbox: Bbox? = null
        map.obscuredTopPx = map.windOverlay.insetTopPx
        map.obscuredBottomPx = map.windOverlay.insetBottomPx
        map.onViewport = { bbox = it }
        map.reportViewport()
        val grid = bbox!!.gridPoints(settings.gridSize).mapIndexed { i, p ->
            syntheticPoint(p, 1600.0 + (i % settings.gridSize) * 150, now, (i / settings.gridSize - i % settings.gridSize) * 0.8)
        }
        map.windOverlay.fans = buildFans(state.copy(grid = grid), profiles)
        map.mapView.invalidate()
        repeat(5) { Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle() }
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
