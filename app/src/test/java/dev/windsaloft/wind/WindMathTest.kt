package dev.windsaloft.wind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindMathTest {
    private fun angleDiff(a: Double, b: Double) = ((a - b + 540.0) % 360.0) - 180.0

    @Test
    fun uvRoundTrip() {
        for (dir in listOf(0.0, 45.0, 90.0, 180.0, 270.0, 359.0)) {
            val (u, v) = WindMath.toUv(10.0, dir)
            val (s, d) = WindMath.fromUv(u, v)
            assertEquals(10.0, s, 1e-9)
            assertEquals(0.0, angleDiff(d, dir), 1e-9)
        }
    }

    @Test
    fun westWindBlowsEast() {
        // Wind FROM 270 moves air toward the east: positive u.
        val (u, v) = WindMath.toUv(10.0, 270.0)
        assertEquals(10.0, u, 1e-9)
        assertEquals(0.0, v, 1e-9)
    }

    private val ground = 1600.0
    private val profile = listOf(
        RawSample(ground + 10, 4.0, 180.0, 15.0),
        RawSample(2000.0, 10.0, 270.0, 10.0),
        RawSample(3000.0, 20.0, 270.0, 2.0),
    )

    @Test
    fun interpolatesBetweenLevels() {
        val w = WindMath.interpolate(profile, 2500.0, ground)!!
        assertEquals(15.0, w.speedMs, 1e-9)
        assertEquals(270.0, w.dirFromDeg, 1e-9)
        assertEquals(6.0, w.tempC!!, 1e-9)
    }

    @Test
    fun interpolatesAcrossNorthSmoothly() {
        val samples = listOf(RawSample(0.0, 10.0, 350.0, null), RawSample(100.0, 10.0, 10.0, null))
        val w = WindMath.interpolate(samples, 50.0, 0.0)!!
        assertEquals(0.0, angleDiff(w.dirFromDeg, 0.0), 1e-6)
        assertTrue(w.speedMs > 9.8)
    }

    @Test
    fun belowGroundIsNull() {
        assertNull(WindMath.interpolate(profile, 1000.0, ground))
    }

    @Test
    fun surfaceUsesLowestSample() {
        val w = WindMath.interpolate(profile, ground, ground)
        assertNotNull(w)
        assertEquals(4.0, w!!.speedMs, 1e-9)
    }

    @Test
    fun aboveTopIsNull() {
        assertNull(WindMath.interpolate(profile, 3500.0, ground))
    }

    @Test
    fun forecastPointAglVsMsl() {
        val fp = ForecastPoint(LatLon(0.0, 0.0), ground, longArrayOf(3600L), listOf(profile))
        val msl = fp.windsAt(3600L, listOf(0, 8000), AltitudeRef.MSL)
        // Sea level is underground here, so that slot shows the ground wind instead.
        assertTrue(msl[0]!!.surface)
        assertEquals(4.0, msl[0]!!.speedMs, 1e-9)
        assertNotNull(msl[1])
        val agl = fp.windsAt(3600L, listOf(0, 1000), AltitudeRef.AGL)
        assertEquals(4.0, agl[0]!!.speedMs, 1e-9)
        assertNotNull(agl[1])
        assertTrue(fp.windsAt(7200L, listOf(0), AltitudeRef.AGL).all { it == null })
    }

    @Test
    fun vectorSpread() {
        assertNull(WindMath.maxVectorDifference(listOf(Wind(0, 270.0, 10.0, null))))
        // Same speed, opposite directions: 20 m/s apart.
        val opposite = WindMath.maxVectorDifference(listOf(Wind(0, 270.0, 10.0, null), Wind(0, 90.0, 10.0, null)))!!
        assertEquals(20.0, opposite, 1e-9)
        // Largest pair wins.
        val three = WindMath.maxVectorDifference(
            listOf(Wind(0, 270.0, 10.0, null), Wind(0, 270.0, 12.0, null), Wind(0, 270.0, 15.0, null)),
        )!!
        assertEquals(5.0, three, 1e-9)
    }

    @Test
    fun temperatureSkipsSamplesWithoutOne() {
        // Like ECMWF: wind at 120 m with no temperature, between two levels that have one.
        val samples = listOf(
            RawSample(1010.0, 3.0, 180.0, 10.0),
            RawSample(1120.0, 5.0, 190.0, null),
            RawSample(3000.0, 15.0, 270.0, -10.0),
        )
        val w = WindMath.interpolate(samples, 2005.0, 1000.0)!!
        assertEquals(0.0, w.tempC!!, 1e-9)
        assertNull(WindMath.interpolate(listOf(RawSample(0.0, 1.0, 0.0, null), RawSample(100.0, 1.0, 0.0, null)), 50.0, 0.0)!!.tempC)
    }

    @Test
    fun cloudLayersFromPressureLevels() {
        val ground = 1000.0
        // 70 % cloud from ~2,400 to ~2,900 m MSL, 35 % around 3,900 m, clear elsewhere.
        val levels = listOf(
            CloudSample(1500.0, 0.0), CloudSample(2200.0, 10.0), CloudSample(2400.0, 70.0), CloudSample(2900.0, 65.0),
            CloudSample(3100.0, 5.0), CloudSample(3700.0, 0.0), CloudSample(3900.0, 35.0), CloudSample(4100.0, 0.0),
        )
        val fp = ForecastPoint(LatLon(0.0, 0.0), ground, longArrayOf(0L), listOf(emptyList()), listOf(levels))
        val layers = fp.cloudLayers(0L, AltitudeRef.MSL, 0, 18_000)!!
        assertEquals(2, layers.size)
        val bkn = layers[0]
        assertEquals(CloudCover.BKN, bkn.cover)
        // 25 % is crossed at 2,250 m (7,382 ft) and again at ~3,033 m (9,951 ft); layers snap to 100 ft steps.
        assertEquals(7_400, bkn.baseFt)
        assertEquals(9_900, bkn.topFt)
        assertEquals(CloudCover.SCT, layers[1].cover)
        // AGL shifts the same layer down by the ground elevation.
        val agl = fp.cloudLayers(0L, AltitudeRef.AGL, 0, 18_000)!!
        assertTrue(kotlin.math.abs((bkn.baseFt - agl[0].baseFt) - 3_281) <= 100)
        // No cloud data at all -> null, not "clear".
        assertNull(ForecastPoint(LatLon(0.0, 0.0), ground, longArrayOf(0L), listOf(emptyList())).cloudLayers(0L, AltitudeRef.MSL, 0, 18_000))
    }

    @Test
    fun temperatureRampIsBlueAtFreezingAndBlackAtMinus40() {
        val colors = dev.windsaloft.map.AltitudeColors
        assertEquals(0xFF2F7BFF.toInt(), colors.temperature(0.0))
        assertEquals(0xFF000000.toInt(), colors.temperature(-40.0))
        assertEquals(0xFF000000.toInt(), colors.temperature(-55.0)) // clamps below -40
        // Colder than freezing gets steadily darker.
        fun lum(c: Int) = 0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)
        val darkening = (0 downTo -40 step 5).map { lum(colors.temperature(it.toDouble())) }
        assertTrue(darkening.zipWithNext().all { (a, b) -> b < a })
        // Very dark symbols get a light outline so they stay visible.
        assertEquals(0xCCFFFFFF.toInt(), colors.haloFor(colors.temperature(-35.0)))
        assertEquals(0xAA000000.toInt(), colors.haloFor(colors.temperature(20.0)))
    }
}
