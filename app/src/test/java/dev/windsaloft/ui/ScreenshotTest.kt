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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    /** Two made-up airport reports around Boulder: one gusty, one variable. */
    private fun sampleMetars(now: Long) = listOf(
        dev.windsaloft.data.Metar("KBJC", "Rocky Mountain Metro, CO, US", LatLon(39.909, -105.117), now - 1500, 300, 22, 39, 18.0,
            "METAR KBJC 160153Z 30022G39KT 10SM FEW120 18/M02 A2990"),
        dev.windsaloft.data.Metar("KBDU", "Boulder Muni, CO, US", LatLon(40.039, -105.226), now - 2400, null, 4, null, 19.0,
            "METAR KBDU 160135Z VRB04KT 10SM CLR 19/M03 A2991"),
    )

    private val outDir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }

    /**
     * Veering, strengthening winds with height — a typical post-frontal profile. With [shearAt], the wind
     * swings round by 100° and jumps in speed above that sample, to show shear markers.
     */
    private fun syntheticPoint(at: LatLon, elevation: Double, now: Long, skew: Double, shearAt: Int? = null): ForecastPoint {
        val hours = (0..48).map { now + it * 3600L }
        val profiles = hours.mapIndexed { h, _ ->
            (0..12).map { i ->
                val height = elevation + 10 + i * 700.0
                val sheared = shearAt != null && i >= shearAt
                // Speed and direction change through the day so the two-day page has highs and lows.
                val daily = 3.0 * Math.sin(h / 7.0 + i * 0.3)
                RawSample(height, 3.0 + i * 1.8 + skew + daily + (if (sheared) 9.0 else 0.0), (205.0 + i * 7 + skew * 4 + h * 2 + (if (sheared) 150.0 else 0.0)) % 360, 18.0 - i * 4.5)
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
        // Gusty at the ground: 3 m/s sustained, gusting 8-9 m/s.
        return ForecastPoint(at, elevation, hours.toLongArray(), profiles, clouds, DoubleArray(hours.size) { 8.0 + skew * 0.3 })
    }

    @Test
    fun renderSingleModel() = render("single_light", Settings(), expanded = false)

    @Test
    fun renderCompareLight() = render("compare_light", compareSettings, expanded = false)

    @Test
    fun renderTemperatureColour() = render("temperature_light", Settings(arrowColor = dev.windsaloft.data.ArrowColor.TEMPERATURE), expanded = false)

    @Test
    fun renderTriangles() = render("triangle_light", Settings(glyph = dev.windsaloft.data.Glyph.TRIANGLE,
        arrowAnchor = dev.windsaloft.data.ArrowAnchor.NOSE, showDirection = true), expanded = false)

    @Test
    fun renderBarbs() = render("barb_light", Settings(glyph = dev.windsaloft.data.Glyph.BARB), expanded = false)

    @Test
    @Config(qualifiers = "+night")
    fun renderCompareDarkExpanded() = render("compare_dark_table", compareSettings, expanded = true)

    private val compareSettings = Settings(modelIds = listOf("ncep_gfs_seamless", "ecmwf_ifs025", "icon_seamless"))

    @Test
    fun renderDetachedChartTemperature() = render(
        "detached_temperature", Settings(arrowColor = dev.windsaloft.data.ArrowColor.TEMPERATURE), expanded = true,
        chartOffset = LatLon(0.06, -0.16), pending = LatLon(40.02, -104.95),
    )

    private val sixModels = listOf("ncep_gfs_seamless", "ecmwf_ifs025", "icon_seamless", "gem_seamless", "ukmo_seamless", "meteofrance_seamless")

    @Test
    fun renderSixSlim() = render("six_slim", Settings(modelIds = sixModels, chartStyle = dev.windsaloft.data.ChartStyle.SLIM), expanded = false)

    @Test
    fun renderSixOneLine() = render("six_oneline", Settings(modelIds = sixModels, chartStyle = dev.windsaloft.data.ChartStyle.ONE_LINE), expanded = false)

    @Test
    fun renderSixConsensus() = render("six_consensus", Settings(modelIds = sixModels, chartStyle = dev.windsaloft.data.ChartStyle.CONSENSUS, backdropPct = 40), expanded = false)

    @Test
    fun renderShearBackdrop() = render("shear_backdrop", Settings(showShear = true, backdropPct = 50, layer = dev.windsaloft.data.MapLayer.SATELLITE), expanded = false, shearAt = 3)

    /** The two-day page, winds and then all models' clouds. */
    @Test
    fun renderTwoDay() {
        val now = currentHourEpoch()
        val pin = LatLon(40.10, -105.20)
        val settings = Settings(modelIds = sixModels.take(4) + "ncep_hrrr_conus")
        val state = UiState(
            settings = settings, pin = pin, pinLabel = "Boulder, Colorado",
            pinForecasts = sixModels.take(4).mapIndexed { i, m -> m to syntheticPoint(pin, 1580.0, now, i * 1.6) }.toMap(),
            nowHourEpoch = now, hourOffset = 10, unavailableModels = setOf("ncep_hrrr_conus"), altRangeK = 5..14,
        )
        compose.setContent { WindsAloftTheme { TwoDayScreen(state, {}) } }
        compose.waitForIdle()
        save("twoday_winds")
        // Dragging across the chart moves the time bar; it doesn't switch models.
        compose.onNode(hasContentDescription("Wind at each altitude", substring = true)).performTouchInput {
            swipe(Offset(width * 0.3f, height * 0.5f), Offset(width * 0.55f, height * 0.5f), durationMillis = 400)
        }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("(+10 h)", substring = true).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Wind at each altitude over the next 48 hours, NOAA GFS + HRRR", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodes(hasContentDescription("NOAA GFS + HRRR", substring = true)).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Next 24 h ›").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        save("twoday_winds_day2")
        compose.onNodeWithText("Clouds, all models").performClick()
        compose.waitForIdle()
        save("twoday_clouds")
    }

    @Test
    @Config(qualifiers = "+night")
    fun renderTwoDayDark() {
        val now = currentHourEpoch()
        val pin = LatLon(40.10, -105.20)
        val state = UiState(
            settings = Settings(), pin = pin, pinLabel = "Boulder, Colorado",
            pinForecasts = mapOf(Settings().primaryModelId to syntheticPoint(pin, 1580.0, now, 0.0)), nowHourEpoch = now, hourOffset = 4,
        )
        compose.setContent { WindsAloftTheme { TwoDayScreen(state, {}) } }
        compose.waitForIdle()
        save("twoday_dark")
    }

    /** The panel's ✕ only hides it: the spot and its forecast stay, and the pill brings the panel back. */
    @Test
    fun closingThePanelKeepsTheSpot() {
        Configuration.getInstance().userAgentValue = "dev.windsaloft.test"
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = dev.windsaloft.MainViewModel(app)
        val now = currentHourEpoch()
        val pin = LatLon(40.10, -105.20)
        vm.replaceStateForTest {
            it.copy(
                pin = pin, pinLabel = "Skydive Boulder", nowHourEpoch = now, hourOffset = 3, altRangeK = 4..14,
                pinForecasts = mapOf(it.settings.primaryModelId to syntheticPoint(pin, 1580.0, now, 0.0)),
            )
        }
        vm.saveCamera(Camera(LatLon(40.0, -105.2), 10.0))
        compose.setContent { WindsAloftTheme { WindsAloftApp(vm) } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Hide this panel").performClick()
        compose.waitForIdle()
        assertTrue("the spot stays", vm.state.value.pin == pin)
        assertTrue("its forecast stays", vm.state.value.pinForecasts.isNotEmpty())
        compose.onNodeWithContentDescription("Show the location panel").assertExists()
        repeat(4) { Thread.sleep(150); shadowOf(Looper.getMainLooper()).idle() }
        save("panel_hidden")
        compose.onNodeWithContentDescription("Show the location panel").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Hide this panel").assertExists()
    }

    /** Every change on the settings page redraws the preview. */
    @Test
    fun settingsPreviewFollowsChanges() {
        val now = currentHourEpoch()
        val pin = LatLon(40.10, -105.20)
        var settings by androidx.compose.runtime.mutableStateOf(Settings())
        val state = UiState(settings = settings, pin = pin, pinForecasts = mapOf(settings.primaryModelId to syntheticPoint(pin, 1580.0, now, 0.0)), nowHourEpoch = now, hourOffset = 3, altRangeK = 0..14)
        val columns = buildColumns(state, buildProfiles(state))
        compose.setContent { WindsAloftTheme { SettingsScreen(settings, { settings = it }, {}, columns, state.altitudesFt, "", {}) } }
        compose.waitForIdle()
        val before = capture()
        compose.onNodeWithText("Barb").performClick()
        compose.waitForIdle()
        val after = capture()
        assertFalse("preview should redraw when the symbol changes", before.sameAs(after))
    }

    private fun capture(): Bitmap {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val root = (global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<android.view.View>).first()
        // Just the top of the page, where the preview is.
        val bmp = Bitmap.createBitmap(root.width, root.height / 3, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        return bmp
    }

    @Test
    fun renderSettings() {
        val now = currentHourEpoch()
        val pin = LatLon(40.10, -105.20)
        val settings = Settings(modelIds = sixModels.take(3), backdropPct = 40, showShear = true)
        val state = UiState(settings = settings, pin = pin, pinForecasts = settings.modelIds.mapIndexed { i, m -> m to syntheticPoint(pin, 1580.0, now, i * 1.6) }.toMap(), nowHourEpoch = now, hourOffset = 3, altRangeK = 0..14)
        val columns = buildColumns(state, buildProfiles(state))
        compose.setContent { WindsAloftTheme { SettingsScreen(settings, {}, {}, columns, state.altitudesFt, "Saved on this phone: map tiles 212 MB, winds 1.4 MB.", {}) } }
        compose.waitForIdle()
        save("settings_top")
        compose.onNodeWithText("Highest altitude").performScrollTo()
        compose.waitForIdle()
        save("settings_models")
    }

    @Test
    fun renderRotatedMap() = render("rotated", Settings(), expanded = false, rotation = 40f)

    private fun render(
        name: String, settings: Settings, expanded: Boolean, chartOffset: LatLon? = null, pending: LatLon? = null, rotation: Float = 0f,
        shearAt: Int? = null,
    ) {
        Configuration.getInstance().userAgentValue = "dev.windsaloft.test"
        val now = currentHourEpoch()
        val pin = LatLon(40.10, -105.20)
        // Each model gets a slightly different profile so the comparison shows some spread.
        val pinForecasts = settings.modelIds.mapIndexed { i, m -> m to syntheticPoint(pin, 1580.0, now, i * 1.6, shearAt) }.toMap()
        val state = UiState(
            settings = settings, pin = pin, pinLabel = "Boulder, Colorado", pinForecasts = pinForecasts,
            pinFetchedAtMs = System.currentTimeMillis() - 4 * 60_000, nowHourEpoch = now,
            hourOffset = 3, altRangeK = 4..14,
            chartAt = chartOffset?.let { LatLon(pin.lat + it.lat, pin.lon + it.lon) }, pendingPin = pending,
            metars = if (expanded) sampleMetars(System.currentTimeMillis() / 1000) else emptyList(),
        )
        val profiles = buildProfiles(state)

        lateinit var map: MapHandle
        var panelsTop = 0
        compose.setContent {
            val ctx = LocalContext.current
            map = androidx.compose.runtime.remember { MapHandle(ctx, Camera(LatLon(40.0, -105.2), 10.0)) }
            WindsAloftTheme {
                Box(Modifier.fillMaxSize()) {
                    AndroidView(factory = {
                        map.setLayer(settings.layer)
                        map.windOverlay.location = pin
                        map.windOverlay.chartLocation = state.chartAt
                        map.windOverlay.pendingLocation = state.pendingPin
                        map.windOverlay.columns = buildColumns(state, profiles)
                        map.windOverlay.ceilingFt = settings.ceilingFt
                        map.windOverlay.layout = settings.arrowLayout
                        map.windOverlay.speedLabel = { ms -> Format.speed(ms, settings.unit) }
                        map.windOverlay.tempLabel = { c -> settings.tempUnit.format(c) }
                        map.windOverlay.colorMode = settings.arrowColor
                        map.windOverlay.showClouds = settings.showClouds
                        map.windOverlay.altitudesFt = state.altitudesFt
                        map.windOverlay.glyph = settings.glyph
                        map.windOverlay.anchor = settings.arrowAnchor
                        map.windOverlay.showDirection = settings.showDirection
                        map.windOverlay.showSpeed = settings.showSpeed
                        map.windOverlay.style = settings.chartStyle
                        map.windOverlay.backdropAlpha = settings.backdropPct / 100f
                        map.windOverlay.showShear = settings.showShear
                        map.windOverlay.unitLabel = settings.unit.label
                        map.metarOverlay.stations = state.metars
                        map.metarOverlay.speedLabel = { ms -> Format.speed(ms, settings.unit) }
                        map.mapView
                    }, modifier = Modifier.fillMaxSize())
                    CompassButton(rotation, {}, Modifier.align(Alignment.TopEnd).padding(top = 76.dp, end = 16.dp))
                    SearchBar(
                        emptyList(), {}, {}, {}, {}, {},
                        Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth(),
                    )
                    Column(
                        Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PinPanel(
                            state, profiles, expanded = expanded, onToggle = {}, onClose = {},
                            modifier = Modifier.fillMaxWidth()
                                .onGloballyPositioned { panelsTop = it.boundsInRoot().top.toInt() },
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
                (panelsTop - (28 * map.mapView.resources.displayMetrics.density).toInt())),
        ))
        if (rotation != 0f) {
            // Rotate about the forecast spot, a little below the middle so the chart fits above it.
            map.mapView.controller.setCenter(org.osmdroid.util.GeoPoint(pin.lat + 0.08, pin.lon))
            map.mapView.mapOrientation = rotation
        }
        repeat(10) { Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle(); map.mapView.invalidate() }
        compose.waitForIdle()
        val root = map.mapView.rootView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The real app screen, with the phone turned on its left side: panels down one side, chart along the phone. */
    @Test
    @Config(qualifiers = "w860dp-h400dp-land-xhdpi")
    fun renderLandscapeApp() {
        Configuration.getInstance().userAgentValue = "dev.windsaloft.test"
        val display = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowDisplay>(display).setRotation(android.view.Surface.ROTATION_90)
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = dev.windsaloft.MainViewModel(app)
        val now = currentHourEpoch()
        val pin = LatLon(40.10, -105.20)
        val dz = dev.windsaloft.data.SavedPlace("Skydive Boulder", pin, 0)
        vm.replaceStateForTest {
            it.copy(
                pin = pin, pinLabel = dz.name,
                pinForecasts = mapOf(Settings().primaryModelId to syntheticPoint(pin, 1580.0, now, 0.0)),
                pinFetchedAtMs = System.currentTimeMillis() - 2 * 3600_000L, nowHourEpoch = now, hourOffset = 3, altRangeK = 4..14,
                favorites = listOf(dz), online = false, metars = sampleMetars(System.currentTimeMillis() / 1000),
                settings = Settings(showDirection = true),
            )
        }
        vm.saveCamera(Camera(LatLon(40.0, -105.2), 10.0))
        compose.setContent { WindsAloftTheme { WindsAloftApp(vm) } }
        repeat(8) { Thread.sleep(200); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500)); compose.mainClock.advanceTimeBy(500) }
        compose.waitForIdle()
        repeat(6) { Thread.sleep(150); shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500)) }
        save("landscape_app")
    }

    @Test
    fun renderPlacesDialog() {
        val now = System.currentTimeMillis()
        val dz = dev.windsaloft.data.SavedPlace("Skydive Boulder", LatLon(40.10, -105.20), 0)
        val state = UiState(
            settings = Settings(), pin = dz.location,
            favorites = listOf(dz, dev.windsaloft.data.SavedPlace("Mile-Hi Skydiving", LatLon(40.165, -105.163), 0),
                dev.windsaloft.data.SavedPlace(null, LatLon(39.91, -105.12), 0)),
            recents = listOf(dz),
            savedWindTimes = mapOf(
                dev.windsaloft.data.WindCache.spotKey(dz.location) to now - 40 * 60_000L,
                dev.windsaloft.data.WindCache.spotKey(LatLon(40.165, -105.163)) to now - 5 * 3600_000L,
            ),
        )
        compose.setContent {
            WindsAloftTheme {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Card { Box(Modifier.padding(16.dp)) { PlacesContent(state, {}, {}, { _, _ -> }, {}, {}, {}) } }
                }
            }
        }
        compose.waitForIdle()
        save("places_dialog")
    }

    /** Draws every window (the activity plus any dialog) into one PNG. */
    private fun save(name: String) {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val roots = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<android.view.View>
        val base = roots.first()
        val bmp = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        for (v in roots) {
            val loc = IntArray(2).also { v.getLocationOnScreen(it) }
            canvas.save(); canvas.translate(loc[0].toFloat(), loc[1].toFloat())
            if (v !== base) canvas.drawColor(0x66000000)
            v.draw(canvas); canvas.restore()
        }
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
