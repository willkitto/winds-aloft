package dev.windsaloft.data

import dev.windsaloft.wind.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class PlacesTest {
    @Test
    fun recentsDedupeAndCap() {
        var list = emptyList<SavedPlace>()
        repeat(25) { i -> list = SavedPlace.pushRecent(list, SavedPlace("p$i", LatLon(40.0 + i, -105.0), i.toLong())) }
        assertEquals(SavedPlace.MAX_RECENTS, list.size)
        assertEquals("p24", list.first().name)
        // Re-picking (nearly) the same spot moves it to the top instead of adding a second entry.
        list = SavedPlace.pushRecent(list, SavedPlace("again", LatLon(50.0004, -104.9996), 99))
        assertEquals(SavedPlace.MAX_RECENTS, list.size)
        assertEquals("again", list.first().name)
        assertEquals(1, list.count { it.sameSpot(LatLon(50.0, -105.0)) })
    }

    @Test
    fun encodeRoundTrip() {
        val list = listOf(SavedPlace("Skydive \"DZ\"", LatLon(40.1, -105.2), 5), SavedPlace(null, LatLon(-33.9, 151.2), 6))
        assertEquals(list, SavedPlace.decodeList(SavedPlace.encodeList(list)))
        assertEquals(emptyList<SavedPlace>(), SavedPlace.decodeList("not json"))
    }

    @Test
    fun windCacheKeepsOnlyListedSpots() {
        val dir = Files.createTempDirectory("winds").toFile()
        val cache = WindCache(dir)
        val dz = LatLon(40.123456, -105.2)
        val other = LatLon(41.0, -104.0)
        cache.save(dz, "gfs_seamless", "{\"a\":1}", 1000L, 18_000)
        cache.save(other, "gfs_seamless", "{}", 2000L, 14_000)
        assertEquals(WindCache.Entry("{\"a\":1}", 1000L, 18_000), cache.load(LatLon(40.12346, -105.2), "gfs_seamless"))
        assertEquals(2000L, cache.fetchedAt(other, "gfs_seamless"))
        assertNull(cache.load(dz, "icon_seamless"))
        cache.prune(listOf(dz))
        assertNull(cache.load(other, "gfs_seamless"))
        assertEquals(1000L, cache.fetchedAt(dz, "gfs_seamless"))
        dir.deleteRecursively()
    }
}
