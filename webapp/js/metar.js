// "Observed now": the latest airport reports near the forecast spot, from NOAA's api.weather.gov
// (US only, CORS-enabled). aviationweather.gov has no CORS headers, so a browser can't use it.

const API = "https://api.weather.gov";

/** Rough boxes for the areas api.weather.gov covers (lower 48, Alaska, Hawaii, Puerto Rico, Guam). */
const US_BOXES = [
  [24, 49.6, -125, -66.5], [51, 71.6, -179.9, -129.9], [18.8, 22.4, -160.5, -154.6],
  [17.8, 18.6, -67.4, -65.2], [13.2, 13.7, 144.6, 145],
];
export const inUS = (lat, lon) => US_BOXES.some(([a, b, c, d]) => lat >= a && lat <= b && lon >= c && lon <= d);

function distanceKm(a, b) {
  const R = 6371, rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad, dLon = (b.lon - a.lon) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

async function getJson(url, signal) {
  const r = await fetch(url, { headers: { Accept: "application/geo+json" }, signal });
  if (r.status === 404) { const e = new Error("not covered"); e.notCovered = true; throw e; }
  if (!r.ok) throw new Error(`HTTP ${r.status}`);
  return r.json();
}

/** A quantity in m/s from {unitCode, value}. */
function speedMs(q) {
  if (!q || q.value == null) return null;
  const u = q.unitCode || "";
  if (u.endsWith("km_h-1")) return q.value / 3.6;
  if (u.endsWith("m_s-1")) return q.value;
  if (u.includes("kn")) return q.value / 1.943844;
  return q.value / 3.6;
}

const stationCache = new Map(); // "lat,lon" -> nearest stations

/**
 * The nearest `max` stations (within 100 km) with their latest observation:
 * [{id, name, lat, lon, distKm, obs: {time (ms), dir, sp, gust (m/s), tempC, raw} | null}].
 * Throws {notCovered} outside the US.
 */
export async function nearbyObservations(spot, { max = 4, signal } = {}) {
  const key = `${spot.lat.toFixed(3)},${spot.lon.toFixed(3)}`;
  let stations = stationCache.get(key);
  if (!stations) {
    const d = await getJson(`${API}/points/${spot.lat.toFixed(4)},${spot.lon.toFixed(4)}/stations`, signal);
    stations = (d.features || []).map(f => {
      const [lon, lat] = f.geometry?.coordinates || [];
      return { id: f.properties?.stationIdentifier, name: f.properties?.name || "", lat, lon };
    }).filter(s => s.id && Number.isFinite(s.lat) && Number.isFinite(s.lon))
      .map(s => ({ ...s, distKm: distanceKm(spot, s) }))
      .filter(s => s.distKm <= 100)
      .sort((a, b) => a.distKm - b.distKm)
      .slice(0, max);
    stationCache.set(key, stations);
  }
  return Promise.all(stations.map(async s => {
    try {
      const o = (await getJson(`${API}/stations/${encodeURIComponent(s.id)}/observations/latest`, signal)).properties || {};
      const obs = {
        time: Date.parse(o.timestamp),
        dir: o.windDirection?.value ?? null,
        sp: speedMs(o.windSpeed),
        gust: speedMs(o.windGust),
        tempC: o.temperature?.value ?? null,
        raw: o.rawMessage || "",
      };
      // The decoded fields are sometimes empty even though the METAR has the wind: read it from there.
      const m = /\b(\d{3}|VRB)(\d{2,3})(?:G(\d{2,3}))?(KT|MPS)\b/.exec(obs.raw);
      if (m && obs.sp == null) {
        const f = m[4] === "MPS" ? 1 : 1 / 1.943844;
        obs.sp = +m[2] * f;
        obs.dir = m[1] === "VRB" ? null : +m[1];
        obs.gust = m[3] ? +m[3] * f : null;
      }
      const t = /\s(M?\d{2})\/(M?\d{2})?\s/.exec(obs.raw + " ");
      if (t && obs.tempC == null) obs.tempC = +t[1].replace("M", "-");
      return { ...s, obs };
    } catch (e) {
      if (e.name === "AbortError") throw e;
      return { ...s, obs: null };
    }
  }));
}
