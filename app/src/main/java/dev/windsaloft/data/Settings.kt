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

/**
 * Which end of an arrow sits on the column's centre line. TAIL is a windsock: attached at its upwind
 * end, streaming downwind. NOSE puts the arrowhead on the line; CENTRE centres the arrow on it.
 */
enum class ArrowAnchor(val label: String) { TAIL("Tail (windsock)"), NOSE("Nose"), CENTRE("Centre") }

/** What arrow colour shows. AUTO: altitude, or the model when comparing. */
enum class ArrowColor(val label: String) {
    AUTO("Auto"), ALTITUDE("Altitude"), SPEED("Speed"), TEMPERATURE("Temperature"), MODEL("Model"), WHITE("White")
}

/**
 * The wind symbol, with the end that sits on the column line by default: an arrow is a windsock
 * (points downwind, tail on the line), a triangle is a landing tetrahedron (points into the wind, nose
 * on the line), a wind barb follows the weather-chart convention (station end on the line).
 */
enum class Glyph(val label: String, val defaultAnchor: ArrowAnchor) {
    ARROW("Arrow (windsock)", ArrowAnchor.TAIL),
    TRIANGLE("Triangle (tetrahedron)", ArrowAnchor.NOSE),
    BARB("Wind barb", ArrowAnchor.TAIL),
}

/**
 * Which way the wind chart runs on screen. LONGEST keeps it along the screen's long side: on a phone
 * turned sideways the chart stays "up" as if the phone were still upright, using the most room.
 */
enum class ChartOrientation(val label: String) {
    LONGEST("Along the long side"), VERTICAL("Always vertical"), HORIZONTAL("Always horizontal")
}

enum class TempUnit(val label: String) {
    C("Celsius (C)"), F("Fahrenheit (F)");

    /** "-3C" / "27F": the unit letter, not "°", which is kept for compass directions. */
    fun format(c: Double): String = "${(if (this == F) c * 9 / 5 + 32 else c).let { Math.round(it) }}$name"
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
    val showClouds: Boolean = true,
    val arrowAnchor: ArrowAnchor = ArrowAnchor.TAIL,
    /** Show the direction the wind comes from (e.g. "270°") beside each speed on the map. */
    val showDirection: Boolean = false,
    val showSpeed: Boolean = true,
    val glyph: Glyph = Glyph.ARROW,
    val chartOrientation: ChartOrientation = ChartOrientation.LONGEST,
    /** Show the latest reports (METARs) from airports near the forecast spot. */
    val showMetars: Boolean = true,
) {
    val primaryModelId: String get() = modelIds.first()
    val comparing: Boolean get() = modelIds.size > 1

    companion object {
        const val MAX_MODELS = 3
        val CEILINGS = listOf(14_000, 18_000, 24_000, 30_000)
    }
}

/** [orientation] (map rotation) is kept in memory only: it belongs to the way the phone was held then. */
data class Camera(val center: LatLon, val zoom: Double, val orientation: Float = 0f)

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
            showClouds = sp.getBoolean("showClouds", d.showClouds),
            arrowAnchor = enumOr(sp.getString("arrowAnchor", null), d.arrowAnchor),
            showDirection = sp.getBoolean("showDirection", d.showDirection),
            showSpeed = sp.getBoolean("showSpeed", d.showSpeed),
            glyph = enumOr(sp.getString("glyph", null), d.glyph),
            chartOrientation = enumOr(sp.getString("chartOrientation", null), d.chartOrientation),
            showMetars = sp.getBoolean("showMetars", d.showMetars),
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
        .putBoolean("showClouds", s.showClouds)
        .putString("arrowAnchor", s.arrowAnchor.name)
        .putBoolean("showDirection", s.showDirection)
        .putBoolean("showSpeed", s.showSpeed)
        .putString("glyph", s.glyph.name)
        .putString("chartOrientation", s.chartOrientation.name)
        .putBoolean("showMetars", s.showMetars)
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

    fun loadFavorites(): List<SavedPlace> = SavedPlace.decodeList(sp.getString("favorites", null))

    fun saveFavorites(list: List<SavedPlace>) = sp.edit().putString("favorites", SavedPlace.encodeList(list)).apply()

    fun loadRecents(): List<SavedPlace> = SavedPlace.decodeList(sp.getString("recents", null))

    fun saveRecents(list: List<SavedPlace>) = sp.edit().putString("recents", SavedPlace.encodeList(list)).apply()

    private fun android.content.SharedPreferences.getDouble(key: String) =
        java.lang.Double.longBitsToDouble(getLong(key, 0L))

    private fun android.content.SharedPreferences.Editor.putDouble(key: String, v: Double) =
        putLong(key, java.lang.Double.doubleToRawLongBits(v))

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
