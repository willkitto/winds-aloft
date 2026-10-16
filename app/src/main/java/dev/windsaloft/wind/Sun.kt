package dev.windsaloft.wind

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Sunrise and sunset (NOAA's simple approximation, good to a few minutes) for shading night. */
object Sun {
    /** [sunrise, sunset] in epoch seconds for the UTC day starting at [dayStartUtc]; null in polar day or night. */
    fun times(lat: Double, lon: Double, dayStartUtc: Long): Pair<Long, Long>? {
        val dayOfYear = java.time.Instant.ofEpochSecond(dayStartUtc).atZone(java.time.ZoneOffset.UTC).dayOfYear
        val g = 2 * Math.PI / 365 * (dayOfYear - 1)
        val eq = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) -
            0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val la = Math.toRadians(lat)
        val x = cos(Math.toRadians(90.833)) / (cos(la) * cos(decl)) - tan(la) * tan(decl)
        if (x < -1 || x > 1) return null
        val ha = Math.toDegrees(acos(x))
        return dayStartUtc + ((720 - 4 * (lon + ha) - eq) * 60).toLong() to dayStartUtc + ((720 - 4 * (lon - ha) - eq) * 60).toLong()
    }

    fun isDay(lat: Double, lon: Double, epochSec: Long): Boolean {
        val day = epochSec / 86400 * 86400
        for (k in -1..1) {
            val t = times(lat, lon, day + k * 86400L) ?: return isPolarDay(lat, epochSec)
            if (epochSec in t.first..t.second) return true
        }
        return false
    }

    private fun isPolarDay(lat: Double, epochSec: Long): Boolean {
        val month = java.time.Instant.ofEpochSecond(epochSec).atZone(java.time.ZoneOffset.UTC).monthValue
        return (lat > 0) == (month in 4..9)
    }
}
