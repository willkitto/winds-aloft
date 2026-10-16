// Wind maths and the Open-Meteo request/parse, ported from the Android app:
// wind/WindMath.kt (u/v interpolation, clouds, the surface row with gusts) and data/OpenMeteo.kt.

export const M_PER_FT = 0.3048;
export const KT_PER_MS = 1.943844;

/** Models with upper-air (pressure-level) winds. ECMWF's 9 km "ecmwf_ifs" has none, so it's not listed. */
export const MODELS = [
  { id: "best_match", label: "Best match (auto)", short: "Best" },
  { id: "ncep_gfs_seamless", label: "NOAA GFS + HRRR", short: "GFS" },
  { id: "ncep_hrrr_conus", label: "NOAA HRRR (US only, ~2 days)", short: "HRRR" },
  { id: "ecmwf_ifs025", label: "ECMWF IFS 0.25°", short: "ECMWF" },
  { id: "icon_seamless", label: "DWD ICON", short: "ICON" },
  { id: "gem_seamless", label: "Canada GEM", short: "GEM" },
  { id: "ukmo_seamless", label: "UK Met Office", short: "UKMO" },
];
export const modelLabel = id => MODELS.find(m => m.id === id)?.label ?? id;
export const modelShort = id => MODELS.find(m => m.id === id)?.short ?? id;

/** Pressure levels (hPa) paired with their standard-atmosphere height in feet. */
const PRESSURE_LEVELS = [
  [1000, 364], [975, 1060], [950, 1773], [925, 2500], [900, 3243], [850, 4781],
  [800, 6394], [750, 8091], [700, 9882], [650, 11780], [600, 13801], [550, 15962],
  [500, 18289], [450, 20812], [400, 23574], [350, 26631], [300, 30065], [250, 33999],
];
/** Near-surface wind heights (m AGL) and the temperature height paired with each. */
const SURFACE_LEVELS = [[10, 2], [80, 80], [120, 120], [180, 180]];
export const FORECAST_DAYS = 4;

/** Pressure levels needed to cover up to ceilingFt, including one level above it for interpolation. */
export function levelsFor(ceilingFt) {
  const out = [];
  for (const [hpa, stdFt] of PRESSURE_LEVELS) {
    out.push(hpa);
    if (stdFt > ceilingFt + 1500) break;
  }
  return out;
}

export function hourlyVariables(ceilingFt) {
  const v = [];
  for (const [h, t] of SURFACE_LEVELS) v.push(`wind_speed_${h}m`, `wind_direction_${h}m`, `temperature_${t}m`);
  v.push("wind_gusts_10m");
  for (const p of levelsFor(ceilingFt)) {
    v.push(`wind_speed_${p}hPa`, `wind_direction_${p}hPa`, `geopotential_height_${p}hPa`, `cloud_cover_${p}hPa`, `temperature_${p}hPa`);
  }
  return v;
}

export function forecastUrl(lat, lon, modelId, ceilingFt) {
  return "https://api.open-meteo.com/v1/forecast" +
    `?latitude=${lat.toFixed(4)}&longitude=${lon.toFixed(4)}` +
    `&hourly=${hourlyVariables(ceilingFt).join(",")}` +
    `&models=${modelId}` +
    `&wind_speed_unit=ms&timeformat=unixtime&forecast_days=${FORECAST_DAYS}`;
}

// ---------------------------------------------------------------- vector maths

/** Speed + meteorological direction (FROM) -> u (east) / v (north). */
export function toUv(speed, dirFromDeg) {
  const r = dirFromDeg * Math.PI / 180;
  return [-speed * Math.sin(r), -speed * Math.cos(r)];
}

/** u / v -> [speed, direction FROM in [0, 360)]. */
export function fromUv(u, v) {
  let dir = Math.atan2(-u, -v) * 180 / Math.PI;
  if (dir < 0) dir += 360;
  if (dir >= 360) dir -= 360;
  return [Math.hypot(u, v), dir];
}

/** Largest gap between the ground and the lowest sample over which the lowest sample is extended down. */
const SURFACE_CLAMP_M = 50;
/** Cover at least this (%) counts as a cloud layer. */
export const CLOUD_LAYER_MIN_PCT = 25;

/** Cloud cover (%) at heightM, interpolated between pressure levels sorted by height. */
export function cloudAt(levels, heightM) {
  if (!levels.length) return null;
  const first = levels[0];
  if (heightM <= first.h) return first.h - heightM <= 500 ? first.pct : null;
  for (let k = 1; k < levels.length; k++) {
    const hi = levels[k];
    if (hi.h >= heightM) {
      const lo = levels[k - 1];
      return lo.pct + (hi.pct - lo.pct) * (heightM - lo.h) / (hi.h - lo.h);
    }
  }
  return null;
}

/** Largest vector difference (m/s) between any two winds; null with fewer than two. */
export function maxVectorDifference(winds) {
  if (winds.length < 2) return null;
  const uv = winds.map(w => toUv(w.sp, w.d));
  let max = 0;
  for (let i = 0; i < uv.length; i++) for (let j = i + 1; j < uv.length; j++) {
    max = Math.max(max, Math.hypot(uv[i][0] - uv[j][0], uv[i][1] - uv[j][1]));
  }
  return max;
}

/**
 * Linearly interpolates the wind vector (and temperature) to targetM metres MSL. samples are sorted
 * by height ({h, sp, d, t}). Null below ground or above the highest sample.
 */
export function interpolate(samples, targetM, groundM) {
  if (!samples.length || targetM < groundM - 1) return null;
  const first = samples[0];
  if (targetM <= first.h) {
    return first.h - groundM <= SURFACE_CLAMP_M ? { ...first, h: targetM, t: interpolateTemp(samples, targetM) } : null;
  }
  if (targetM > samples[samples.length - 1].h) return null;
  const hiIdx = samples.findIndex(s => s.h >= targetM);
  const hi = samples[hiIdx], lo = samples[hiIdx - 1];
  const span = hi.h - lo.h;
  const t = span <= 0 ? 0 : (targetM - lo.h) / span;
  const [u0, v0] = toUv(lo.sp, lo.d), [u1, v1] = toUv(hi.sp, hi.d);
  const [sp, d] = fromUv(u0 + (u1 - u0) * t, v0 + (v1 - v0) * t);
  return { h: targetM, sp, d, t: interpolateTemp(samples, targetM) };
}

/**
 * Temperature at targetM, interpolated only between samples that have one: some models give wind
 * but no temperature at 80–180 m, and those samples mustn't blank the levels around them.
 */
export function interpolateTemp(samples, targetM) {
  let lo = null, hi = null;
  for (const s of samples) {
    if (s.t == null) continue;
    if (s.h <= targetM) lo = s; else { hi = s; break; }
  }
  if (lo && hi) return lo.t + (hi.t - lo.t) * (targetM - lo.h) / (hi.h - lo.h);
  if (lo && targetM - lo.h <= SURFACE_CLAMP_M) return lo.t;
  if (hi && hi.h - targetM <= SURFACE_CLAMP_M) return hi.t;
  return null;
}

// ---------------------------------------------------------------- parsing

const num = v => (typeof v === "number" && Number.isFinite(v) ? v : null);

/**
 * Parses one Open-Meteo forecast response into a forecast point:
 * { elev, times, index (epoch -> hour), profiles[hour] = sorted samples, surface[hour] = 10 m sample,
 *   clouds[hour] = sorted {h, pct}, gusts[hour] (m/s or null), hasGusts }.
 * timeShift (seconds) is added to every time (used by the demo data to make it look current).
 */
export function parseForecast(json, timeShift = 0) {
  if (json && json.error) throw new Error(json.reason || "Unknown API error");
  const H = json?.hourly;
  if (!H || !Array.isArray(H.time)) throw new Error("Response has no hourly data");
  const elev = num(json.elevation) ?? 0;
  const times = H.time.map(t => t + timeShift);
  const series = name => (Array.isArray(H[name]) ? H[name] : null);

  const levels = [
    ...SURFACE_LEVELS.map(([h, t]) => ({ fixed: elev + h, surface: h === 10, sp: series(`wind_speed_${h}m`), d: series(`wind_direction_${h}m`), t: series(`temperature_${t}m`) })),
    ...PRESSURE_LEVELS.map(([p]) => ({ gph: series(`geopotential_height_${p}hPa`), sp: series(`wind_speed_${p}hPa`), d: series(`wind_direction_${p}hPa`), t: series(`temperature_${p}hPa`) })),
  ];
  const profiles = [], surface = [];
  for (let i = 0; i < times.length; i++) {
    const out = [];
    let sfc = null;
    for (const l of levels) {
      const sp = num(l.sp?.[i]), d = num(l.d?.[i]);
      if (sp == null || d == null) continue;
      let h;
      if (l.gph) {
        h = num(l.gph[i]);
        // Pressure levels at or below ground are extrapolated by the model; skip them.
        if (h == null || h <= elev + 10) continue;
      } else if (l.fixed != null) h = l.fixed;
      else continue;
      const s = { h, sp, d, t: num(l.t?.[i]) };
      out.push(s);
      if (l.surface) sfc = s;
    }
    out.sort((a, b) => a.h - b.h);
    profiles.push(out);
    surface.push(sfc);
  }
  const cloudLevels = PRESSURE_LEVELS
    .map(([p]) => [series(`geopotential_height_${p}hPa`), series(`cloud_cover_${p}hPa`)])
    .filter(([g, c]) => g && c);
  const clouds = times.map((_, i) => cloudLevels
    .map(([g, c]) => ({ h: num(g[i]), pct: num(c[i]) }))
    .filter(c => c.h != null && c.pct != null && c.h > elev)
    .sort((a, b) => a.h - b.h));
  const gustSeries = series("wind_gusts_10m");
  const gusts = times.map((_, i) => num(gustSeries?.[i]));
  const index = new Map(times.map((t, i) => [t, i]));
  return { elev, times, index, profiles, surface, clouds, gusts, hasGusts: !!gustSeries };
}

export const lastEpoch = fp => fp.times[fp.times.length - 1] ?? 0;
const hourIndex = (fp, epoch) => fp.index.get(epoch) ?? -1;

/** The 10 m wind for hour i, labelled with altitudeFt, with the gust forecast when there is one. */
function surfaceAt(fp, i, altitudeFt) {
  const s = fp.surface[i];
  if (!s) return null;
  return { alt: altitudeFt, d: s.d, sp: s.sp, t: s.t, gust: fp.gusts[i], surface: true };
}

/**
 * Winds at each altitude ({alt, d, sp, t, gust, surface} or null). The ground wind (with gusts)
 * fills the 0 ft AGL slot, or in MSL the slot just below the lowest altitude above ground, so the
 * column starts with the surface wind.
 */
export function windsAt(fp, epoch, altitudesFt, ref) {
  const i = hourIndex(fp, epoch);
  if (i < 0) return altitudesFt.map(() => null);
  const samples = fp.profiles[i];
  const base = ref === "AGL" ? fp.elev : 0;
  const winds = altitudesFt.map(ft => {
    if (ref === "AGL" && ft === 0) return surfaceAt(fp, i, 0);
    const s = interpolate(samples, base + ft * M_PER_FT, fp.elev);
    return s ? { alt: ft, d: s.d, sp: s.sp, t: s.t, gust: null, surface: false } : null;
  });
  if (ref === "MSL") {
    const first = winds.findIndex(w => w != null);
    if (first > 0 && altitudesFt[first - 1] * M_PER_FT < fp.elev) {
      winds[first - 1] = surfaceAt(fp, i, Math.round(fp.elev / M_PER_FT));
    }
  }
  return winds;
}

export const cloudCover = pct => (pct >= 87.5 ? "OVC" : pct >= 50 ? "BKN" : "SCT");

/**
 * Cloud layers between loFt and hiFt (display reference), found by scanning every 100 ft for at
 * least 25 % cover: [{base, top, max, cover}]. Null when the model gives no cloud data.
 */
export function cloudLayers(fp, epoch, ref, loFt, hiFt) {
  const i = hourIndex(fp, epoch);
  const levels = fp.clouds[i];
  if (i < 0 || !levels || !levels.length) return null;
  const base = ref === "AGL" ? fp.elev : 0;
  const out = [];
  let start = -1, last = -1, max = 0;
  for (let ft = loFt; ft <= hiFt; ft += 100) {
    const h = base + ft * M_PER_FT;
    const pct = h < fp.elev ? null : cloudAt(levels, h);
    if (pct != null && pct >= CLOUD_LAYER_MIN_PCT) {
      if (start < 0) { start = ft; max = pct; } else max = Math.max(max, pct);
      last = ft;
    } else if (start >= 0) {
      out.push({ base: start, top: last, max, cover: cloudCover(max) });
      start = -1;
    }
  }
  if (start >= 0) out.push({ base: start, top: last, max, cover: cloudCover(max) });
  return out;
}

/** Parses "lat, lon" input, e.g. "40.015, -105.27". */
export function parseCoordinates(text) {
  const m = /^\s*(-?\d{1,2}(?:\.\d+)?)\s*[,\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*$/.exec(text);
  if (!m) return null;
  const lat = +m[1], lon = +m[2];
  return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180 ? { lat, lon } : null;
}
