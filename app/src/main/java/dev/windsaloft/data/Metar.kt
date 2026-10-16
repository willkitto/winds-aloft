package dev.windsaloft.data

import dev.windsaloft.wind.LatLon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One airport weather report (METAR): what the wind is actually doing at the ground now, gusts
 * included. [dirDeg] is null for variable wind ("VRB"); speeds are in knots as reported.
 */
data class Metar(
    val icao: String,
    val name: String,
    val location: LatLon,
    val obsTimeSec: Long,
    val dirDeg: Int?,
    val speedKt: Int,
    val gustKt: Int?,
    val tempC: Double?,
    val raw: String,
) {
    val calm: Boolean get() = speedKt == 0
}

/** Airport reports from aviationweather.gov (NOAA), worldwide, no key needed. */
object Metars {
    /** Reports from the last two hours within [radiusDeg] degrees of latitude of [at]. */
    fun url(at: LatLon, radiusDeg: Double): String {
        val dLon = radiusDeg / cos(Math.toRadians(at.lat)).coerceAtLeast(0.2)
        return String.format(
            Locale.US, "https://aviationweather.gov/api/data/metar?bbox=%.3f,%.3f,%.3f,%.3f&format=json&hours=2",
            at.lat - radiusDeg, at.lon - dLon, at.lat + radiusDeg, at.lon + dLon,
        )
    }

    /** The newest report from each station, nearest to [at] first, at most [max]. */
    fun parse(json: String, at: LatLon, max: Int = 5): List<Metar> {
        if (json.isBlank()) return emptyList()
        val arr = Json.parseToJsonElement(json) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun num(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
            val icao = str("icaoId") ?: return@mapNotNull null
            val lat = num("lat") ?: return@mapNotNull null
            val lon = num("lon") ?: return@mapNotNull null
            val wdir = o["wdir"] as? JsonPrimitive
            Metar(
                icao = icao,
                name = str("name").orEmpty(),
                location = LatLon(lat, lon),
                obsTimeSec = (o["obsTime"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
                dirDeg = if (wdir?.isString == true) null else wdir?.intOrNull,
                speedKt = (o["wspd"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null,
                gustKt = (o["wgst"] as? JsonPrimitive)?.intOrNull,
                tempC = num("temp"),
                raw = str("rawOb").orEmpty(),
            )
        }
            .groupBy { it.icao }.values.map { list -> list.maxBy { it.obsTimeSec } }
            .sortedBy { distanceKm(at, it.location) }
            .take(max)
    }

    fun distanceKm(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * 6371.0 * asin(sqrt(h))
    }

    /** Compass point (N, NE, …) from [a] toward [b]. */
    fun bearingPoint(a: LatLon, b: LatLon): String {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val deg = Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)))
        return listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[Math.floorMod(Math.round(deg / 45).toInt(), 8)]
    }
}
