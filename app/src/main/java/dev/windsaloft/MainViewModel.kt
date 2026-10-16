package dev.windsaloft

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.annotation.VisibleForTesting
import dev.windsaloft.data.Camera
import dev.windsaloft.data.Connectivity
import dev.windsaloft.data.Geocoding
import dev.windsaloft.data.Http
import dev.windsaloft.data.Metar
import dev.windsaloft.data.Metars
import dev.windsaloft.data.OpenMeteo
import dev.windsaloft.data.Place
import dev.windsaloft.data.Prefs
import dev.windsaloft.data.SavedPlace
import dev.windsaloft.data.Settings
import dev.windsaloft.data.WindCache
import dev.windsaloft.wind.ForecastPoint
import dev.windsaloft.wind.LatLon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val settings: Settings,
    val pin: LatLon? = null,
    val pinLabel: String? = null,
    /** Pin forecasts keyed by model id. */
    val pinForecasts: Map<String, ForecastPoint> = emptyMap(),
    val pinFetchedAtMs: Long = 0L,
    val pinLoading: Boolean = false,
    val nowHourEpoch: Long = currentHourEpoch(),
    /** Hours after the current hour. */
    val hourOffset: Int = 0,
    /** Selected altitude band, in thousands of feet. */
    val altRangeK: IntRange = 0..14,
    val searchResults: List<Place> = emptyList(),
    /** A spot the user long-pressed, waiting for them to confirm before fetching its forecast. */
    val pendingPin: LatLon? = null,
    /** Where the wind chart is drawn when dragged away from the forecast spot (null = on the spot). */
    val chartAt: LatLon? = null,
    val favorites: List<SavedPlace> = emptyList(),
    /** The last [SavedPlace.MAX_RECENTS] spots looked at, newest first. */
    val recents: List<SavedPlace> = emptyList(),
    /** Connected to the internet (not just to a network: a plane's captive Wi-Fi counts as offline). */
    val online: Boolean = true,
    /** When each place's winds were last downloaded (any selected model), keyed by [WindCache.spotKey]. */
    val savedWindTimes: Map<String, Long> = emptyMap(),
    /** Progress while downloading winds for every saved place: done to total. */
    val savingPlaces: Pair<Int, Int>? = null,
    /** Latest reports from airports near the pin, nearest first. */
    val metars: List<Metar> = emptyList(),
    /** When [metars] were downloaded (0 = never). */
    val metarsAtMs: Long = 0L,
) {
    val selectedEpoch: Long get() = nowHourEpoch + hourOffset * 3600L

    val primaryPinForecast: ForecastPoint? get() = pinForecasts[settings.primaryModelId]

    val maxHourOffset: Int
        get() {
            val last = pinForecasts.values.maxOfOrNull { it.lastEpochSec } ?: return 72
            return ((last - nowHourEpoch) / 3600L).toInt().coerceIn(0, 120)
        }

    val altitudesFt: List<Int> get() = altRangeK.map { it * 1000 }

    val pinFavorite: SavedPlace? get() = pin?.let { p -> favorites.firstOrNull { it.sameSpot(p) } }

    fun savedWindsAt(at: LatLon): Long? = savedWindTimes[WindCache.spotKey(at)]
}

data class CameraRequest(val target: LatLon, val zoom: Double?)

fun currentHourEpoch(): Long = System.currentTimeMillis() / 1000L / 3600L * 3600L

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val cache = WindCache(File(app.filesDir, "winds"))
    private val net = Connectivity(app)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _camera = MutableSharedFlow<CameraRequest>(extraBufferCapacity = 2)
    val cameraRequests: SharedFlow<CameraRequest> = _camera.asSharedFlow()

    /**
     * Where the map was last left. Kept up to date (not just read at start-up), so when the screen is
     * rebuilt the map comes back exactly where it was instead of jumping somewhere else.
     */
    var lastCamera: Camera? = prefs.loadCamera()
        private set

    /** The first-launch "go to my location" happens once, not again every time the screen is rebuilt. */
    var offeredMyLocation = false

    private var pinJob: Job? = null
    private var placesJob: Job? = null
    private var metarJob: Job? = null

    init {
        Http.userAgent = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME} (Android)"
        app.filesDir.listFiles { f -> f.name.startsWith("pin_") }?.forEach { it.delete() } // old single-spot cache
        _state.update { it.copy(online = net.isOnline()) }
        net.watch { online -> viewModelScope.launch { onConnectivity(online) } }
        refreshSavedTimes()
        _state.value.pin?.let { loadPin(it, announceOffline = true) }
        if (_state.value.online) updateSavedPlaces(force = false)
    }

    private fun initialState(): UiState {
        val settings = prefs.loadSettings()
        val pin = prefs.loadPin()
        val ceilK = settings.ceilingFt / 1000
        val alt = prefs.loadAltRange()
        return UiState(
            settings = settings,
            pin = pin?.first,
            pinLabel = pin?.second,
            altRangeK = alt.first.coerceIn(0, ceilK)..alt.last.coerceIn(0, ceilK),
            favorites = prefs.loadFavorites(),
            recents = prefs.loadRecents(),
        )
    }

    private fun onConnectivity(online: Boolean) {
        val was = _state.value.online
        if (online == was) return
        _state.update { it.copy(online = online) }
        if (!online) return
        // Back online: freshen the current spot if its winds are getting old, then the saved places.
        val s = _state.value
        if (s.pin != null && (s.pinForecasts.isEmpty() || System.currentTimeMillis() - s.pinFetchedAtMs > 15 * 60_000L)) fetchPin()
        if (s.pin != null && System.currentTimeMillis() - s.metarsAtMs > 10 * 60_000L) loadMetars(s.pin)
        updateSavedPlaces(force = false)
    }

    // ---- Pin (detailed profile) ----

    fun setPin(location: LatLon, label: String? = null) {
        val name = label ?: _state.value.favorites.firstOrNull { it.sameSpot(location) }?.name
        _state.update {
            it.copy(
                pin = location, pinLabel = name, pinForecasts = emptyMap(), pinFetchedAtMs = 0L, metars = emptyList(), metarsAtMs = 0L,
                searchResults = emptyList(), pendingPin = null, chartAt = null,
                recents = SavedPlace.pushRecent(it.recents, SavedPlace(name, location, System.currentTimeMillis())),
            )
        }
        prefs.savePin(location, name)
        prefs.saveRecents(_state.value.recents)
        pruneCache()
        loadPin(location, announceOffline = true)
    }

    /**
     * Airport reports near [pin]: the last saved ones first (still useful in the plane), then fresh
     * ones when online. Looks further afield when nothing is close.
     */
    private fun loadMetars(pin: LatLon) {
        if (!_state.value.settings.showMetars) return
        metarJob?.cancel()
        metarJob = viewModelScope.launch {
            withContext(Dispatchers.IO) { cache.load(pin, METAR_KEY) }?.let { e ->
                val saved = runCatching { Metars.parse(e.json, pin) }.getOrDefault(emptyList())
                _state.update { if (it.pin == pin && it.metarsAtMs < e.fetchedAtMs) it.copy(metars = saved, metarsAtMs = e.fetchedAtMs) else it }
            }
            if (!net.isOnline()) return@launch
            try {
                for (radius in listOf(0.6, 1.5)) {
                    val json = Http.get(Metars.url(pin, radius))
                    val list = withContext(Dispatchers.Default) { Metars.parse(json, pin) }
                    if (list.isEmpty() && radius < 1.5) continue
                    val now = System.currentTimeMillis()
                    withContext(Dispatchers.IO) { cache.save(pin, METAR_KEY, json, now, 0) }
                    _state.update { if (it.pin == pin) it.copy(metars = list, metarsAtMs = now) else it }
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Observations are extra; the forecast is what matters, so stay quiet on failure.
            }
        }
    }

    /** Shows the saved winds for [pin] straight away (if any), then downloads fresh ones when online. */
    private fun loadPin(pin: LatLon, announceOffline: Boolean) {
        loadMetars(pin)
        pinJob?.cancel()
        pinJob = viewModelScope.launch {
            val models = _state.value.settings.modelIds
            var newest = 0L
            val saved = withContext(Dispatchers.IO) {
                models.mapNotNull { model ->
                    val e = cache.load(pin, model) ?: return@mapNotNull null
                    runCatching { OpenMeteo.parse(e.json, listOf(pin)).first() }.getOrNull()
                        ?.let { newest = maxOf(newest, e.fetchedAtMs); model to it }
                }.toMap()
            }
            if (_state.value.pin != pin) return@launch
            if (saved.isNotEmpty()) _state.update { it.copy(pinForecasts = saved, pinFetchedAtMs = newest) }
            if (net.isOnline()) {
                fetchPinNow(pin)
            } else {
                _state.update { it.copy(online = false) }
                if (announceOffline) _messages.tryEmit(
                    if (saved.isEmpty()) "Offline, and no saved winds for this spot"
                    else "Offline: showing winds downloaded ${dev.windsaloft.ui.Format.age(newest)}"
                )
            }
        }
    }

    fun proposePin(location: LatLon) = _state.update { it.copy(pendingPin = location) }

    fun cancelPendingPin() = _state.update { it.copy(pendingPin = null) }

    fun confirmPendingPin() {
        val p = _state.value.pendingPin ?: return
        setPin(p)
    }

    /** Moves only the chart; the forecast spot (and its data) stay put. Null re-attaches it. */
    fun setChartAt(location: LatLon?) = _state.update { it.copy(chartAt = location) }

    fun clearPin() {
        pinJob?.cancel()
        _state.update { it.copy(pin = null, pinLabel = null, pinForecasts = emptyMap(), pinLoading = false) }
        prefs.savePin(null, null)
    }

    private fun fetchPin() {
        val pin = _state.value.pin ?: return
        pinJob?.cancel()
        pinJob = viewModelScope.launch { fetchPinNow(pin) }
    }

    /** Fetches every selected model for the pin in parallel; one model failing doesn't affect the others. */
    private suspend fun fetchPinNow(pin: LatLon) {
        val s = _state.value
        val models = s.settings.modelIds
        val ceiling = s.settings.ceilingFt
        _state.update { st -> st.copy(pinLoading = true, pinForecasts = st.pinForecasts.filterKeys { it in models }) }
        try {
            coroutineScope {
                models.forEach { model ->
                    launch {
                        try {
                            val fp = download(pin, model, ceiling)
                            _state.update {
                                if (it.pin != pin) it
                                else it.copy(pinForecasts = it.pinForecasts + (model to fp), pinFetchedAtMs = System.currentTimeMillis(), nowHourEpoch = currentHourEpoch())
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            _messages.tryEmit("${OpenMeteo.label(model)}: ${e.message}")
                        }
                    }
                }
            }
        } finally {
            if (currentCoroutineContext().isActive) _state.update { it.copy(pinLoading = false) }
            refreshSavedTimes()
        }
    }

    /** Downloads one model's forecast for a spot and keeps the raw response for offline use. */
    private suspend fun download(at: LatLon, model: String, ceilingFt: Int): ForecastPoint {
        val json = Http.get(OpenMeteo.forecastUrl(listOf(at), model, ceilingFt, includeTemperature = true))
        val fp = withContext(Dispatchers.Default) { OpenMeteo.parse(json, listOf(at)).first() }
        withContext(Dispatchers.IO) { cache.save(at, model, json, System.currentTimeMillis(), ceilingFt) }
        return fp
    }

    // ---- Saved places + history ----

    /** Saves [place] (default: the current spot) as a favourite, or removes it if it already is one. */
    fun toggleFavorite(place: SavedPlace? = null) {
        val s = _state.value
        val target = place ?: SavedPlace(s.pinLabel, s.pin ?: return, System.currentTimeMillis())
        val existing = s.favorites.firstOrNull { it.sameSpot(target.location) }
        val list = if (existing != null) s.favorites - existing
        else listOf(target.copy(atMs = System.currentTimeMillis())) + s.favorites
        setFavorites(list)
        if (existing == null) updateSavedPlaces(force = false)
        _messages.tryEmit(if (existing != null) "Removed from saved places" else "Saved. Its winds are kept ready for offline use")
    }

    fun renameFavorite(place: SavedPlace, name: String) {
        val n = name.trim().ifEmpty { null }
        setFavorites(_state.value.favorites.map { if (it == place) it.copy(name = n) else it })
        if (_state.value.pin?.let { place.sameSpot(it) } == true) {
            _state.update { it.copy(pinLabel = n) }
            prefs.savePin(_state.value.pin, n)
        }
    }

    fun removeFavorite(place: SavedPlace) = setFavorites(_state.value.favorites - place)

    private fun setFavorites(list: List<SavedPlace>) {
        _state.update { it.copy(favorites = list) }
        prefs.saveFavorites(list)
        pruneCache()
    }

    fun removeRecent(place: SavedPlace) {
        _state.update { it.copy(recents = it.recents - place) }
        prefs.saveRecents(_state.value.recents)
        pruneCache()
    }

    fun clearRecents() {
        _state.update { it.copy(recents = emptyList()) }
        prefs.saveRecents(emptyList())
        pruneCache()
    }

    fun goToPlace(place: SavedPlace) {
        setPin(place.location, place.name)
        moveCamera(place.location, 11.0)
    }

    /**
     * Downloads every selected model for each saved place, so they're all ready offline. Without
     * [force], only places whose winds are over an hour old are fetched.
     */
    fun updateSavedPlaces(force: Boolean) {
        if (placesJob?.isActive == true) return
        val s = _state.value
        val models = s.settings.modelIds
        val now = System.currentTimeMillis()
        val todo = s.favorites.flatMap { p -> models.map { p.location to it } }
            .filter { (at, m) -> force || (cache.fetchedAt(at, m) ?: 0L) < now - 60 * 60_000L }
        if (todo.isEmpty()) {
            if (force) _messages.tryEmit(if (s.favorites.isEmpty()) "No saved places yet" else "Saved places are up to date")
            return
        }
        if (!net.isOnline()) {
            if (force) _messages.tryEmit("Offline: saved places will update when you're connected")
            return
        }
        placesJob = viewModelScope.launch {
            var failed = 0
            todo.forEachIndexed { i, (at, model) ->
                _state.update { it.copy(savingPlaces = i to todo.size) }
                try {
                    val fp = download(at, model, s.settings.ceilingFt)
                    // Keep the on-screen spot in step if it's one of these.
                    _state.update { if (it.pin == at) it.copy(pinForecasts = it.pinForecasts + (model to fp)) else it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                }
            }
            _state.update { it.copy(savingPlaces = null) }
            refreshSavedTimes()
            if (force || failed > 0) _messages.tryEmit(
                if (failed == 0) "Winds saved for ${s.favorites.size} place${if (s.favorites.size == 1) "" else "s"}"
                else "Couldn't download $failed of ${todo.size} forecasts"
            )
        }
    }

    private fun pruneCache() {
        val s = _state.value
        val keep = (s.favorites + s.recents).map { it.location } + listOfNotNull(s.pin)
        viewModelScope.launch(Dispatchers.IO) { cache.prune(keep) }
        refreshSavedTimes()
    }

    private fun refreshSavedTimes() {
        viewModelScope.launch {
            val s = _state.value
            val spots = (s.favorites + s.recents).map { it.location } + listOfNotNull(s.pin)
            val times = withContext(Dispatchers.IO) {
                spots.mapNotNull { at ->
                    s.settings.modelIds.mapNotNull { cache.fetchedAt(at, it) }.maxOrNull()?.let { WindCache.spotKey(at) to it }
                }.toMap()
            }
            _state.update { it.copy(savedWindTimes = times) }
        }
    }

    private companion object {
        /** Airport reports are cached beside the winds, under this pseudo-model name. */
        const val METAR_KEY = "metar"
    }

    @VisibleForTesting
    internal fun replaceStateForTest(transform: (UiState) -> UiState) = _state.update(transform)

    fun refresh() {
        _state.update { it.copy(nowHourEpoch = currentHourEpoch(), online = net.isOnline()) }
        if (!_state.value.online) { _messages.tryEmit("Offline: can't download new winds right now"); return }
        fetchPin()
        _state.value.pin?.let { loadMetars(it) }
    }

    /** Called when the app returns to the foreground so "now" stays current. */
    fun onResume() {
        val now = currentHourEpoch()
        if (now != _state.value.nowHourEpoch) _state.update { it.copy(nowHourEpoch = now) }
        val s = _state.value
        if (s.pin != null && System.currentTimeMillis() - s.metarsAtMs > 10 * 60_000L) loadMetars(s.pin)
    }

    // ---- Controls ----

    fun setHourOffset(h: Int) = _state.update { it.copy(hourOffset = h.coerceIn(0, it.maxHourOffset)) }

    fun setAltRange(range: IntRange) {
        _state.update { it.copy(altRangeK = range) }
        prefs.saveAltRange(range)
    }

    fun updateSettings(new: Settings) {
        val old = _state.value.settings
        if (new == old) return
        prefs.saveSettings(new)
        val ceilK = new.ceilingFt / 1000
        _state.update {
            val r = it.altRangeK
            it.copy(settings = new, altRangeK = r.first.coerceAtMost(ceilK)..r.last.coerceAtMost(ceilK))
        }
        prefs.saveAltRange(_state.value.altRangeK)
        if (new.showMetars && !old.showMetars) _state.value.pin?.let { loadMetars(it) }
        if (old.modelIds != new.modelIds || old.ceilingFt != new.ceilingFt) {
            _state.value.pin?.let { loadPin(it, announceOffline = false) }
            refreshSavedTimes()
        }
    }

    fun saveCamera(c: Camera) {
        lastCamera = c
        prefs.saveCamera(c)
    }

    fun moveCamera(target: LatLon, zoom: Double?) {
        _camera.tryEmit(CameraRequest(target, zoom))
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    // ---- Search ----

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        Geocoding.parseCoordinates(q)?.let { selectPlace(Place(q, "", it)); return }
        viewModelScope.launch {
            try {
                val results = Geocoding.search(q)
                if (results.isEmpty()) _messages.tryEmit("No places found for \"$q\"")
                _state.update { it.copy(searchResults = results) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit("Search failed: ${e.message}")
            }
        }
    }

    fun clearSearchResults() = _state.update { it.copy(searchResults = emptyList()) }

    fun selectPlace(place: Place) {
        setPin(place.location, place.name)
        moveCamera(place.location, 10.0)
    }
}
