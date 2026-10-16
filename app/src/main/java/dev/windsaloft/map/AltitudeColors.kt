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

    /**
     * Temperature ramp anchored on freezing: 0 °C is blue, colder gets steadily darker to black at
     * −40 °C; warmer runs cyan, green, yellow, orange to red at +40 °C.
     */
    fun temperature(c: Double): Int {
        val t = c.coerceIn(TEMP_STOPS.first().first, TEMP_STOPS.last().first)
        for (k in 1 until TEMP_STOPS.size) {
            val (t1, c1) = TEMP_STOPS[k]
            if (t <= t1) {
                val (t0, c0) = TEMP_STOPS[k - 1]
                return lerp(c0, c1, ((t - t0) / (t1 - t0)).toFloat())
            }
        }
        return TEMP_STOPS.last().second
    }

    private val TEMP_STOPS = listOf(
        -40.0 to 0xFF000000.toInt(),
        -30.0 to 0xFF0B1446.toInt(),
        -20.0 to 0xFF13288A.toInt(),
        -10.0 to 0xFF1F45C8.toInt(),
        0.0 to 0xFF2F7BFF.toInt(),
        5.0 to 0xFF22C3E6.toInt(),
        10.0 to 0xFF33CC66.toInt(),
        20.0 to 0xFFFFD23A.toInt(),
        30.0 to 0xFFFF8A1F.toInt(),
        40.0 to 0xFFE3262E.toInt(),
    )

    private fun lerp(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * f).roundToInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Outline colour that keeps a symbol visible: dark for light colours, light for very dark ones. */
    fun haloFor(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
        return if (luminance < 0.18) 0xCCFFFFFF.toInt() else 0xAA000000.toInt()
    }

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

/** The colour of one wind symbol for a colour setting; shared by the map and the table. */
object WindColors {
    /** [columnColor] is the model's colour when comparing (null for a single model). */
    fun of(mode: dev.windsaloft.data.ArrowColor, w: dev.windsaloft.wind.Wind, columnColor: Int?, ceilingFt: Int): Int = when (mode) {
        dev.windsaloft.data.ArrowColor.AUTO -> columnColor ?: AltitudeColors.argb(w.altitudeFt, ceilingFt)
        dev.windsaloft.data.ArrowColor.ALTITUDE -> AltitudeColors.argb(w.altitudeFt, ceilingFt)
        dev.windsaloft.data.ArrowColor.SPEED -> AltitudeColors.speed(w.speedMs * dev.windsaloft.wind.KT_PER_MS)
        dev.windsaloft.data.ArrowColor.TEMPERATURE -> w.tempC?.let { AltitudeColors.temperature(it) } ?: 0xFFFFFFFF.toInt()
        dev.windsaloft.data.ArrowColor.MODEL -> columnColor ?: ModelColors.argb(0)
        dev.windsaloft.data.ArrowColor.WHITE -> 0xFFFFFFFF.toInt()
    }
}

/** Distinct colours for compared models, by selection order. Bright enough to read on maps with a dark halo. */
/**
 * One colour per compared model, in selection order. The first seven are ordered so neighbouring
 * columns stay distinct for colour-blind viewers (checked with a CVD simulation); every column is also
 * labelled by name, so colour is never the only cue. The rest only appear with eight or more models.
 */
object ModelColors {
    private val COLORS = intArrayOf(
        0xFF22C3FF.toInt(), 0xFFFF7A2F.toInt(), 0xFF9B7BFF.toInt(), 0xFFFFC21A.toInt(), 0xFFFF4FA3.toInt(), 0xFF6BE05A.toInt(),
        0xFFF0F0F0.toInt(), 0xFF3ED6C0.toInt(), 0xFFFF4A4A.toInt(), 0xFFC9A86A.toInt(), 0xFF5A86FF.toInt(),
    )

    fun argb(slot: Int): Int = COLORS[slot.mod(COLORS.size)]
}
