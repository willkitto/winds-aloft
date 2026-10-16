package dev.windsaloft.data

import dev.windsaloft.wind.LatLon
import java.io.File
import java.util.Locale

/**
 * Raw Open-Meteo responses on disk, one file per spot and model, so recently viewed and saved places
 * still have winds with no connection (e.g. in the plane on the way to altitude).
 */
class WindCache(private val dir: File) {
    data class Entry(val json: String, val fetchedAtMs: Long, val ceilingFt: Int)

    init { dir.mkdirs() }

    fun save(at: LatLon, model: String, json: String, fetchedAtMs: Long, ceilingFt: Int) {
        val base = key(at, model)
        val tmp = File(dir, "$base.tmp")
        tmp.writeText(json)
        tmp.renameTo(File(dir, "$base.json"))
        File(dir, "$base.meta").writeText("$fetchedAtMs $ceilingFt")
    }

    fun load(at: LatLon, model: String): Entry? {
        val base = key(at, model)
        val body = File(dir, "$base.json")
        val meta = File(dir, "$base.meta")
        if (!body.exists() || !meta.exists()) return null
        val parts = meta.readText().trim().split(" ")
        val time = parts.getOrNull(0)?.toLongOrNull() ?: return null
        return Entry(body.readText(), time, parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** When [at] was last downloaded for [model], without reading the forecast itself. */
    fun fetchedAt(at: LatLon, model: String): Long? =
        File(dir, "${key(at, model)}.meta").takeIf { it.exists() }?.readText()?.trim()?.split(" ")?.firstOrNull()?.toLongOrNull()

    /** Deletes everything except the spots in [keep] (favourites, recents and the current pin). */
    fun prune(keep: Collection<LatLon>) {
        val keepPrefixes = keep.map { spotKey(it) + "_" }.toSet()
        dir.listFiles()?.forEach { f -> if (keepPrefixes.none { f.name.startsWith(it) }) f.delete() }
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    companion object {
        fun spotKey(at: LatLon) = String.format(Locale.US, "%.4f_%.4f", at.lat, at.lon)
        fun key(at: LatLon, model: String) = spotKey(at) + "_" + model.replace(Regex("[^A-Za-z0-9_]"), "")
    }
}
