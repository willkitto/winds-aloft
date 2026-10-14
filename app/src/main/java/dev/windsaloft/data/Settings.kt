package dev.windsaloft.data

import android.content.Context
import dev.windsaloft.wind.AltitudeRef
import dev.windsaloft.wind.KT_PER_MS
import dev.windsaloft.wind.LatLon

enum class SpeedUnit(val label: String, val perMs: Double) {
    KT("kt", KT_PER_MS),
    MPH("mph", 2.236936),
    KMH("km/h", 3.6),
    MS("m/s", 1.0),
}

enum class MapLayer(val label: String) { TOPO("Topo"), STREETS("Streets"), SATELLITE("Satellite") }

data class Settings(
    val unit: SpeedUnit = SpeedUnit.KT,
    val altitudeRef: AltitudeRef = AltitudeRef.MSL,
    val modelId: String = OpenMeteo.MODELS.first().id,
    /** Wind arrows drawn in an N×N grid across the visible map; 0 turns the grid off. */
    val gridSize: Int = 4,
    val ceilingFt: Int = 18_000,
    val layer: MapLayer = MapLayer.TOPO,
) {
    companion object {
        val CEILINGS = listOf(14_000, 18_000, 24_000, 30_000)
        val GRID_SIZES = listOf(0, 3, 4, 5)
    }
}

data class Camera(val center: LatLon, val zoom: Double)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("winds_aloft", Context.MODE_PRIVATE)
    private val files = context.filesDir

    fun loadSettings(): Settings {
        val d = Settings()
        return Settings(
            unit = enumOr(sp.getString("unit", null), d.unit),
            altitudeRef = enumOr(sp.getString("altRef", null), d.altitudeRef),
            modelId = sp.getString("model", null)?.takeIf { id -> OpenMeteo.MODELS.any { it.id == id } } ?: d.modelId,
            gridSize = sp.getInt("grid", d.gridSize),
            ceilingFt = sp.getInt("ceiling", d.ceilingFt),
            layer = enumOr(sp.getString("layer", null), d.layer),
        )
    }

    fun saveSettings(s: Settings) = sp.edit()
        .putString("unit", s.unit.name)
        .putString("altRef", s.altitudeRef.name)
        .putString("model", s.modelId)
        .putInt("grid", s.gridSize)
        .putInt("ceiling", s.ceilingFt)
        .putString("layer", s.layer.name)
        .apply()

    fun loadPin(): Pair<LatLon, String?>? {
        if (!sp.contains("pinLat")) return null
        return LatLon(sp.getDouble("pinLat"), sp.getDouble("pinLon")) to sp.getString("pinLabel", null)
    }

    fun savePin(pin: LatLon?, label: String?) {
        val e = sp.edit()
        if (pin == null) e.remove("pinLat").remove("pinLon").remove("pinLabel")
        else e.putDouble("pinLat", pin.lat).putDouble("pinLon", pin.lon).putString("pinLabel", label)
        e.apply()
    }

    fun loadCamera(): Camera? {
        if (!sp.contains("camLat")) return null
        return Camera(LatLon(sp.getDouble("camLat"), sp.getDouble("camLon")), sp.getDouble("camZoom"))
    }

    fun saveCamera(c: Camera) = sp.edit()
        .putDouble("camLat", c.center.lat).putDouble("camLon", c.center.lon).putDouble("camZoom", c.zoom)
        .apply()

    fun loadAltRange(): IntRange = sp.getInt("altLo", 0)..sp.getInt("altHi", 14)

    fun saveAltRange(r: IntRange) = sp.edit().putInt("altLo", r.first).putInt("altHi", r.last).apply()

    /** Last pin forecast, kept on disk so the app shows data while offline. */
    fun savePinCache(json: String, key: String, fetchedAtMs: Long) {
        java.io.File(files, "pin_forecast.json").writeText(json)
        sp.edit().putString("pinCacheKey", key).putLong("pinCacheTime", fetchedAtMs).apply()
    }

    fun loadPinCache(key: String): Pair<String, Long>? {
        if (sp.getString("pinCacheKey", null) != key) return null
        val f = java.io.File(files, "pin_forecast.json")
        return if (f.exists()) f.readText() to sp.getLong("pinCacheTime", 0L) else null
    }

    private fun android.content.SharedPreferences.getDouble(key: String) =
        java.lang.Double.longBitsToDouble(getLong(key, 0L))

    private fun android.content.SharedPreferences.Editor.putDouble(key: String, v: Double) =
        putLong(key, java.lang.Double.doubleToRawLongBits(v))

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
