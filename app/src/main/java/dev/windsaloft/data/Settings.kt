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

/** STACKED: a vertical column per forecast source, one row per altitude (low at the bottom). FAN: all arrows share one origin. */
enum class ArrowLayout(val label: String) { STACKED("Stacked"), FAN("Fan") }

/** What arrow colour shows. AUTO: altitude, or the model when comparing. */
enum class ArrowColor(val label: String) { AUTO("Auto"), ALTITUDE("Altitude"), SPEED("Speed"), TEMPERATURE("Temperature") }

enum class TempUnit(val label: String) {
    C("°C"), F("°F");

    fun format(c: Double): String = "${(if (this == F) c * 9 / 5 + 32 else c).let { Math.round(it) }}°"
}

data class Settings(
    val unit: SpeedUnit = SpeedUnit.KT,
    val altitudeRef: AltitudeRef = AltitudeRef.MSL,
    /** 1–3 forecast models, compared side by side at the pinned location. */
    val modelIds: List<String> = listOf(OpenMeteo.MODELS.first().id),
    val ceilingFt: Int = 18_000,
    val layer: MapLayer = MapLayer.TOPO,
    val arrowLayout: ArrowLayout = ArrowLayout.STACKED,
    val arrowColor: ArrowColor = ArrowColor.AUTO,
    val tempUnit: TempUnit = TempUnit.C,
    val showTemperature: Boolean = true,
) {
    val primaryModelId: String get() = modelIds.first()
    val comparing: Boolean get() = modelIds.size > 1

    companion object {
        const val MAX_MODELS = 3
        val CEILINGS = listOf(14_000, 18_000, 24_000, 30_000)
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
            modelIds = (sp.getString("models", null) ?: sp.getString("model", null)).orEmpty()
                .split(",").filter { id -> OpenMeteo.MODELS.any { it.id == id } }.distinct().take(Settings.MAX_MODELS)
                .ifEmpty { d.modelIds },
            ceilingFt = sp.getInt("ceiling", d.ceilingFt),
            layer = enumOr(sp.getString("layer", null), d.layer),
            arrowLayout = enumOr(sp.getString("arrowLayout", null), d.arrowLayout),
            arrowColor = enumOr(sp.getString("arrowColor", null), d.arrowColor),
            tempUnit = enumOr(sp.getString("tempUnit", null), d.tempUnit),
            showTemperature = sp.getBoolean("showTemp", d.showTemperature),
        )
    }

    fun saveSettings(s: Settings) = sp.edit()
        .putString("unit", s.unit.name)
        .putString("altRef", s.altitudeRef.name)
        .putString("models", s.modelIds.joinToString(","))
        .putInt("ceiling", s.ceilingFt)
        .putString("layer", s.layer.name)
        .putString("arrowLayout", s.arrowLayout.name)
        .putString("arrowColor", s.arrowColor.name)
        .putString("tempUnit", s.tempUnit.name)
        .putBoolean("showTemp", s.showTemperature)
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

    /** Last pin forecast per model, kept on disk so the app shows data while offline. */
    fun savePinCache(modelId: String, json: String, key: String, fetchedAtMs: Long) {
        java.io.File(files, "pin_$modelId.json").writeText(json)
        sp.edit().putString("pinCacheKey_$modelId", key).putLong("pinCacheTime_$modelId", fetchedAtMs).apply()
    }

    fun loadPinCache(modelId: String, key: String): Pair<String, Long>? {
        if (sp.getString("pinCacheKey_$modelId", null) != key) return null
        val f = java.io.File(files, "pin_$modelId.json")
        return if (f.exists()) f.readText() to sp.getLong("pinCacheTime_$modelId", 0L) else null
    }

    private fun android.content.SharedPreferences.getDouble(key: String) =
        java.lang.Double.longBitsToDouble(getLong(key, 0L))

    private fun android.content.SharedPreferences.Editor.putDouble(key: String, v: Double) =
        putLong(key, java.lang.Double.doubleToRawLongBits(v))

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
