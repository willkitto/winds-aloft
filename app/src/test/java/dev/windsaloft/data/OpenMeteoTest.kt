package dev.windsaloft.data

import dev.windsaloft.wind.AltitudeRef
import dev.windsaloft.wind.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoTest {
    @Test
    fun levelsCoverCeiling() {
        assertEquals(listOf(1000, 975, 950, 925, 900, 850, 800, 750, 700, 650, 600, 550), OpenMeteo.levelsFor(14_000))
        assertEquals(250, OpenMeteo.levelsFor(30_000).last())
    }

    @Test
    fun urlContainsAllPoints() {
        val url = OpenMeteo.forecastUrl(listOf(LatLon(40.0, -105.0), LatLon(41.5, -104.25)), "gfs_seamless", 14_000, false)
        assertTrue(url.contains("latitude=40.0000,41.5000"))
        assertTrue(url.contains("longitude=-105.0000,-104.2500"))
        assertTrue(url.contains("geopotential_height_550hPa"))
        assertFalse(url.contains("temperature"))
        assertTrue(url.contains("models=gfs_seamless"))
        assertTrue(url.contains("wind_gusts_10m"))
    }

    // Two locations, as the API returns for comma-separated coordinates. The 1000 hPa level is
    // below ground at the first (elevation 1600 m) and must be dropped; 850 hPa has a null hour.
    private val twoLocations = """
        [{"latitude":40.0,"longitude":-105.0,"elevation":1600.0,
          "hourly":{"time":[3600,7200],
            "wind_speed_10m":[3.0,4.0],"wind_direction_10m":[180,190],"wind_gusts_10m":[9.5,null],
            "wind_speed_1000hPa":[9.0,9.0],"wind_direction_1000hPa":[90,90],"geopotential_height_1000hPa":[110,112],
            "wind_speed_850hPa":[8.0,null],"wind_direction_850hPa":[250,null],"geopotential_height_850hPa":[1450,1460],
            "wind_speed_700hPa":[15.0,16.0],"wind_direction_700hPa":[270,275],"geopotential_height_700hPa":[3100,3110],
            "cloud_cover_850hPa":[90,90],"cloud_cover_700hPa":[60,null]}},
         {"latitude":41.0,"longitude":-104.0,"elevation":50.0,
          "hourly":{"time":[3600,7200],
            "wind_speed_10m":[2.0,2.0],"wind_direction_10m":[0,0],
            "wind_speed_1000hPa":[5.0,5.0],"wind_direction_1000hPa":[45,45],"geopotential_height_1000hPa":[110,112]}}]
    """.trimIndent()

    @Test
    fun parsesMultipleLocations() {
        val requested = listOf(LatLon(40.01, -105.02), LatLon(41.0, -104.0))
        val pts = OpenMeteo.parse(twoLocations, requested)
        assertEquals(2, pts.size)
        assertEquals(requested[0], pts[0].location)

        val first = pts[0]
        assertEquals(1600.0, first.elevationM, 1e-9)
        // hour 0: 10 m + 700 hPa (1000 hPa below ground, 850 hPa at 1450 m is below 1600 m ground too)
        assertEquals(listOf(1610.0, 3100.0), first.profiles[0].map { it.heightM })
        // MSL: the below-ground slot under the lowest real altitude shows the ground wind and its gusts;
        // anything further below ground is empty. AGL 0 ft is the ground wind too.
        val msl = first.windsAt(3600, listOf(0, 3000, 9000), AltitudeRef.MSL)
        assertNull(msl[0])
        assertTrue(msl[1]!!.surface)
        assertEquals(3.0, msl[1]!!.speedMs, 1e-9)
        assertEquals(9.5, msl[1]!!.gustMs!!, 1e-9)
        assertEquals(5249, msl[1]!!.altitudeFt) // the ground, 1600 m
        assertEquals(270.0, msl[2]!!.dirFromDeg, 15.0)
        val agl = first.windsAt(3600, listOf(0, 1000), AltitudeRef.AGL)
        assertTrue(agl[0]!!.surface)
        assertEquals(3.0, agl[0]!!.speedMs, 1e-9)
        assertFalse(agl[1]!!.surface)
        assertNull(agl[1]!!.gustMs)
        // No gust given for that hour: the ground wind still shows, just without gusts.
        assertNull(first.windsAt(7200, listOf(0), AltitudeRef.AGL)[0]!!.gustMs)

        // Cloud cover comes from levels above ground only: 850 hPa (1450 m) is under the 1600 m terrain.
        assertEquals(listOf(3100.0 to 60.0), first.clouds[0].map { it.heightM to it.pct })
        assertTrue(first.clouds[1].isEmpty())

        val second = pts[1]
        assertEquals(listOf(60.0, 110.0), second.profiles[0].map { it.heightM })
    }

    @Test
    fun singleLocationObject() {
        val json = """{"latitude":1,"longitude":2,"elevation":0,"hourly":{"time":[0],"wind_speed_10m":[1.5],"wind_direction_10m":[90]}}"""
        val pts = OpenMeteo.parse(json, listOf(LatLon(1.0, 2.0)))
        assertEquals(1, pts.size)
        assertEquals(1.5, pts[0].profiles[0][0].speedMs, 1e-9)
    }

    @Test(expected = ApiException::class)
    fun apiErrorThrows() {
        OpenMeteo.parse("""{"error":true,"reason":"Daily API request limit exceeded."}""", emptyList())
    }

    @Test
    fun coordinateSearch() {
        assertEquals(LatLon(40.015, -105.27), Geocoding.parseCoordinates("40.015, -105.27"))
        assertEquals(LatLon(-33.9, 151.2), Geocoding.parseCoordinates("-33.9 151.2"))
        assertNull(Geocoding.parseCoordinates("Boulder"))
        assertNull(Geocoding.parseCoordinates("95, 10"))
    }

    @Test
    fun temperatureUsesUnitLetterNotDegreeSign() {
        assertEquals("-3C", TempUnit.C.format(-3.2))
        assertEquals("27F", TempUnit.F.format(-2.8))
    }
}
