// Colour ramps, ported from map/AltitudeColors.kt. Colours are [r, g, b] arrays; css() makes a CSS string.
import { KT_PER_MS } from "./wind.js";

const clamp01 = v => Math.min(1, Math.max(0, v));
export const css = (c, a = 1) => (a >= 1 ? `rgb(${c[0]},${c[1]},${c[2]})` : `rgba(${c[0]},${c[1]},${c[2]},${a})`);
const hex = h => [(h >> 16) & 255, (h >> 8) & 255, h & 255];

/** Polynomial approximation of Google's Turbo colormap (Apache 2.0). */
export function turbo(xIn) {
  const x = clamp01(xIn);
  const r = 0.13572138 + x * (4.61539260 + x * (-42.66032258 + x * (132.13108234 + x * (-152.94239396 + x * 59.28637943))));
  const g = 0.09140261 + x * (2.19418839 + x * (4.84296658 + x * (-14.18503333 + x * (4.27729857 + x * 2.82956604))));
  const b = 0.10667330 + x * (12.64194608 + x * (-60.58204836 + x * (110.36276771 + x * (-89.90310912 + x * 27.34824973))));
  const c = v => Math.round(clamp01(v) * 255);
  return [c(r), c(g), c(b)];
}

/** Altitude on the Turbo ramp: low = blue, mid = green/yellow, high = red. */
export const altitudeColor = (ft, ceilingFt) => turbo(0.1 + 0.85 * clamp01(ft / Math.max(1, ceilingFt)));

/** 0 kt (blue) … 50 kt (red). */
export const speedColor = kt => turbo(0.12 + 0.83 * clamp01(kt / 50));

/** Anchored on freezing: 0 C is blue, colder gets darker to black at −40; warmer runs to red at +40. */
const TEMP_STOPS = [
  [-40, 0x000000], [-30, 0x0B1446], [-20, 0x13288A], [-10, 0x1F45C8], [0, 0x2F7BFF],
  [5, 0x22C3E6], [10, 0x33CC66], [20, 0xFFD23A], [30, 0xFF8A1F], [40, 0xE3262E],
].map(([t, c]) => [t, hex(c)]);

export function temperatureColor(c) {
  const t = Math.min(40, Math.max(-40, c));
  for (let k = 1; k < TEMP_STOPS.length; k++) {
    const [t1, c1] = TEMP_STOPS[k];
    if (t <= t1) {
      const [t0, c0] = TEMP_STOPS[k - 1];
      const f = (t - t0) / (t1 - t0);
      return c0.map((v, i) => Math.round(v + (c1[i] - v) * f));
    }
  }
  return TEMP_STOPS[TEMP_STOPS.length - 1][1];
}

/** Outline that keeps a symbol visible: dark for light colours, light for very dark ones. */
export function haloFor(c) {
  const lum = (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) / 255;
  return lum < 0.18 ? "rgba(255,255,255,0.8)" : "rgba(0,0,0,0.667)";
}

/** Distinct colours for compared models, by selection order. */
export const MODEL_COLORS = [hex(0x22C3FF), hex(0xFF4FA3), hex(0xFFC21A)];
export const modelColor = slot => MODEL_COLORS[((slot % 3) + 3) % 3];
export const WHITE = [255, 255, 255];

/** The colour of one wind symbol for a colour setting; shared by the map and the table. */
export function windColor(mode, w, columnColor, ceilingFt) {
  switch (mode) {
    case "altitude": return altitudeColor(w.alt, ceilingFt);
    case "speed": return speedColor(w.sp * KT_PER_MS);
    case "temperature": return w.t != null ? temperatureColor(w.t) : WHITE;
    case "model": return columnColor ?? modelColor(0);
    case "white": return WHITE;
    default: return columnColor ?? altitudeColor(w.alt, ceilingFt); // auto
  }
}
