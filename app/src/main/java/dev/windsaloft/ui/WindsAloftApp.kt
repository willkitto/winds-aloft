package dev.windsaloft.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.font.FontWeight
import dev.windsaloft.data.ChartStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import kotlinx.coroutines.delay
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.windsaloft.MainViewModel
import dev.windsaloft.data.MapLayer
import dev.windsaloft.map.MapHandle
import dev.windsaloft.map.attribution
import dev.windsaloft.map.chartTurnDegrees
import dev.windsaloft.wind.LatLon
import org.osmdroid.util.GeoPoint

/** What the panels show: everything, only the forecast-time slider, or nothing. */
enum class PanelMode { FULL, TIME_ONLY, HIDDEN }

/** Records where a panel covers the map, so a newly placed chart lands in the clear part. */
@Composable
private fun Modifier.coversMap(map: MapHandle, key: String): Modifier {
    DisposableEffect(key) { onDispose { map.obstacles.remove(key) } }
    return onGloballyPositioned {
        val r = it.boundsInRoot()
        map.obstacles[key] = android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
    }
}

@Composable
fun WindsAloftApp(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val map = remember { MapHandle(context, vm.lastCamera) }
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var showPlaces by remember { mutableStateOf(false) }
    var showTwoDay by rememberSaveable { mutableStateOf(false) }
    var layerMenu by remember { mutableStateOf(false) }
    // Collapsed by default so the arrow columns get most of the screen; tap the panel for the table.
    var profileExpanded by rememberSaveable { mutableStateOf(false) }
    // The location panel's ✕ hides it (down to a small pill); a newly picked spot shows it again.
    var pinPanelHidden by rememberSaveable { mutableStateOf(false) }
    var lastPin by remember { mutableStateOf(state.pin) }
    if (state.pin != lastPin) { lastPin = state.pin; pinPanelHidden = false }
    // The hide button steps through: all panels -> forecast time only -> nothing. The arrows never resize.
    var panelModeName by rememberSaveable { mutableStateOf(PanelMode.FULL.name) }
    val panelMode = PanelMode.valueOf(panelModeName)
    fun setPanelMode(m: PanelMode) { panelModeName = m.name }

    // Moving the forecast spot is deliberate: long-press proposes a spot, then the user confirms.
    map.onLongPress = vm::proposePin
    map.windOverlay.onChartMoved = vm::setChartAt
    // Two-finger rotation turns the map; the compass shows north and resets it.
    var mapRotation by remember { mutableFloatStateOf(map.mapView.mapOrientation) }
    map.windOverlay.onRotation = { mapRotation = it }

    // ---- Phone orientation ----
    // The screen turns with the phone (panels, text and keyboard stay readable), but the map is turned
    // back so it doesn't move, and the chart keeps the direction picked in Settings.
    val config = LocalConfiguration.current
    val view = LocalView.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    @Suppress("DEPRECATION")
    val displayRotation = remember(config) { (view.display?.rotation ?: 0) * 90 }
    SideEffect {
        map.onDisplayRotation(displayRotation)
        mapRotation = map.mapView.mapOrientation
    }
    val s = state.settings
    val chartTurn = chartTurnDegrees(s.chartOrientation, config.screenWidthDp, config.screenHeightDp, displayRotation)

    // ---- Location ----
    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // Only moves the map to where you are; getting winds there is still a long-press, like anywhere else.
    fun goToMyLocation() {
        map.myLocation.enableMyLocation()
        val zoom = maxOf(map.mapView.zoomLevelDouble, 11.0)
        val fix = map.myLocation.myLocation
        if (fix != null) {
            vm.moveCamera(LatLon(fix.latitude, fix.longitude), zoom)
        } else {
            vm.message("Waiting for a location fix…")
            map.myLocation.runOnFirstFix {
                val l = map.myLocation.myLocation ?: return@runOnFirstFix
                map.mapView.post { vm.moveCamera(LatLon(l.latitude, l.longitude), zoom) }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) goToMyLocation() else vm.message("Location permission denied")
    }
    fun myLocationClicked() {
        if (hasLocationPermission()) goToMyLocation()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // Very first launch, nothing pinned: offer to centre on the user's location. Only ever once, so a
    // rebuilt screen never jumps the map to where you are.
    LaunchedEffect(Unit) {
        if (!vm.offeredMyLocation) {
            vm.offeredMyLocation = true
            if (state.pin == null && vm.lastCamera == null) myLocationClicked()
        }
    }

    // ---- Map lifecycle + events ----
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    map.mapView.onResume()
                    if (hasLocationPermission()) map.myLocation.enableMyLocation()
                    vm.onResume()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    map.mapView.onPause()
                    map.myLocation.disableMyLocation()
                    vm.saveCamera(map.camera())
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.saveCamera(map.camera())
            map.mapView.onDetach()
        }
    }
    LaunchedEffect(Unit) {
        vm.cameraRequests.collect { req ->
            map.mapView.controller.animateTo(GeoPoint(req.target.lat, req.target.lon), req.zoom, 800L)
        }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // When a pin is placed, move it to the base of the clear map area so its chart fits beside the panels.
    // Opening the table later doesn't move or resize anything: it covers the map, which can be panned.
    LaunchedEffect(state.pin) {
        val pin = state.pin ?: return@LaunchedEffect
        delay(400) // let the pin panel lay out first
        map.keepAbovePanels(pin)
    }

    // ---- Derived wind data for the selected time + altitudes ----
    // Keyed on everything buildProfiles reads, including which model is isolated.
    val profiles = remember(state.pinForecasts, state.selectedEpoch, state.altRangeK, s, state.isolatedModel) { buildProfiles(state) }
    val columns = remember(profiles, s.comparing, state.isolatedModel) { buildColumns(state, profiles) }

    // ---- Panel pieces, arranged below for portrait or landscape ----
    val searchBar = @Composable { modifier: Modifier ->
        SearchBar(
            results = state.searchResults,
            onSearch = vm::search,
            onPick = vm::selectPlace,
            onClearResults = vm::clearSearchResults,
            onSettings = { showSettings = true },
            onPlaces = { showPlaces = true },
            modifier = modifier.coversMap(map, "search"),
        )
    }
    val compass = @Composable { modifier: Modifier ->
        CompassButton(rotation = mapRotation, onClick = map::resetNorth, modifier = modifier)
    }
    val fabs = @Composable {
        SmallFloatingActionButton(onClick = { setPanelMode(PanelMode.TIME_ONLY) }) {
            Icon(AppIcons.Fullscreen, contentDescription = "Hide panels (keep the time slider)")
        }
        if (state.pin != null) SmallFloatingActionButton(onClick = { showTwoDay = true }) {
            Text("48h", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        if (state.showingSeveral) SmallFloatingActionButton(onClick = vm::cycleChartStyle) {
            Text(
                when (s.chartStyle) { ChartStyle.FULL -> "Full"; ChartStyle.SLIM -> "Slim"; ChartStyle.ONE_LINE -> "1 line"; ChartStyle.CONSENSUS -> "Avg" },
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            )
        }
        Box {
            SmallFloatingActionButton(onClick = { layerMenu = true }) { Icon(AppIcons.Layers, contentDescription = "Change map style") }
            DropdownMenu(layerMenu, onDismissRequest = { layerMenu = false }) {
                MapLayer.entries.forEach { l ->
                    DropdownMenuItem(
                        text = { Text(l.label + (l.region?.let { " ($it)" } ?: ""), fontWeight = if (l == s.layer) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { layerMenu = false; vm.updateSettings(s.copy(layer = l)) },
                    )
                }
            }
        }
        SmallFloatingActionButton(onClick = vm::refresh) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh forecast")
        }
        SmallFloatingActionButton(onClick = ::myLocationClicked) { Icon(AppIcons.MyLocation, contentDescription = "My location") }
    }
    val pending = @Composable {
        state.pendingPin?.let { p ->
            ConfirmSpotCard(location = p, onConfirm = vm::confirmPendingPin, onCancel = vm::cancelPendingPin, modifier = Modifier.fillMaxWidth())
        }
    }
    val pinAndControls = @Composable {
        if (state.pin != null && pinPanelHidden) {
            // ✕ only hides the panel: the spot, its chart and its forecast stay. Tap the pill to bring it back.
            PinPill(state, onOpen = { pinPanelHidden = false }, modifier = Modifier.coversMap(map, "pin"))
        } else if (state.pin != null) {
            PinPanel(
                state = state,
                profiles = profiles,
                expanded = profileExpanded,
                onToggle = { profileExpanded = !profileExpanded },
                onClose = { pinPanelHidden = true; profileExpanded = false },
                onReattach = if (state.chartAt != null) ({ vm.setChartAt(null) }) else null,
                onToggleFavorite = { vm.toggleFavorite() },
                modifier = Modifier.fillMaxWidth().coversMap(map, "pin"),
                onRemove = vm::clearPin,
            )
        } else {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.9f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ) {
                Text(
                    "Long-press the map to pick a forecast spot",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }
        ControlsCard(
            state = state,
            attribution = s.layer.attribution,
            onHourOffset = vm::setHourOffset,
            onAltRange = vm::setAltRange,
            modifier = Modifier.fillMaxWidth().coversMap(map, "controls"),
            onModelTapped = vm::toggleIsolate,
        )
    }
    val timeOnly = @Composable { modifier: Modifier ->
        Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeOnlyCard(state, vm::setHourOffset, Modifier.weight(1f).coversMap(map, "time"))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallFloatingActionButton(onClick = { setPanelMode(PanelMode.FULL) }) {
                    Icon(AppIcons.FullscreenExit, contentDescription = "Show all panels")
                }
                SmallFloatingActionButton(onClick = { setPanelMode(PanelMode.HIDDEN) }) {
                    Icon(AppIcons.Fullscreen, contentDescription = "Hide the time slider too")
                }
            }
        }
    }
    val showPanelsButton = @Composable { modifier: Modifier ->
        ExtendedFloatingActionButton(
            onClick = { setPanelMode(PanelMode.FULL) },
            icon = { Icon(AppIcons.FullscreenExit, contentDescription = null) },
            text = { Text("Show panels") },
            modifier = modifier,
        )
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { map.mapView },
            modifier = Modifier.fillMaxSize(),
            update = {
                map.setLayer(s.layer)
                map.chartTurn = chartTurn
                map.windOverlay.location = state.pin
                map.windOverlay.chartLocation = state.chartAt
                map.windOverlay.pendingLocation = state.pendingPin
                map.windOverlay.columns = columns
                map.windOverlay.ceilingFt = s.ceilingFt
                map.windOverlay.layout = s.arrowLayout
                map.windOverlay.colorMode = s.arrowColor
                map.windOverlay.showTemperature = s.showTemperature
                map.windOverlay.showClouds = s.showClouds
                map.windOverlay.anchor = s.arrowAnchor
                map.windOverlay.showDirection = s.showDirection
                map.windOverlay.showSpeed = s.showSpeed
                map.windOverlay.style = s.chartStyle
                map.windOverlay.backdropAlpha = s.backdropPct / 100f
                map.windOverlay.showShear = s.showShear
                map.windOverlay.unitLabel = s.unit.label
                map.windOverlay.onModelTapped = vm::toggleIsolate
                map.windOverlay.glyph = s.glyph
                map.windOverlay.altitudesFt = state.altitudesFt
                map.windOverlay.speedLabel = { ms -> Format.speed(ms, s.unit) }
                map.windOverlay.tempLabel = { c -> s.tempUnit.format(c) }
                map.metarOverlay.stations = if (s.showMetars) state.metars else emptyList()
                map.metarOverlay.speedLabel = { ms -> Format.speed(ms, s.unit) }
                it.invalidate()
            },
        )

        if (landscape) {
            // Sideways: panels in a column down one side, leaving the long side of the map for the chart.
            // The panel sits on the side the chart starts from, so the chart runs out across the open map.
            val panelAtStart = chartTurn == 90
            val panelWidth = 380.dp
            Row(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val side = @Composable {
                    Column(Modifier.width(panelWidth).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (panelMode == PanelMode.FULL) searchBar(Modifier.fillMaxWidth())
                        Spacer(Modifier.weight(1f))
                        when (panelMode) {
                            PanelMode.FULL -> Column(
                                Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) { pending(); pinAndControls() }
                            PanelMode.TIME_ONLY -> { pending(); timeOnly(Modifier.fillMaxWidth()) }
                            PanelMode.HIDDEN -> { pending(); showPanelsButton(Modifier) }
                        }
                    }
                }
                val buttons = @Composable {
                    Column(Modifier.fillMaxHeight().coversMap(map, "buttons"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        compass(Modifier)
                        Spacer(Modifier.weight(1f))
                        if (panelMode == PanelMode.FULL) fabs()
                    }
                }
                if (panelAtStart) { side(); buttons(); Spacer(Modifier.weight(1f)) }
                else { Spacer(Modifier.weight(1f)); buttons(); side() }
            }
        } else {
            if (panelMode == PanelMode.FULL) searchBar(
                Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(),
            )

            // Bottom: buttons, pinned profile, controls
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pending()
                when (panelMode) {
                    PanelMode.HIDDEN -> showPanelsButton(Modifier.align(Alignment.End))
                    // Just the forecast-time slider, so the map stays free while stepping through hours.
                    PanelMode.TIME_ONLY -> timeOnly(Modifier)
                    PanelMode.FULL -> {
                        Column(
                            Modifier.align(Alignment.End),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.End,
                        ) { fabs() }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) { pinAndControls() }
                    }
                }
            }

            compass(
                Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(end = 16.dp, top = if (panelMode == PanelMode.FULL) 72.dp else 12.dp),
            )
        }

        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 64.dp)
                .widthIn(max = 560.dp),
        )
    }

    if (showSettings) {
        val offlineInfo = remember {
            val tiles = java.io.File(context.filesDir, "osmdroid/tiles/cache.db").length()
            val winds = java.io.File(context.filesDir, "winds").listFiles()?.sumOf { it.length() } ?: 0L
            "Saved on this phone: map tiles %.0f MB (up to 1 GB), winds %.1f MB.".format(tiles / 1e6, winds / 1e6)
        }
        SettingsScreen(
            settings = s, onChange = vm::updateSettings, onDismiss = { showSettings = false },
            previewColumns = columns, previewAltitudes = state.altitudesFt, offlineInfo = offlineInfo,
            onUpdateSaved = { vm.updateSavedPlaces(force = true) },
        )
    }
    if (showTwoDay && state.pin != null) {
        TwoDayScreen(state = state, onDismiss = { showTwoDay = false }, onAltRange = vm::setAltRange)
    }
    if (showPlaces) {
        PlacesDialog(
            state = state,
            onGo = { vm.goToPlace(it); showPlaces = false },
            onToggleFavorite = vm::toggleFavorite,
            onRename = vm::renameFavorite,
            onRemoveRecent = vm::removeRecent,
            onClearRecents = vm::clearRecents,
            onSaveAllOffline = { vm.updateSavedPlaces(force = true) },
            onDismiss = { showPlaces = false },
        )
    }
}
