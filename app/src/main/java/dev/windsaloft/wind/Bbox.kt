package dev.windsaloft.wind

import kotlin.math.abs
import kotlin.math.max

/** A lat/lon box. [east] may be less than [west] when the box crosses the antimeridian. */
data class Bbox(val north: Double, val south: Double, val east: Double, val west: Double) {
    val latSpan: Double get() = north - south
    val lonSpan: Double get() = ((east - west) % 360.0 + 360.0) % 360.0
    val center: LatLon get() = LatLon((north + south) / 2, normalizeLon(west + lonSpan / 2))

    /** Centres of an n×n grid of cells covering the box. */
    fun gridPoints(n: Int): List<LatLon> {
        if (n <= 0) return emptyList()
        return buildList {
            for (row in 0 until n) for (col in 0 until n) {
                val lat = (north - (row + 0.5) * latSpan / n).coerceIn(-85.0, 85.0)
                val lon = normalizeLon(west + (col + 0.5) * lonSpan / n)
                add(LatLon(lat, lon))
            }
        }
    }

    /** True when [other] covers nearly the same area, so a grid fetched for one is fine for the other. */
    fun similarTo(other: Bbox): Boolean {
        val span = max(latSpan, 1e-6)
        val lonSpanSafe = max(lonSpan, 1e-6)
        val dLat = abs(center.lat - other.center.lat) / span
        val dLon = abs(normalizeLon(center.lon - other.center.lon)) / lonSpanSafe
        val zoomRatio = other.latSpan / span
        return dLat < 0.15 && dLon < 0.15 && zoomRatio in 0.8..1.25
    }

    companion object {
        fun normalizeLon(lon: Double): Double {
            var l = (lon + 180.0) % 360.0
            if (l < 0) l += 360.0
            return l - 180.0
        }
    }
}
