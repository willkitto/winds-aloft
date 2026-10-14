package dev.windsaloft.data

import dev.windsaloft.wind.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Http {
    var userAgent = "WindsAloft"

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", userAgent)
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val reason = runCatching {
                    Json.parseToJsonElement(body).jsonObject["reason"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                throw IOException(reason ?: "HTTP $code")
            }
            body
        } finally {
            conn.disconnect()
        }
    }
}

data class Place(val name: String, val detail: String, val location: LatLon)

/** Place search via the Open-Meteo geocoding API. */
object Geocoding {
    suspend fun search(query: String): List<Place> {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&format=json&name=" +
            URLEncoder.encode(query, "UTF-8")
        return parse(Http.get(url))
    }

    fun parse(json: String): List<Place> {
        val results = Json.parseToJsonElement(json).jsonObject["results"]?.jsonArray ?: return emptyList()
        return results.mapNotNull { el ->
            val o = el.jsonObject
            val lat = o["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lon = o["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val detail = listOfNotNull(
                o["admin1"]?.jsonPrimitive?.contentOrNull,
                o["country"]?.jsonPrimitive?.contentOrNull,
            ).joinToString(", ")
            Place(name, detail, LatLon(lat, lon))
        }
    }

    private val COORDS = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*[,\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

    /** Parses "lat, lon" input, e.g. "40.015, -105.27". */
    fun parseCoordinates(text: String): LatLon? {
        val m = COORDS.matchEntire(text) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) LatLon(lat, lon) else null
    }
}
