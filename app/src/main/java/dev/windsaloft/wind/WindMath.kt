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

/** Wind interpolated to a requested altitude. Direction is meteorological: where the wind blows FROM. */
data class Wind(val altitudeFt: Int, val dirFromDeg: Double, val speedMs: Double, val tempC: Double?)

enum class AltitudeRef { MSL, AGL }

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

    /**
     * Linearly interpolates the wind vector (and temperature) to [targetM] metres MSL.
     * [samples] must be sorted by height. Returns null below ground or above the highest sample.
     */
    fun interpolate(samples: List<RawSample>, targetM: Double, groundM: Double): RawSample? {
        if (samples.isEmpty() || targetM < groundM - 1.0) return null
        val first = samples.first()
        if (targetM <= first.heightM) {
            return if (first.heightM - groundM <= SURFACE_CLAMP_M) first.copy(heightM = targetM) else null
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
        val temp = if (lo.tempC != null && hi.tempC != null) lo.tempC + (hi.tempC - lo.tempC) * t else null
        return RawSample(targetM, speed, dir, temp)
    }
}

/** Hourly vertical wind profiles for one location. */
class ForecastPoint(
    val location: LatLon,
    val elevationM: Double,
    val timesEpochSec: LongArray,
    /** Per hour, samples sorted by height MSL. */
    val profiles: List<List<RawSample>>,
) {
    val lastEpochSec: Long get() = timesEpochSec.lastOrNull() ?: 0L

    fun hourIndex(epochSec: Long): Int = java.util.Arrays.binarySearch(timesEpochSec, epochSec).let { if (it >= 0) it else -1 }

    fun windsAt(epochSec: Long, altitudesFt: List<Int>, ref: AltitudeRef): List<Wind?> {
        val i = hourIndex(epochSec)
        if (i < 0) return altitudesFt.map { null }
        val samples = profiles[i]
        val base = if (ref == AltitudeRef.AGL) elevationM else 0.0
        return altitudesFt.map { ft ->
            WindMath.interpolate(samples, base + ft * M_PER_FT, elevationM)
                ?.let { Wind(ft, it.dirFromDeg, it.speedMs, it.tempC) }
        }
    }
}
