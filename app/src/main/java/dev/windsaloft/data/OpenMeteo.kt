package dev.windsaloft.data

import dev.windsaloft.wind.CloudSample
import dev.windsaloft.wind.ForecastPoint
import dev.windsaloft.wind.LatLon
import dev.windsaloft.wind.RawSample
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale

data class WeatherModel(val id: String, val label: String, val short: String)

class ApiException(message: String) : Exception(message)

/** Builds requests for, and parses responses from, the Open-Meteo forecast API (https://open-meteo.com). */
object OpenMeteo {
    private const val BASE = "https://api.open-meteo.com/v1/forecast"

    /** Models with upper-air (pressure-level) winds. Note ECMWF's 9 km "ecmwf_ifs" has none, so it's not listed. */
    val MODELS = listOf(
        WeatherModel("best_match", "Best match (auto)", "Best"),
        WeatherModel("ncep_gfs_seamless", "NOAA GFS + HRRR", "GFS"),
        WeatherModel("ncep_hrrr_conus", "NOAA HRRR (US only, ~2 days)", "HRRR"),
        WeatherModel("ecmwf_ifs025", "ECMWF IFS 0.25°", "ECMWF"),
        WeatherModel("icon_seamless", "DWD ICON", "ICON"),
        WeatherModel("gem_seamless", "Canada GEM", "GEM"),
        WeatherModel("ukmo_seamless", "UK Met Office", "UKMO"),
    )

    fun label(id: String): String = MODELS.firstOrNull { it.id == id }?.label ?: id

    fun short(id: String): String = MODELS.firstOrNull { it.id == id }?.short ?: id

    /** Pressure levels (hPa) paired with their standard-atmosphere height in feet. */
    private val PRESSURE_LEVELS = listOf(
        1000 to 364, 975 to 1060, 950 to 1773, 925 to 2500, 900 to 3243, 850 to 4781,
        800 to 6394, 750 to 8091, 700 to 9882, 650 to 11780, 600 to 13801, 550 to 15962,
        500 to 18289, 450 to 20812, 400 to 23574, 350 to 26631, 300 to 30065, 250 to 33999,
    )

    /** Near-surface wind heights (m AGL) and the temperature height paired with each. */
    private val SURFACE_LEVELS = listOf(10 to 2, 80 to 80, 120 to 120, 180 to 180)

    const val FORECAST_DAYS = 4

    /** Pressure levels needed to cover up to [ceilingFt], including one level above it for interpolation. */
    fun levelsFor(ceilingFt: Int): List<Int> {
        val out = mutableListOf<Int>()
        for ((hpa, stdFt) in PRESSURE_LEVELS) {
            out += hpa
            if (stdFt > ceilingFt + 1500) break
        }
        return out
    }

    fun hourlyVariables(ceilingFt: Int, includeTemperature: Boolean): List<String> = buildList {
        for ((h, t) in SURFACE_LEVELS) {
            add("wind_speed_${h}m")
            add("wind_direction_${h}m")
            if (includeTemperature) add("temperature_${t}m")
        }
        for (p in levelsFor(ceilingFt)) {
            add("wind_speed_${p}hPa")
            add("wind_direction_${p}hPa")
            add("geopotential_height_${p}hPa")
            add("cloud_cover_${p}hPa")
            if (includeTemperature) add("temperature_${p}hPa")
        }
    }

    fun forecastUrl(points: List<LatLon>, modelId: String, ceilingFt: Int, includeTemperature: Boolean): String {
        fun fmt(d: Double) = String.format(Locale.US, "%.4f", d)
        return BASE +
            "?latitude=" + points.joinToString(",") { fmt(it.lat) } +
            "&longitude=" + points.joinToString(",") { fmt(it.lon) } +
            "&hourly=" + hourlyVariables(ceilingFt, includeTemperature).joinToString(",") +
            "&models=" + modelId +
            "&wind_speed_unit=ms&timeformat=unixtime&forecast_days=$FORECAST_DAYS"
    }

    /**
     * Parses a forecast response. [requested] are the points in the request, in order; the returned
     * forecasts use them (rather than the model grid cell centre) as their location.
     */
    fun parse(json: String, requested: List<LatLon>): List<ForecastPoint> {
        val root = Json.parseToJsonElement(json)
        if (root is JsonObject && root["error"]?.jsonPrimitive?.booleanOrNull == true) {
            throw ApiException(root["reason"]?.jsonPrimitive?.contentOrNull ?: "Unknown API error")
        }
        val objects = when (root) {
            is JsonArray -> root.map { it.jsonObject }
            is JsonObject -> listOf(root)
            else -> throw ApiException("Unexpected response")
        }
        return objects.mapIndexed { i, obj ->
            val loc = requested.getOrNull(i) ?: LatLon(obj.num("latitude") ?: 0.0, obj.num("longitude") ?: 0.0)
            parseLocation(obj, loc)
        }
    }

    private fun parseLocation(obj: JsonObject, location: LatLon): ForecastPoint {
        val elevation = obj.num("elevation") ?: 0.0
        val hourly = obj["hourly"]?.jsonObject ?: throw ApiException("Response has no hourly data")
        val times = hourly["time"]?.jsonArray?.map { it.jsonPrimitive.longOrNull ?: 0L }?.toLongArray()
            ?: throw ApiException("Response has no times")

        fun series(name: String): DoubleArray? = hourly[name]?.jsonArray?.let { arr ->
            DoubleArray(arr.size) { arr[it].jsonPrimitive.doubleOrNull ?: Double.NaN }
        }

        class Level(val height: DoubleArray?, val fixedHeight: Double, val speed: DoubleArray?, val dir: DoubleArray?, val temp: DoubleArray?)

        val levels = SURFACE_LEVELS.map { (h, t) ->
            Level(null, elevation + h, series("wind_speed_${h}m"), series("wind_direction_${h}m"), series("temperature_${t}m"))
        } + PRESSURE_LEVELS.map { (p, _) ->
            Level(series("geopotential_height_${p}hPa"), Double.NaN, series("wind_speed_${p}hPa"), series("wind_direction_${p}hPa"), series("temperature_${p}hPa"))
        }

        val profiles = times.indices.map { t ->
            val samples = ArrayList<RawSample>(levels.size)
            for (lvl in levels) {
                val speed = lvl.speed?.getOrNull(t) ?: continue
                val dir = lvl.dir?.getOrNull(t) ?: continue
                val h = if (lvl.height != null) lvl.height.getOrNull(t) ?: continue else lvl.fixedHeight
                if (speed.isNaN() || dir.isNaN() || h.isNaN()) continue
                // Pressure levels at or below ground are extrapolated by the model; skip them.
                if (lvl.height != null && h <= elevation + 10.0) continue
                val temp = lvl.temp?.getOrNull(t)?.takeUnless { it.isNaN() }
                samples += RawSample(h, speed, dir, temp)
            }
            samples.sortBy { it.heightM }
            samples
        }
        val cloudLevels = PRESSURE_LEVELS.mapNotNull { (p, _) ->
            val gph = series("geopotential_height_${p}hPa") ?: return@mapNotNull null
            val cc = series("cloud_cover_${p}hPa") ?: return@mapNotNull null
            gph to cc
        }
        val clouds = times.indices.map { t ->
            cloudLevels.mapNotNull { (gph, cc) ->
                val h = gph.getOrNull(t)?.takeUnless { it.isNaN() } ?: return@mapNotNull null
                val pct = cc.getOrNull(t)?.takeUnless { it.isNaN() } ?: return@mapNotNull null
                if (h > elevation) CloudSample(h, pct) else null
            }.sortedBy { it.heightM }
        }
        return ForecastPoint(location, elevation, times, profiles, clouds)
    }

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonElement)?.jsonPrimitive?.doubleOrNull
}
