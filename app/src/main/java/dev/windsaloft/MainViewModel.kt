package dev.windsaloft

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.windsaloft.data.Camera
import dev.windsaloft.data.Geocoding
import dev.windsaloft.data.Http
import dev.windsaloft.data.OpenMeteo
import dev.windsaloft.data.Place
import dev.windsaloft.data.Prefs
import dev.windsaloft.data.Settings
import dev.windsaloft.wind.ForecastPoint
import dev.windsaloft.wind.LatLon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
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
) {
    val selectedEpoch: Long get() = nowHourEpoch + hourOffset * 3600L

    val primaryPinForecast: ForecastPoint? get() = pinForecasts[settings.primaryModelId]

    val maxHourOffset: Int
        get() {
            val last = pinForecasts.values.maxOfOrNull { it.lastEpochSec } ?: return 72
            return ((last - nowHourEpoch) / 3600L).toInt().coerceIn(0, 120)
        }

    val altitudesFt: List<Int> get() = altRangeK.map { it * 1000 }
}

data class CameraRequest(val target: LatLon, val zoom: Double?)

fun currentHourEpoch(): Long = System.currentTimeMillis() / 1000L / 3600L * 3600L

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _camera = MutableSharedFlow<CameraRequest>(extraBufferCapacity = 2)
    val cameraRequests: SharedFlow<CameraRequest> = _camera.asSharedFlow()

    val initialCamera: Camera? = prefs.loadCamera()

    private var pinJob: Job? = null

    init {
        Http.userAgent = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME} (Android)"
        loadPinCache()
        if (_state.value.pin != null) fetchPin()
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
        )
    }

    // ---- Pin (detailed profile) ----

    fun setPin(location: LatLon, label: String? = null) {
        _state.update { it.copy(pin = location, pinLabel = label, pinForecasts = emptyMap(), searchResults = emptyList()) }
        prefs.savePin(location, label)
        fetchPin()
    }

    fun clearPin() {
        pinJob?.cancel()
        _state.update { it.copy(pin = null, pinLabel = null, pinForecasts = emptyMap(), pinLoading = false) }
        prefs.savePin(null, null)
    }

    private fun pinCacheKey(pin: LatLon, ceilingFt: Int) = "$pin|$ceilingFt"

    private fun loadPinCache() {
        val s = _state.value
        val pin = s.pin ?: return
        var newest = 0L
        val loaded = s.settings.modelIds.mapNotNull { model ->
            val (json, time) = prefs.loadPinCache(model, pinCacheKey(pin, s.settings.ceilingFt)) ?: return@mapNotNull null
            runCatching { OpenMeteo.parse(json, listOf(pin)).first() }.getOrNull()
                ?.let { newest = maxOf(newest, time); model to it }
        }.toMap()
        if (loaded.isNotEmpty()) _state.update { it.copy(pinForecasts = loaded, pinFetchedAtMs = newest) }
    }

    /** Fetches every selected model for the pin in parallel; one model failing doesn't affect the others. */
    private fun fetchPin() {
        val s = _state.value
        val pin = s.pin ?: return
        val models = s.settings.modelIds
        val ceiling = s.settings.ceilingFt
        pinJob?.cancel()
        pinJob = viewModelScope.launch {
            _state.update { st -> st.copy(pinLoading = true, pinForecasts = st.pinForecasts.filterKeys { it in models }) }
            try {
                coroutineScope {
                    models.forEach { model ->
                        launch {
                            try {
                                val json = Http.get(OpenMeteo.forecastUrl(listOf(pin), model, ceiling, includeTemperature = true))
                                val fp = withContext(Dispatchers.Default) { OpenMeteo.parse(json, listOf(pin)).first() }
                                val now = System.currentTimeMillis()
                                withContext(Dispatchers.IO) { prefs.savePinCache(model, json, pinCacheKey(pin, ceiling), now) }
                                _state.update {
                                    it.copy(pinForecasts = it.pinForecasts + (model to fp), pinFetchedAtMs = now, nowHourEpoch = currentHourEpoch())
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
                if (isActive) _state.update { it.copy(pinLoading = false) }
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(nowHourEpoch = currentHourEpoch()) }
        fetchPin()
    }

    /** Called when the app returns to the foreground so "now" stays current. */
    fun onResume() {
        val now = currentHourEpoch()
        if (now != _state.value.nowHourEpoch) _state.update { it.copy(nowHourEpoch = now) }
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
        if (old.modelIds != new.modelIds || old.ceilingFt != new.ceilingFt) fetchPin()
    }

    fun saveCamera(c: Camera) = prefs.saveCamera(c)

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
