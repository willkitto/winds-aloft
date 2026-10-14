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
        assertNull(msl[0]) // sea level is underground here
        assertNotNull(msl[1])
        val agl = fp.windsAt(3600L, listOf(0, 1000), AltitudeRef.AGL)
        assertEquals(4.0, agl[0]!!.speedMs, 1e-9)
        assertNotNull(agl[1])
        assertTrue(fp.windsAt(7200L, listOf(0), AltitudeRef.AGL).all { it == null })
    }

    @Test
    fun bboxGrid() {
        val b = Bbox(north = 41.0, south = 39.0, east = -104.0, west = -106.0)
        val pts = b.gridPoints(2)
        assertEquals(4, pts.size)
        assertEquals(LatLon(40.5, -105.5), pts[0])
        assertEquals(LatLon(39.5, -104.5), pts[3])
        assertTrue(b.similarTo(b.copy(north = 41.1, south = 39.1)))
        assertTrue(!b.similarTo(b.copy(north = 42.0, south = 40.0)))
    }

    @Test
    fun bboxAcrossAntimeridian() {
        val b = Bbox(north = 10.0, south = 0.0, east = -170.0, west = 170.0)
        assertEquals(20.0, b.lonSpan, 1e-9)
        val pts = b.gridPoints(2)
        assertEquals(175.0, pts[0].lon, 1e-9)
        assertEquals(-175.0, pts[1].lon, 1e-9)
    }
}
