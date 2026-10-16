// Persistence: settings / places / camera in localStorage (small), raw forecasts in IndexedDB
// (one entry per spot and model, like data/WindCache.kt), so saved and recent places work offline.
import { MODELS } from "./wind.js";

export const DEMO = new URLSearchParams(location.search).has("demo");
const PREFIX = DEMO ? "wa-demo-" : "wa-";

export function load(key, fallback = null) {
  try {
    const v = localStorage.getItem(PREFIX + key);
    return v == null ? fallback : JSON.parse(v);
  } catch { return fallback; }
}
export function save(key, value) {
  try {
    if (value == null) localStorage.removeItem(PREFIX + key);
    else localStorage.setItem(PREFIX + key, JSON.stringify(value));
  } catch { /* storage full or blocked: the app keeps working for this session */ }
}

export const MAX_MODELS = 3;
export const CEILINGS = [14000, 18000, 24000, 30000];
export const DEFAULT_SETTINGS = {
  unit: "kt", ref: "MSL", models: [MODELS[0].id], ceilingFt: 18000, layer: "topo",
  layout: "stacked", colorMode: "auto", tempUnit: "C", showTemperature: true, showClouds: true,
  anchor: "tail", showDirection: false, showSpeed: true, glyph: "arrow",
};

export function loadSettings() {
  const s = { ...DEFAULT_SETTINGS, ...(load("settings") || {}) };
  s.models = (Array.isArray(s.models) ? s.models : []).filter(id => MODELS.some(m => m.id === id))
    .filter((id, i, a) => a.indexOf(id) === i).slice(0, MAX_MODELS);
  if (!s.models.length) s.models = DEFAULT_SETTINGS.models.slice();
  if (!CEILINGS.includes(s.ceilingFt)) s.ceilingFt = DEFAULT_SETTINGS.ceilingFt;
  return s;
}

// ---------------------------------------------------------------- places (data/Places.kt)

/** Within ~100 m counts as the same spot, so re-picking a drop zone doesn't add duplicates. */
export const SAME_DEG = 0.001;
export const MAX_RECENTS = 20;
export const sameSpot = (a, b) => Math.abs(a.lat - b.lat) < SAME_DEG && Math.abs(a.lon - b.lon) < SAME_DEG;
/** Puts place first, drops any earlier entry for the same spot, keeps at most max. */
export const pushRecent = (list, place, max = MAX_RECENTS) => [place, ...list.filter(p => !sameSpot(p, place))].slice(0, max);
export const spotKey = p => `${p.lat.toFixed(4)}_${p.lon.toFixed(4)}`;
const entryKey = (p, model) => `${spotKey(p)}_${model}`;

// ---------------------------------------------------------------- forecast cache (IndexedDB)

const DB_NAME = DEMO ? "winds-aloft-demo" : "winds-aloft";
let dbPromise = null;
const memory = new Map(); // fallback when IndexedDB is unavailable (e.g. some private modes)

function db() {
  if (!dbPromise) {
    dbPromise = new Promise(resolve => {
      try {
        const req = indexedDB.open(DB_NAME, 1);
        req.onupgradeneeded = () => req.result.createObjectStore("forecasts", { keyPath: "key" });
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => resolve(null);
        req.onblocked = () => resolve(null);
      } catch { resolve(null); }
    });
  }
  return dbPromise;
}

function tx(mode, fn) {
  return db().then(d => new Promise(resolve => {
    if (!d) { resolve(fn(null)); return; }
    try {
      const t = d.transaction("forecasts", mode);
      const store = t.objectStore("forecasts");
      const out = fn(store);
      t.oncomplete = () => resolve(out && "result" in out ? out.result : out);
      t.onerror = t.onabort = () => resolve(null);
    } catch { resolve(null); }
  }));
}

/** Small index of what is saved: {entryKey: {fetchedAt, ceiling}} (kept in localStorage for quick reads). */
let times = load("windTimes", {});
const saveTimes = () => save("windTimes", times);

export const cache = {
  async put(spot, model, json, fetchedAt, ceiling) {
    const key = entryKey(spot, model);
    const rec = { key, spot: spotKey(spot), model, fetchedAt, ceiling, json };
    memory.set(key, rec);
    await tx("readwrite", s => s && s.put(rec));
    times[key] = { fetchedAt, ceiling };
    saveTimes();
  },
  async get(spot, model) {
    const key = entryKey(spot, model);
    const rec = await tx("readonly", s => (s ? s.get(key) : null));
    return rec || memory.get(key) || null;
  },
  fetchedAt(spot, model) { return times[entryKey(spot, model)]?.fetchedAt ?? null; },
  /** Newest download time for a spot over the given models. */
  spotTime(spot, models) {
    const t = models.map(m => this.fetchedAt(spot, m)).filter(v => v != null);
    return t.length ? Math.max(...t) : null;
  },
  /** Deletes everything except the spots in keep (favourites, recents and the current pin). */
  async prune(keep) {
    const keepSpots = new Set(keep.map(spotKey));
    const drop = Object.keys(times).filter(k => !keepSpots.has(k.split("_").slice(0, 2).join("_")));
    if (!drop.length) return;
    for (const k of drop) { delete times[k]; memory.delete(k); }
    saveTimes();
    await tx("readwrite", s => { if (s) for (const k of drop) s.delete(k); return null; });
  },
  count() { return Object.keys(times).length; },
};
