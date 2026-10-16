package dev.windsaloft.data

import dev.windsaloft.wind.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetarTest {
    // Trimmed from a real aviationweather.gov response (Santa Ana winds over Orange County).
    private val json = """
        [{"icaoId":"KAJO","obsTime":1792115760,"temp":28.3,"wdir":90,"wspd":6,"wgst":15,
          "rawOb":"METAR KAJO 160156Z AUTO 09006G15KT 10SM CLR 28/03 A2997","lat":33.8956,"lon":-117.5961,"name":"Corona Muni, CA, US"},
         {"icaoId":"KSNA","obsTime":1792115580,"temp":31.1,"wdir":30,"wspd":22,"wgst":39,
          "rawOb":"METAR KSNA 160153Z 03022G39KT 10SM FEW025 31/02 A2990","lat":33.6798,"lon":-117.8675,"name":"Santa Ana/Wayne Arpt, CA, US"},
         {"icaoId":"KSNA","obsTime":1792111980,"temp":30.0,"wdir":40,"wspd":18,
          "rawOb":"METAR KSNA 160053Z 04018KT","lat":33.6798,"lon":-117.8675,"name":"Santa Ana/Wayne Arpt, CA, US"},
         {"icaoId":"KLGB","obsTime":1792115580,"temp":28.9,"wdir":"VRB","wspd":3,
          "rawOb":"METAR KLGB 160153Z VRB03KT 10SM CLR 29/03 A2991","lat":33.8118,"lon":-118.1472,"name":"Long Beach Arpt, CA, US"}]
    """.trimIndent()

    @Test
    fun newestReportPerStationNearestFirst() {
        val newport = LatLon(33.6189, -117.9298)
        val list = Metars.parse(json, newport)
        assertEquals(listOf("KSNA", "KLGB", "KAJO"), list.map { it.icao })
        val sna = list.first()
        assertEquals(22, sna.speedKt)
        assertEquals(39, sna.gustKt)
        assertEquals(30, sna.dirDeg)
        assertEquals(1792115580L, sna.obsTimeSec) // the newer of the two KSNA reports
        val lgb = list[1]
        assertNull(lgb.dirDeg) // variable
        assertNull(lgb.gustKt)
        assertEquals("NE", Metars.bearingPoint(newport, sna.location))
        assertEquals(8.8, Metars.distanceKm(newport, sna.location), 0.5)
    }

    @Test
    fun windText() {
        val m = Metars.parse(json, LatLon(33.6, -117.9)).first()
        assertEquals("030° 22 (39) kt", dev.windsaloft.ui.Format.metarWind(m, SpeedUnit.KT))
        assertEquals("030° 25 (45) mph", dev.windsaloft.ui.Format.metarWind(m, SpeedUnit.MPH))
        assertTrue(Metars.url(LatLon(33.6, -117.9), 0.6).contains("bbox=33.000,"))
        assertEquals(emptyList<Metar>(), Metars.parse("", LatLon(0.0, 0.0)))
    }
}
