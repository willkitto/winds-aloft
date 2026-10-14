package dev.windsaloft.ui

import dev.windsaloft.data.SpeedUnit
import dev.windsaloft.wind.LatLon
import dev.windsaloft.wind.M_PER_FT
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object Format {
    private val local = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
    private val zulu = DateTimeFormatter.ofPattern("HHmm'Z'", Locale.US)

    fun time(epochSec: Long): String {
        val i = Instant.ofEpochSecond(epochSec)
        return local.format(i.atZone(ZoneId.systemDefault())) + " · " + zulu.format(i.atZone(ZoneOffset.UTC))
    }

    fun feet(ft: Int): String = String.format(Locale.getDefault(), "%,d", ft)

    fun elevation(m: Double): String = feet((m / M_PER_FT).roundToInt()) + " ft"

    fun speed(ms: Double, unit: SpeedUnit): String = (ms * unit.perMs).roundToInt().toString()

    fun direction(deg: Double): String = String.format(Locale.US, "%03d°", deg.roundToInt() % 360)

    fun coords(p: LatLon): String {
        val ns = if (p.lat >= 0) "N" else "S"
        val ew = if (p.lon >= 0) "E" else "W"
        return String.format(Locale.US, "%.4f°%s %.4f°%s", abs(p.lat), ns, abs(p.lon), ew)
    }

    fun age(fetchedAtMs: Long): String {
        val min = ((System.currentTimeMillis() - fetchedAtMs) / 60_000L).coerceAtLeast(0)
        return when {
            min < 1 -> "just now"
            min < 60 -> "$min min ago"
            min < 48 * 60 -> "${min / 60} h ago"
            else -> "${min / (24 * 60)} days ago"
        }
    }
}
