package dev.windsaloft.map

import kotlin.math.roundToInt

/** Maps altitude to a colour on the "Turbo" ramp: low = blue, mid = green/yellow, high = red. */
object AltitudeColors {
    fun argb(altitudeFt: Int, ceilingFt: Int): Int {
        val f = (altitudeFt.toDouble() / ceilingFt.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        return turbo(0.1 + 0.85 * f)
    }

    /** 0 kt (blue) … 50 kt (red). */
    fun speed(kt: Double): Int = turbo(0.12 + 0.83 * (kt / 50.0).coerceIn(0.0, 1.0))

    /** −40 °C (deep blue) … +35 °C (red). */
    fun temperature(c: Double): Int = turbo(0.08 + 0.87 * ((c + 40.0) / 75.0).coerceIn(0.0, 1.0))

    /** Polynomial approximation of Google's Turbo colormap (Apache 2.0). */
    fun turbo(xIn: Double): Int {
        val x = xIn.coerceIn(0.0, 1.0)
        val r = 0.13572138 + x * (4.61539260 + x * (-42.66032258 + x * (132.13108234 + x * (-152.94239396 + x * 59.28637943))))
        val g = 0.09140261 + x * (2.19418839 + x * (4.84296658 + x * (-14.18503333 + x * (4.27729857 + x * 2.82956604))))
        val b = 0.10667330 + x * (12.64194608 + x * (-60.58204836 + x * (110.36276771 + x * (-89.90310912 + x * 27.34824973))))
        fun c(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }
}

/** Distinct colours for compared models, by selection order. Bright enough to read on maps with a dark halo. */
object ModelColors {
    private val COLORS = intArrayOf(0xFF22C3FF.toInt(), 0xFFFF4FA3.toInt(), 0xFFFFC21A.toInt())

    fun argb(slot: Int): Int = COLORS[slot.mod(COLORS.size)]
}
