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

    /** Distance in the unit that goes with the speed unit: nautical miles with knots, miles with mph. */
    fun distance(km: Double, unit: SpeedUnit): String = when (unit) {
        SpeedUnit.KT -> "${(km / 1.852).roundToInt()} nm"
        SpeedUnit.MPH -> "${(km / 1.609344).roundToInt()} mi"
        else -> "${km.roundToInt()} km"
    }

    /** An airport report's wind: "030° 22 (39) kt", "VRB 3 kt" or "Calm". */
    fun metarWind(m: dev.windsaloft.data.Metar, unit: SpeedUnit): String {
        if (m.calm) return "Calm"
        val ms = { kt: Int -> kt / dev.windsaloft.wind.KT_PER_MS }
        return (m.dirDeg?.let { String.format(Locale.US, "%03d°", it % 360) } ?: "VRB") + " " +
            speed(ms(m.speedKt), unit) + (m.gustKt?.let { " (${speed(ms(it), unit)})" } ?: "") + " " + unit.label
    }

    fun direction(deg: Double): String = String.format(Locale.US, "%03d°", deg.roundToInt() % 360)

    fun coords(p: LatLon): String {
        val ns = if (p.lat >= 0) "N" else "S"
        val ew = if (p.lon >= 0) "E" else "W"
        return String.format(Locale.US, "%.4f°%s %.4f°%s", abs(p.lat), ns, abs(p.lon), ew)
    }

    /** "BKN 4,300–7,800 · SCT 12,100–13,000" (or "BKN 4.3k–7.8k" when [short]). */
    fun clouds(layers: List<dev.windsaloft.wind.CloudLayer>?, short: Boolean): String = when {
        layers == null -> "no cloud data"
        layers.isEmpty() -> "clear"
        else -> layers.joinToString(if (short) ", " else " · ") { l ->
            "${l.cover.name} ${cloudFt(l.baseFt, short)}–${cloudFt(l.topFt, short)}"
        }
    }

    private fun cloudFt(ft: Int, short: Boolean): String {
        val hundreds = Math.round(ft / 100.0).toInt()
        return when {
            ft == 0 -> "SFC"
            short && hundreds >= 10 -> if (hundreds % 10 == 0) "${hundreds / 10}k" else "${hundreds / 10}.${hundreds % 10}k"
            else -> feet(hundreds * 100)
        }
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
