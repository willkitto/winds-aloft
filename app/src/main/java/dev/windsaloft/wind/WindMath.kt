package dev.windsaloft.wind

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

const val M_PER_FT = 0.3048
const val KT_PER_MS = 1.943844

data class LatLon(val lat: Double, val lon: Double)

/** One model level for one hour: wind (and optionally temperature) at a height above mean sea level. */
data class RawSample(val heightM: Double, val speedMs: Double, val dirFromDeg: Double, val tempC: Double?)

/**
 * Wind interpolated to a requested altitude. Direction is meteorological: where the wind blows FROM.
 * [surface] marks the ground wind (10 m), which also carries the forecast gust speed, [gustMs].
 */
data class Wind(
    val altitudeFt: Int, val dirFromDeg: Double, val speedMs: Double, val tempC: Double?,
    val gustMs: Double? = null, val surface: Boolean = false,
)

enum class AltitudeRef { MSL, AGL }

/** Cloud cover (%) reported for a pressure level at a height above mean sea level. */
data class CloudSample(val heightM: Double, val pct: Double)

/** Aviation coverage categories (in eighths): scattered 3–4/8, broken 5–7/8, overcast 8/8. */
enum class CloudCover {
    SCT, BKN, OVC;

    companion object {
        fun of(pct: Double): CloudCover = when {
            pct >= 87.5 -> OVC
            pct >= 50.0 -> BKN
            else -> SCT
        }
    }
}

/** A cloud layer between [baseFt] and [topFt] (in the display reference, MSL or AGL), at most [maxPct] cover. */
data class CloudLayer(val baseFt: Int, val topFt: Int, val maxPct: Double) {
    val cover: CloudCover get() = CloudCover.of(maxPct)
}

object WindMath {
    /** Largest gap between the ground and the lowest sample over which we extend the lowest sample down. */
    private const val SURFACE_CLAMP_M = 50.0

    /** Converts speed + meteorological direction into u (east) / v (north) components. */
    fun toUv(speed: Double, dirFromDeg: Double): Pair<Double, Double> {
        val r = Math.toRadians(dirFromDeg)
        return -speed * sin(r) to -speed * cos(r)
    }

    /** Converts u / v back into speed + meteorological direction in [0, 360). */
    fun fromUv(u: Double, v: Double): Pair<Double, Double> {
        val speed = hypot(u, v)
        var dir = Math.toDegrees(atan2(-u, -v))
        if (dir < 0) dir += 360.0
        if (dir >= 360.0) dir -= 360.0
        return speed to dir
    }

    /** Cover at least this (%) counts as a cloud layer. */
    const val CLOUD_LAYER_MIN_PCT = 25.0

    /** Cloud cover (%) at [heightM], interpolated between pressure levels sorted by height. */
    fun cloudAt(levels: List<CloudSample>, heightM: Double): Double? {
        if (levels.isEmpty()) return null
        val first = levels.first()
        if (heightM <= first.heightM) return if (first.heightM - heightM <= 500.0) first.pct else null
        for (k in 1 until levels.size) {
            val hi = levels[k]
            if (hi.heightM >= heightM) {
                val lo = levels[k - 1]
                return lo.pct + (hi.pct - lo.pct) * (heightM - lo.heightM) / (hi.heightM - lo.heightM)
            }
        }
        return null
    }

    /**
     * Largest magnitude of the vector difference between any two winds (m/s): how far apart the
     * models are, combining direction and speed. Null with fewer than two winds.
     */
    fun maxVectorDifference(winds: List<Wind>): Double? {
        if (winds.size < 2) return null
        val uv = winds.map { toUv(it.speedMs, it.dirFromDeg) }
        var max = 0.0
        for (i in uv.indices) for (j in i + 1 until uv.size) {
            max = maxOf(max, hypot(uv[i].first - uv[j].first, uv[i].second - uv[j].second))
        }
        return max
    }

    /**
     * Linearly interpolates the wind vector (and temperature) to [targetM] metres MSL.
     * [samples] must be sorted by height. Returns null below ground or above the highest sample.
     */
    fun interpolate(samples: List<RawSample>, targetM: Double, groundM: Double): RawSample? {
        if (samples.isEmpty() || targetM < groundM - 1.0) return null
        val first = samples.first()
        if (targetM <= first.heightM) {
            return if (first.heightM - groundM <= SURFACE_CLAMP_M) first.copy(heightM = targetM, tempC = interpolateTemp(samples, targetM)) else null
        }
        if (targetM > samples.last().heightM) return null

        val hiIdx = samples.indexOfFirst { it.heightM >= targetM }
        val hi = samples[hiIdx]
        val lo = samples[hiIdx - 1]
        val span = hi.heightM - lo.heightM
        val t = if (span <= 0.0) 0.0 else (targetM - lo.heightM) / span

        val (u0, v0) = toUv(lo.speedMs, lo.dirFromDeg)
        val (u1, v1) = toUv(hi.speedMs, hi.dirFromDeg)
        val (speed, dir) = fromUv(u0 + (u1 - u0) * t, v0 + (v1 - v0) * t)
        return RawSample(targetM, speed, dir, interpolateTemp(samples, targetM))
    }

    /**
     * Temperature at [targetM], interpolated only between samples that have one: some models give
     * wind but no temperature at 80–180 m, and those samples mustn't blank the levels around them.
     */
    fun interpolateTemp(samples: List<RawSample>, targetM: Double): Double? {
        var lo: RawSample? = null
        var hi: RawSample? = null
        for (s in samples) {
            if (s.tempC == null) continue
            if (s.heightM <= targetM) lo = s else { hi = s; break }
        }
        val l = lo?.tempC
        val h = hi?.tempC
        return when {
            l != null && h != null -> l + (h - l) * (targetM - lo!!.heightM) / (hi!!.heightM - lo.heightM)
            l != null && targetM - lo!!.heightM <= SURFACE_CLAMP_M -> l
            h != null && hi!!.heightM - targetM <= SURFACE_CLAMP_M -> h
            else -> null
        }
    }
}

/** Hourly vertical wind profiles for one location. */
class ForecastPoint(
    val location: LatLon,
    val elevationM: Double,
    val timesEpochSec: LongArray,
    /** Per hour, samples sorted by height MSL. */
    val profiles: List<List<RawSample>>,
    /** Per hour, pressure-level cloud cover sorted by height (empty when the model has none). */
    val clouds: List<List<CloudSample>> = emptyList(),
    /** Per hour, forecast gust speed at 10 m (NaN = none); null when the model doesn't give gusts. */
    val surfaceGustsMs: DoubleArray? = null,
) {
    /**
     * Cloud layers between [loFt] and [hiFt] (display reference), found by scanning every 100 ft for
     * at least [WindMath.CLOUD_LAYER_MIN_PCT] cover. Null when the model gives no cloud data.
     */
    fun cloudLayers(epochSec: Long, ref: AltitudeRef, loFt: Int, hiFt: Int): List<CloudLayer>? {
        val i = hourIndex(epochSec)
        val levels = clouds.getOrNull(i)
        if (i < 0 || levels.isNullOrEmpty()) return null
        val base = if (ref == AltitudeRef.AGL) elevationM else 0.0
        val out = mutableListOf<CloudLayer>()
        var start = -1
        var last = -1
        var max = 0.0
        for (ft in loFt..hiFt step 100) {
            val h = base + ft * M_PER_FT
            val pct = if (h < elevationM) null else WindMath.cloudAt(levels, h)
            if (pct != null && pct >= WindMath.CLOUD_LAYER_MIN_PCT) {
                if (start < 0) { start = ft; max = pct } else max = maxOf(max, pct)
                last = ft
            } else if (start >= 0) {
                out += CloudLayer(start, last, max); start = -1
            }
        }
        if (start >= 0) out += CloudLayer(start, last, max)
        return out
    }

    val lastEpochSec: Long get() = timesEpochSec.lastOrNull() ?: 0L

    fun hourIndex(epochSec: Long): Int = java.util.Arrays.binarySearch(timesEpochSec, epochSec).let { if (it >= 0) it else -1 }

    /**
     * Winds at each altitude. The ground wind (with gusts) fills the 0 ft AGL slot, or in MSL the
     * slot just below the lowest altitude above ground, so the column starts with the surface wind.
     */
    fun windsAt(epochSec: Long, altitudesFt: List<Int>, ref: AltitudeRef): List<Wind?> {
        val i = hourIndex(epochSec)
        if (i < 0) return altitudesFt.map { null }
        val samples = profiles[i]
        val base = if (ref == AltitudeRef.AGL) elevationM else 0.0
        val winds = altitudesFt.map { ft ->
            if (ref == AltitudeRef.AGL && ft == 0) return@map surfaceAt(i, 0)
            WindMath.interpolate(samples, base + ft * M_PER_FT, elevationM)
                ?.let { Wind(ft, it.dirFromDeg, it.speedMs, it.tempC) }
        }.toMutableList()
        if (ref == AltitudeRef.MSL) {
            val first = winds.indexOfFirst { it != null }
            if (first > 0 && altitudesFt[first - 1] * M_PER_FT < elevationM) {
                winds[first - 1] = surfaceAt(i, Math.round(elevationM / M_PER_FT).toInt())
            }
        }
        return winds
    }

    /** The 10 m wind for hour [i], labelled with [altitudeFt], with the gust forecast when there is one. */
    private fun surfaceAt(i: Int, altitudeFt: Int): Wind? {
        val s = profiles[i].firstOrNull { kotlin.math.abs(it.heightM - (elevationM + 10)) < 1.0 } ?: return null
        val gust = surfaceGustsMs?.getOrNull(i)?.takeUnless { it.isNaN() }
        return Wind(altitudeFt, s.dirFromDeg, s.speedMs, s.tempC, gustMs = gust, surface = true)
    }
}
