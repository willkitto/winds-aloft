package dev.windsaloft.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
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
import dev.windsaloft.map.WindFan
import dev.windsaloft.map.attribution
import dev.windsaloft.wind.LatLon
import org.osmdroid.util.GeoPoint

@Composable
fun WindsAloftApp(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val map = remember { MapHandle(context, vm.initialCamera) }
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var profileExpanded by rememberSaveable { mutableStateOf(true) }

    map.onTap = { vm.setPin(it) }
    map.onViewport = vm::onViewportChanged

    // ---- Location ----
    fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun goToMyLocation() {
        map.myLocation.enableMyLocation()
        val fix = map.myLocation.myLocation
        if (fix != null) {
            vm.setPin(LatLon(fix.latitude, fix.longitude), "My location")
            vm.moveCamera(LatLon(fix.latitude, fix.longitude), 10.0)
        } else {
            vm.message("Waiting for a location fix…")
            map.myLocation.runOnFirstFix {
                val l = map.myLocation.myLocation ?: return@runOnFirstFix
                map.mapView.post {
                    vm.setPin(LatLon(l.latitude, l.longitude), "My location")
                    vm.moveCamera(LatLon(l.latitude, l.longitude), 10.0)
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) goToMyLocation() else vm.message("Location permission denied")
    }

    // First launch with nothing pinned: offer to centre on the user's location.
    LaunchedEffect(Unit) {
        if (state.pin == null && vm.initialCamera == null) {
            if (hasLocationPermission()) goToMyLocation()
            else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
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
            map.mapView.onDetach()
        }
    }
    LaunchedEffect(Unit) {
        vm.cameraRequests.collect { req ->
            map.mapView.controller.animateTo(GeoPoint(req.target.lat, req.target.lon), req.zoom, 800L)
        }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // ---- Derived wind data for the selected time + altitudes ----
    val s = state.settings
    val altitudes = state.altitudesFt
    val epoch = state.selectedEpoch
    val pinWinds = remember(state.pinForecast, epoch, altitudes, s.altitudeRef) {
        val fp = state.pinForecast ?: return@remember emptyList()
        altitudes.zip(fp.windsAt(epoch, altitudes, s.altitudeRef))
    }
    val fans = remember(state.grid, pinWinds, state.pin, epoch, altitudes, s.altitudeRef) {
        val grid = state.grid.map { WindFan(it.location, it.windsAt(epoch, altitudes, s.altitudeRef).filterNotNull(), false) }
        val pin = state.pin?.let { p -> WindFan(p, pinWinds.mapNotNull { it.second }, true) }
        grid + listOfNotNull(pin)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { map.mapView },
            modifier = Modifier.fillMaxSize(),
            update = {
                map.setLayer(s.layer)
                map.windOverlay.fans = fans
                map.windOverlay.ceilingFt = s.ceilingFt
                it.invalidate()
            },
        )

        // Top: search
        SearchBar(
            results = state.searchResults,
            onSearch = vm::search,
            onPick = vm::selectPlace,
            onClearResults = vm::clearSearchResults,
            onSettings = { showSettings = true },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .onGloballyPositioned { map.obscuredTopPx = it.boundsInRoot().bottom.toInt() },
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
            Column(
                Modifier.align(Alignment.End),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                SmallFloatingActionButton(onClick = {
                    val next = MapLayer.entries[(s.layer.ordinal + 1) % MapLayer.entries.size]
                    vm.updateSettings(s.copy(layer = next))
                    vm.message("Map: ${next.label}")
                }) { Icon(AppIcons.Layers, contentDescription = "Change map style") }
                SmallFloatingActionButton(onClick = vm::refresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh forecast")
                }
                SmallFloatingActionButton(onClick = {
                    if (hasLocationPermission()) goToMyLocation()
                    else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Icon(AppIcons.MyLocation, contentDescription = "My location") }
            }
            if (state.pin != null) {
                PinPanel(
                    state = state,
                    winds = pinWinds,
                    expanded = profileExpanded,
                    onToggle = { profileExpanded = !profileExpanded },
                    onClose = vm::clearPin,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    "Tap the map to see the full wind profile for that spot",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(4.dp),
                )
            }
            ControlsCard(
                state = state,
                attribution = s.layer.attribution,
                onHourOffset = vm::setHourOffset,
                onAltRange = vm::setAltRange,
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        val rootHeight = map.mapView.height
                        if (rootHeight > 0) {
                            val newBottom = (rootHeight - coords.boundsInRoot().top).toInt()
                            if (newBottom != map.obscuredBottomPx) {
                                map.obscuredBottomPx = newBottom
                                map.reportViewport()
                            }
                        }
                    },
            )
        }

        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 64.dp),
        )
    }

    if (showSettings) {
        SettingsDialog(settings = s, onChange = vm::updateSettings, onDismiss = { showSettings = false })
    }
}
