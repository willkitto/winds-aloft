package dev.windsaloft.data

import dev.windsaloft.wind.LatLon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.math.abs

/** A favourite or recently viewed forecast spot. [name] is null for spots picked on the map. */
data class SavedPlace(val name: String?, val location: LatLon, val atMs: Long) {
    /** Within ~100 m counts as the same spot, so re-picking a drop zone doesn't add duplicates. */
    fun sameSpot(other: LatLon) = abs(location.lat - other.lat) < SAME_DEG && abs(location.lon - other.lon) < SAME_DEG

    companion object {
        const val SAME_DEG = 0.001
        const val MAX_RECENTS = 20

        fun encodeList(list: List<SavedPlace>): String = buildJsonArray {
            list.forEach { p ->
                add(buildJsonObject {
                    p.name?.let { put("name", it) }
                    put("lat", p.location.lat)
                    put("lon", p.location.lon)
                    put("at", p.atMs)
                })
            }
        }.toString()

        fun decodeList(json: String?): List<SavedPlace> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                (Json.parseToJsonElement(json) as JsonArray).mapNotNull { e ->
                    val o = e as? JsonObject ?: return@mapNotNull null
                    SavedPlace(
                        (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                        LatLon(o.getValue("lat").jsonPrimitive.double, o.getValue("lon").jsonPrimitive.double),
                        o["at"]?.jsonPrimitive?.long ?: 0L,
                    )
                }
            }.getOrDefault(emptyList())
        }

        /** Puts [place] first, drops any earlier entry for the same spot, and keeps at most [max]. */
        fun pushRecent(list: List<SavedPlace>, place: SavedPlace, max: Int = MAX_RECENTS): List<SavedPlace> =
            (listOf(place) + list.filterNot { it.sameSpot(place.location) }).take(max)
    }
}
