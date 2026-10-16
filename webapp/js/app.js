// Winds Aloft web app: state, map, panels. Mirrors the Android app's MainViewModel + WindsAloftApp.
import {
  MODELS, modelLabel, modelShort, forecastUrl, parseForecast, windsAt, cloudLayers, lastEpoch,
  maxVectorDifference, parseCoordinates, KT_PER_MS,
} from "./wind.js";
import { css, altitudeColor, speedColor, temperatureColor, modelColor, windColor } from "./colors.js";
import {
  SPEED_UNITS, fmtTime, fmtFeet, fmtElevation, fmtSpeed, fmtSpeedGust, fmtDir, fmtTemp, fmtCoords,
  fmtClouds, fmtAge, escapeHtml as esc,
} from "./format.js";
import { drawScene, DEFAULT_ANCHOR, STACK_LENGTH, STACK_FULL_KT, stackedFraction } from "./chart.js";
import { DEMO, load, save, loadSettings, MAX_MODELS, CEILINGS, sameSpot, pushRecent, MAX_RECENTS, spotKey, cache } from "./store.js";
import { inUS, nearbyObservations } from "./metar.js";

const $ = id => document.getElementById(id);
const $$ = sel => Array.from(document.querySelectorAll(sel));
const currentHour = () => Math.floor(Date.now() / 3600000) * 3600;
const LONG_PRESS_MS = 550;
const SNAP_PX = 36;            // dropping the chart this close to the pin re-attaches it
const STALE_MS = 60 * 60_000;  // saved places are refreshed when older than this

// ---------------------------------------------------------------- state

const S = {
  settings: loadSettings(),
  pin: null,           // {lat, lon}
  pinLabel: null,
  forecasts: {},       // model id -> parsed forecast
  fetchedAt: 0,        // newest download time (ms) of the shown forecasts
  loading: 0,
  nowHour: currentHour(),
  hourOffset: 0,
  alt: [0, 14],        // thousands of feet, inclusive
  pending: null,       // long-pressed spot waiting for confirmation
  chartAt: null,       // chart dragged away from the pin (null = on the pin)
  favorites: load("favorites", []),
  recents: load("recents", []),
  online: navigator.onLine !== false,
  panelMode: "full",   // full -> time -> hidden
  expanded: false,
  saving: null,        // [done, total] while downloading saved places
  obs: { key: null, status: "idle", list: [] },
  placesTab: null,
  renaming: null,
};
let profiles = [];     // per selected model: {model, slot, color, winds, clouds}

const comparing = () => S.settings.models.length > 1;
const selectedEpoch = () => S.nowHour + S.hourOffset * 3600;
const altitudes = () => { const a = []; for (let k = S.alt[0]; k <= S.alt[1]; k++) a.push(k * 1000); return a; };
const unitLabel = () => SPEED_UNITS[S.settings.unit].label;
const spd = ms => fmtSpeed(ms, S.settings.unit);
const tmp = c => fmtTemp(c, S.settings.tempUnit);

function maxHourOffset() {
  const fps = Object.values(S.forecasts);
  if (!fps.length) return 72;
  const last = Math.max(...fps.map(lastEpoch));
  return Math.min(120, Math.max(0, Math.round((last - S.nowHour) / 3600)));
}

// ---------------------------------------------------------------- map

const LAYERS = {
  topo: {
    label: "Topo", url: "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png",
    opts: { subdomains: "abc", maxNativeZoom: 17 },
    attribution: 'Map data © <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors, SRTM · Map style © <a href="https://opentopomap.org">OpenTopoMap</a> (CC-BY-SA)',
    short: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>, SRTM · © <a href="https://opentopomap.org">OpenTopoMap</a> (CC-BY-SA)',
  },
  streets: {
    label: "Streets", url: "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    opts: { maxNativeZoom: 19 },
    attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    short: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
  },
  satellite: {
    label: "Satellite", url: "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
    opts: { maxNativeZoom: 19 },
    attribution: 'Imagery © <a href="https://www.esri.com">Esri</a>, Maxar, Earthstar Geographics, and the GIS User Community',
    short: 'Imagery © <a href="https://www.esri.com">Esri</a>, Maxar, Earthstar Geographics',
  },
};
const WEATHER_ATTRIB = 'Weather: <a href="https://open-meteo.com">Open-Meteo.com</a>';

const map = L.map("map", {
  zoomControl: false,
  tapHold: false,          // our own long-press (550 ms) handles iOS; see attachLongPress()
  worldCopyJump: true,
  minZoom: 2, maxZoom: 19,
  fadeAnimation: true,
});
map.attributionControl.setPrefix('<a href="https://leafletjs.com">Leaflet</a>');
let tileLayer = null;
function setLayer() {
  const def = LAYERS[S.settings.layer] || LAYERS.topo;
  if (tileLayer) map.removeLayer(tileLayer);
  // crossOrigin: tiles come back as CORS (not opaque) responses, so the service worker can cache them cheaply.
  tileLayer = L.tileLayer(def.url, { ...def.opts, maxZoom: 19, crossOrigin: "anonymous", attribution: def.attribution }).addTo(map);
  $$(".attrib").forEach(e => { e.innerHTML = `${def.short} · ${WEATHER_ATTRIB}`; });
}
const obsLayer = L.layerGroup().addTo(map);

// ---------------------------------------------------------------- chart canvas

const canvas = $("chart");
const ctx = canvas.getContext("2d");
let dpr = 1;
function sizeCanvas() {
  dpr = Math.min(window.devicePixelRatio || 1, 3);
  const w = window.innerWidth, h = window.innerHeight;
  canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
  canvas.style.width = w + "px"; canvas.style.height = h + "px";
  scheduleDraw();
}

// Zoom animations scale the tiles with CSS for 250 ms while Leaflet already reports the final
// positions; tween the chart's anchor along with them so it doesn't jump ahead of the map.
let tween = null;
const ease = t => 1 - Math.pow(1 - t, 3);
map.on("zoomanim", () => {
  tween = { start: performance.now(), from: { pin: pt(S.pin), chart: pt(S.chartAt), pending: pt(S.pending) } };
  const step = () => { draw(); if (tween && performance.now() - tween.start < 260) requestAnimationFrame(step); else { tween = null; draw(); } };
  requestAnimationFrame(step);
});
function pt(ll) { return ll ? map.latLngToContainerPoint([ll.lat, ll.lon]) : null; }
function screenPoint(ll, which) {
  const p = pt(ll);
  if (!p || !tween || !tween.from[which]) return p;
  const k = ease(Math.min(1, (performance.now() - tween.start) / 250)), f = tween.from[which];
  return L.point(f.x + (p.x - f.x) * k, f.y + (p.y - f.y) * k);
}

let drawQueued = false;
function scheduleDraw() { if (!drawQueued) { drawQueued = true; requestAnimationFrame(() => { drawQueued = false; draw(); }); } }
map.on("move zoom viewreset resize", scheduleDraw);

let chartDrag = null; // {dx, dy, to: {lat, lon}}
let handlePt = null;
function draw() {
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, canvas.width / dpr, canvas.height / dpr);
  const s = S.settings;
  const chartLL = chartDrag?.to || S.chartAt;
  const scene = {
    pin: screenPoint(S.pin, "pin"),
    chart: chartLL ? screenPoint(chartLL, "chart") : null,
    pending: screenPoint(S.pending, "pending"),
    columns: profiles.map(p => ({ label: comparing() ? modelShort(p.model) : null, color: p.color, winds: p.winds, clouds: p.clouds })),
    altitudesFt: altitudes(),
    o: {
      layout: s.layout, glyph: s.glyph, anchor: s.anchor, colorMode: s.colorMode, showTemperature: s.showTemperature,
      showSpeed: s.showSpeed, showDirection: s.showDirection, showClouds: s.showClouds, ceilingFt: s.ceilingFt,
      speedLabel: spd, tempLabel: tmp,
    },
  };
  handlePt = drawScene(ctx, scene);
  const h = $("handle");
  if (handlePt) { h.hidden = false; h.style.left = handlePt.x + "px"; h.style.top = handlePt.y + "px"; } else h.hidden = true;
}

// Drag the chart by its handle; the forecast spot keeps its pin and a dashed leader joins them.
(function attachChartDrag() {
  const h = $("handle");
  h.addEventListener("pointerdown", e => {
    if (!S.pin) return;
    e.preventDefault();
    h.setPointerCapture(e.pointerId);
    const anchor = pt(S.chartAt || S.pin);
    chartDrag = { dx: anchor.x - e.clientX, dy: anchor.y - e.clientY, to: null };
  });
  h.addEventListener("pointermove", e => {
    if (!chartDrag) return;
    const ll = map.containerPointToLatLng([e.clientX + chartDrag.dx, e.clientY + chartDrag.dy]);
    chartDrag.to = { lat: ll.lat, lon: ll.lng };
    scheduleDraw();
  });
  const end = () => {
    if (!chartDrag) return;
    const to = chartDrag.to;
    chartDrag = null;
    if (to) {
      const a = pt(to), p = pt(S.pin);
      S.chartAt = Math.hypot(a.x - p.x, a.y - p.y) < SNAP_PX ? null : to;
      renderPin();
    }
    scheduleDraw();
  };
  h.addEventListener("pointerup", end);
  h.addEventListener("pointercancel", end);
})();

// ---------------------------------------------------------------- long-press to pick a spot

let lastProposal = 0;
function propose(latlng) {
  if (Date.now() - lastProposal < 800) return; // a long-press can also fire contextmenu (Android)
  lastProposal = Date.now();
  S.pending = { lat: latlng.lat, lon: latlng.lng ?? latlng.lon };
  try { navigator.vibrate?.(15); } catch { /* not supported */ }
  renderPending();
  scheduleDraw();
}
(function attachLongPress() {
  const el = map.getContainer();
  let lp = null;
  const cancel = () => { if (lp) { clearTimeout(lp.timer); lp = null; } };
  el.addEventListener("pointerdown", e => {
    $("results").hidden = true;
    autoPlace = null;
    if (!e.isPrimary || (e.pointerType === "mouse" && e.button !== 0)) { cancel(); return; }
    cancel();
    const x = e.clientX, y = e.clientY;
    lp = { x, y, timer: setTimeout(() => { lp = null; propose(map.containerPointToLatLng([x, y])); }, LONG_PRESS_MS) };
  }, { capture: true, passive: true });
  el.addEventListener("pointermove", e => { if (lp && Math.hypot(e.clientX - lp.x, e.clientY - lp.y) > 10) cancel(); }, { capture: true, passive: true });
  ["pointerup", "pointercancel", "pointerleave"].forEach(t => el.addEventListener(t, cancel, { capture: true, passive: true }));
  el.addEventListener("touchstart", e => { if (e.touches.length > 1) cancel(); }, { capture: true, passive: true });
  // Desktop: right-click. Leaflet passes the map position.
  map.on("contextmenu", e => { cancel(); propose(e.latlng); });
  // The long-press itself must not open iOS's callout or select anything.
  el.addEventListener("contextmenu", e => e.preventDefault());
})();

// ---------------------------------------------------------------- pin + forecasts

let pinGen = 0;

function setPin(loc, label = null, { zoom = null } = {}) {
  if (DEMO) {
    const d = nearestDemo(loc);
    if (d && !sameSpot(d, loc)) {
      toast(`Demo mode: showing the sample winds for ${d.name}, the nearest demo spot`);
      loc = { lat: d.lat, lon: d.lon }; label = d.name;
    }
  }
  const name = label ?? S.favorites.find(f => sameSpot(f, loc))?.name ?? null;
  S.pin = { lat: loc.lat, lon: loc.lon };
  S.pinLabel = name;
  S.forecasts = {}; S.fetchedAt = 0;
  S.pending = null; S.chartAt = null;
  $("results").hidden = true;
  S.recents = pushRecent(S.recents, { name, lat: loc.lat, lon: loc.lon, at: Date.now() });
  save("recents", S.recents);
  save("pin", { ...S.pin, label: name });
  pruneCache();
  S.obs = { key: null, status: "idle", list: [] };
  loadPin(true);
  refreshObs();
  render();
  keepAbovePanels(S.pin, zoom);
}

function clearPin() {
  pinGen++;
  S.pin = null; S.pinLabel = null; S.forecasts = {}; S.fetchedAt = 0; S.chartAt = null; S.expanded = false;
  S.obs = { key: null, status: "idle", list: [] };
  obsLayer.clearLayers();
  save("pin", null);
  render();
}

/** Shows the saved winds for the pin straight away (if any), then downloads fresh ones when online. */
async function loadPin(announceOffline) {
  const gen = ++pinGen, pin = S.pin, models = S.settings.models.slice();
  if (!pin) return;
  let newest = 0;
  const saved = {};
  await Promise.all(models.map(async m => {
    const e = await cache.get(pin, m);
    if (!e) return;
    try { saved[m] = parseForecast(e.json); newest = Math.max(newest, e.fetchedAt); } catch { /* corrupt entry */ }
  }));
  if (gen !== pinGen) return;
  if (Object.keys(saved).length) { S.forecasts = saved; S.fetchedAt = newest; render(); }
  if (S.online) await fetchPinNow(gen, pin, models);
  else if (announceOffline) {
    toast(Object.keys(saved).length ? `Offline: showing winds downloaded ${fmtAge(newest)}` : "Offline, and no saved winds for this spot");
  }
}

/** Fetches every selected model for the pin in parallel; one model failing doesn't affect the others. */
async function fetchPinNow(gen, pin, models) {
  S.loading++;
  S.forecasts = Object.fromEntries(Object.entries(S.forecasts).filter(([m]) => models.includes(m)));
  render();
  let offlineShown = false;
  await Promise.all(models.map(async m => {
    try {
      const fp = await download(pin, m, S.settings.ceilingFt);
      if (gen !== pinGen) return;
      S.forecasts = { ...S.forecasts, [m]: fp };
      S.fetchedAt = Date.now();
      S.nowHour = currentHour();
      render();
    } catch (e) {
      if (gen !== pinGen) return;
      if (e.network) {
        if (!offlineShown) {
          offlineShown = true;
          toast(S.fetchedAt ? `Offline: showing winds downloaded ${fmtAge(S.fetchedAt)}` : "Can't reach Open-Meteo, and no saved winds for this spot");
        }
      } else if (/limit/i.test(e.message)) {
        toast(`${modelLabel(m)}: ${e.message}`, { label: "Try demo data", href: "?demo=1" });
      } else toast(`${modelLabel(m)}: ${e.message}`);
    }
  }));
  S.loading--;
  render();
}

async function fetchWithTimeout(url, ms) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), ms);
  try { return await fetch(url, { signal: ctl.signal }); } finally { clearTimeout(t); }
}

/** Downloads one model's forecast for a spot and keeps the raw response for offline use. */
async function download(spot, model, ceilingFt) {
  let json;
  if (DEMO) json = await demoForecast(spot, model);
  else {
    let r;
    try { r = await fetchWithTimeout(forecastUrl(spot.lat, spot.lon, model, ceilingFt), 20000); } catch {
      setOnline(false);
      throw Object.assign(new Error("offline"), { network: true });
    }
    setOnline(true);
    json = await r.json().catch(() => null);
    if (!r.ok || !json || json.error) throw new Error(json?.reason || `HTTP ${r.status}`);
  }
  const fp = parseForecast(json);
  await cache.put(spot, model, json, Date.now(), ceilingFt);
  return fp;
}

// ---- demo data (?demo=1): the playground's captured forecast, shifted so it starts at the current hour

let demoData = null;
async function loadDemo() {
  if (!demoData) demoData = await fetch("demo/demo-data.json").then(r => r.json());
  return demoData;
}
function nearestDemo(loc) {
  if (!demoData) return null;
  let best = null, bd = Infinity;
  for (const l of demoData.locations) {
    const d = (l.lat - loc.lat) ** 2 + ((l.lon - loc.lon) * Math.cos(loc.lat * Math.PI / 180)) ** 2;
    if (d < bd) { bd = d; best = l; }
  }
  return best;
}
async function demoForecast(spot, model) {
  const d = await loadDemo();
  const loc = nearestDemo(spot);
  const f = loc?.forecasts[model];
  await new Promise(r => setTimeout(r, 120));
  if (!f) throw new Error("not in the demo data for this spot");
  const shift = currentHour() - Math.floor(d.capturedAt / 3600) * 3600;
  return { elevation: f.elevation, hourly: { ...f.hourly, time: f.hourly.time.map(t => t + shift) } };
}

// ---- connectivity

function setOnline(v) {
  if (S.online === v) return;
  S.online = v;
  render();
  if (v) onBackOnline();
}
function onBackOnline() {
  // Freshen the current spot if its winds are getting old, then the saved places.
  if (S.pin && (!Object.keys(S.forecasts).length || Date.now() - S.fetchedAt > 15 * 60_000)) fetchPinNow(++pinGen, S.pin, S.settings.models.slice());
  updateSavedPlaces(false);
  refreshObs();
}
window.addEventListener("online", () => setOnline(true));
window.addEventListener("offline", () => setOnline(false));
// navigator.onLine can say "online" behind a captive portal; after a failed download, probe now and then.
setInterval(async () => {
  if (S.online || navigator.onLine === false || DEMO) return;
  try { await fetchWithTimeout(`https://geocoding-api.open-meteo.com/v1/search?name=ab&count=1&t=${Date.now()}`, 8000).then(r => r.ok && setOnline(true)); } catch { /* still offline */ }
}, 60_000);

function refresh() {
  S.nowHour = currentHour();
  if (!S.pin) { toast("Long-press the map to pick a forecast spot"); return; }
  if (navigator.onLine === false) { toast("Offline: can't download new winds right now"); return; }
  fetchPinNow(++pinGen, S.pin, S.settings.models.slice());
  refreshObs(true);
}

// ---------------------------------------------------------------- saved places + history

function toggleFavorite(place = null) {
  const target = place ?? (S.pin ? { name: S.pinLabel, lat: S.pin.lat, lon: S.pin.lon, at: Date.now() } : null);
  if (!target) return;
  const existing = S.favorites.find(f => sameSpot(f, target));
  S.favorites = existing ? S.favorites.filter(f => f !== existing) : [{ name: target.name ?? null, lat: target.lat, lon: target.lon, at: Date.now() }, ...S.favorites];
  save("favorites", S.favorites);
  pruneCache();
  if (!existing) updateSavedPlaces(false);
  toast(existing ? "Removed from saved places" : "Saved. Its winds are kept ready for offline use");
  render();
}

function renameFavorite(i, name) {
  const n = name.trim() || null;
  const f = S.favorites[i];
  if (!f) return;
  S.favorites = S.favorites.map((p, k) => (k === i ? { ...p, name: n } : p));
  save("favorites", S.favorites);
  if (S.pin && sameSpot(f, S.pin)) { S.pinLabel = n; save("pin", { ...S.pin, label: n }); }
  render();
}

function goToPlace(p) {
  closeSheet();
  setPin({ lat: p.lat, lon: p.lon }, p.name, { zoom: 11 });
}

function pruneCache() {
  cache.prune([...S.favorites, ...S.recents, ...(S.pin ? [S.pin] : [])]);
}

/**
 * Downloads every selected model for each saved place, so they're all ready offline. Without force,
 * only places whose winds are over an hour old are fetched.
 */
let savingPlaces = false;
async function updateSavedPlaces(force) {
  if (savingPlaces) return;
  const models = S.settings.models.slice(), now = Date.now();
  const todo = S.favorites.flatMap(p => models.map(m => [p, m]))
    .filter(([p, m]) => force || (cache.fetchedAt(p, m) ?? 0) < now - STALE_MS);
  if (!todo.length) {
    if (force) toast(S.favorites.length ? "Saved places are up to date" : "No saved places yet");
    return;
  }
  if (!S.online) { if (force) toast("Offline: saved places will update when you're connected"); return; }
  savingPlaces = true;
  let failed = 0;
  for (let i = 0; i < todo.length; i++) {
    const [p, m] = todo[i];
    S.saving = [i, todo.length];
    renderPlaces();
    try {
      const fp = await download(p, m, S.settings.ceilingFt);
      // Keep the on-screen spot in step if it's one of these.
      if (S.pin && spotKey(S.pin) === spotKey(p)) { S.forecasts = { ...S.forecasts, [m]: fp }; S.fetchedAt = Date.now(); render(); }
    } catch { failed++; if (!S.online) break; }
  }
  S.saving = null;
  savingPlaces = false;
  renderPlaces();
  if (force || failed) {
    toast(failed === 0 ? `Winds saved for ${S.favorites.length} place${S.favorites.length === 1 ? "" : "s"}` : `Couldn't download ${failed} of ${todo.length} forecasts`);
  }
}

// ---------------------------------------------------------------- airport observations (US)

let obsCtl = null, obsAt = 0;
async function refreshObs(force = false) {
  const pin = S.pin;
  if (!pin) return;
  const key = spotKey(pin);
  const same = S.obs.key === key;
  if (!inUS(pin.lat, pin.lon)) { S.obs = { key, status: "nonus", list: [] }; obsLayer.clearLayers(); renderPin(); return; }
  if (!S.online || navigator.onLine === false) {
    S.obs = { key, status: same && S.obs.list.length ? "stale" : "offline", list: same ? S.obs.list : [] };
    renderPin(); return;
  }
  if (same && !force && S.obs.status === "ok" && Date.now() - obsAt < 5 * 60_000) return;
  obsCtl?.abort();
  obsCtl = new AbortController();
  S.obs = { key, status: "loading", list: same ? S.obs.list : [] };
  renderPin();
  try {
    const list = await nearbyObservations(pin, { max: 4, signal: obsCtl.signal });
    if (!S.pin || spotKey(S.pin) !== key) return;
    S.obs = { key, status: "ok", list };
    obsAt = Date.now();
  } catch (e) {
    if (e.name === "AbortError") return;
    if (!S.pin || spotKey(S.pin) !== key) return;
    S.obs = { key, status: e.notCovered ? "nonus" : "error", list: same ? S.obs.list : [] };
  }
  renderPin();
  renderObsMarkers();
}
setInterval(() => refreshObs(true), 10 * 60_000);

function obsText(o, withName = false) {
  if (!o.obs) return `${withName ? o.id + " " : ""}no recent report`;
  const w = o.obs;
  const dir = w.sp != null && w.sp < 0.5 ? "calm" : w.dir == null ? "VRB" : fmtDir(w.dir);
  const speed = w.sp == null ? "–" : (w.sp < 0.5 ? "0" : spd(w.sp)) + (w.gust != null ? ` (${spd(w.gust)})` : "");
  return [dir === "calm" ? `calm` : `${dir} ${speed} ${unitLabel()}`, w.tempC != null ? tmp(w.tempC) : null, Number.isFinite(w.time) ? fmtAge(w.time) : null]
    .filter(Boolean).join(" · ");
}

function renderObsMarkers() {
  obsLayer.clearLayers();
  if (S.obs.status !== "ok" && S.obs.status !== "stale") return;
  for (const o of S.obs.list) {
    const w = o.obs;
    const calm = !w || w.sp == null || w.sp < 0.5 || w.dir == null;
    const arrow = calm
      ? '<circle cx="14" cy="14" r="5" fill="none" stroke="#fff" stroke-width="2.4"/>'
      : `<g transform="rotate(${(w.dir + 180) % 360} 14 14)"><line x1="14" y1="23" x2="14" y2="10" stroke="#fff" stroke-width="3" stroke-linecap="round"/><path d="M14 3 L8.5 11 L19.5 11 Z" fill="#fff"/></g>`;
    const label = w ? `${o.id} ${w.sp == null ? "" : w.sp < 0.5 ? "calm" : spd(w.sp) + (w.gust != null ? ` (${spd(w.gust)})` : "")}` : o.id;
    const icon = L.divIcon({
      className: "obs-icon", iconSize: [28, 28], iconAnchor: [14, 14],
      html: `<svg class="obs-arrow" viewBox="0 0 28 28" aria-hidden="true"><circle cx="14" cy="14" r="2.5" fill="#1C2733"/>${arrow}</svg><span class="obs-label">${esc(label)}</span>`,
    });
    L.marker([o.lat, o.lon], { icon, keyboard: false, title: `${o.id} ${o.name}` })
      .bindPopup(`<b>${esc(o.id)}</b> ${esc(o.name)}<br>${esc(obsText(o))} · ${o.distKm.toFixed(0)} km<br><code>${esc(o.obs?.raw || "")}</code>`)
      .addTo(obsLayer);
  }
}

// ---------------------------------------------------------------- layout helpers

/**
 * Moves the pin to the base of the clear map area so its chart fits above the bottom panels. The pin
 * panel grows a little once the forecast and airport reports arrive, so for a few seconds afterwards
 * (until the user touches the map) the pin is nudged again to stay clear of it.
 */
let autoPlace = null; // {ll, until, top}
function panelTop() {
  const panel = [$("pinPanel"), $("hint"), $("controls"), $("timeOnly"), $("showPanels")].find(e => !e.hidden);
  return panel ? panel.getBoundingClientRect().top : map.getSize().y - 60;
}
function keepAbovePanels(ll, zoom, animate = true) {
  autoPlace = { ll, until: Date.now() + 10000, top: null };
  requestAnimationFrame(() => requestAnimationFrame(() => {
    const z = zoom ?? map.getZoom();
    const size = map.getSize();
    const bottomTop = panelTop();
    autoPlace && (autoPlace.top = bottomTop);
    const topBottom = $("top").getBoundingClientRect().bottom;
    const targetY = Math.max(topBottom + 120, bottomTop - 34);
    const p = map.project([ll.lat, ll.lon], z);
    const center = p.subtract(L.point(size.x / 2, targetY).subtract(size.divideBy(2)));
    map.setView(map.unproject(center, z), z, { animate });
  }));
}
function followPanels() {
  if (!autoPlace || !S.pin || autoPlace.top == null) return;
  if (Date.now() > autoPlace.until || !sameSpot(autoPlace.ll, S.pin)) { autoPlace = null; return; }
  const top = panelTop();
  if (Math.abs(top - autoPlace.top) < 4) return;
  const { ll, until } = autoPlace;
  keepAbovePanels(ll, null);
  autoPlace.until = until;
}

let toastTimer = null;
function toast(msg, action = null) {
  const t = $("toast");
  t.innerHTML = `<span>${esc(msg)}</span>` + (action ? (action.href ? `<a href="${esc(action.href)}">${esc(action.label)}</a>` : `<button type="button" data-act="${esc(action.act)}">${esc(action.label)}</button>`) : "");
  t.classList.add("on");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove("on"), action ? 7000 : 3500);
}

// ---------------------------------------------------------------- rendering

function buildProfiles() {
  const s = S.settings, alts = altitudes(), epoch = selectedEpoch(), cmp = comparing();
  profiles = s.models.map((m, slot) => {
    const fp = S.forecasts[m];
    if (!fp) return null;
    return {
      model: m, slot, color: cmp ? modelColor(slot) : null,
      winds: windsAt(fp, epoch, alts, s.ref),
      // All layers up to the ceiling: those outside the shown altitudes get markers on the map.
      clouds: cloudLayers(fp, epoch, s.ref, 0, s.ceilingFt),
    };
  }).filter(Boolean);
}

function render() {
  if (S.hourOffset > maxHourOffset()) S.hourOffset = maxHourOffset();
  buildProfiles();
  document.body.classList.toggle("panels-hidden", S.panelMode === "hidden");
  $("controls").hidden = S.panelMode !== "full";
  // The open table covers the map; the buttons come back when it closes.
  $("fabs").hidden = S.panelMode !== "full" || (S.expanded && !!S.pin);
  $("timeOnly").hidden = S.panelMode !== "time";
  $("showPanels").hidden = S.panelMode !== "hidden";
  $("searchForm").hidden = S.panelMode !== "full";
  if (S.panelMode !== "full") $("results").hidden = true;
  renderPending();
  renderControls();
  renderPin();
  if (!$("placesSheet").hidden) renderPlaces();
  scheduleDraw();
}

function renderPending() {
  const p = S.pending;
  $("pending").hidden = !p;
  if (p) $("pendingCoords").textContent = fmtCoords(p);
}

function renderControls() {
  const s = S.settings, max = Math.max(1, maxHourOffset());
  const label = `${fmtTime(selectedEpoch())}  (+${S.hourOffset} h)`;
  $$(".timeLabel").forEach(e => { e.textContent = label; });
  $$(".timeRange").forEach(r => {
    r.max = max; r.value = S.hourOffset;
    r.style.setProperty("--fill", `${(100 * S.hourOffset) / max}%`);
  });
  $$(".progress").forEach(e => { e.hidden = S.loading === 0; });
  const ceilK = s.ceilingFt / 1000;
  const lo = $("altLo"), hi = $("altHi");
  lo.max = hi.max = ceilK; lo.value = S.alt[0]; hi.value = S.alt[1];
  // When both thumbs meet, keep the one that can still move on top.
  lo.classList.toggle("front", S.alt[0] === S.alt[1] && S.alt[1] >= ceilK);
  $("altFill").style.left = `${(100 * S.alt[0]) / ceilK}%`;
  $("altFill").style.right = `${100 - (100 * S.alt[1]) / ceilK}%`;
  $("altLabel").textContent = `${fmtFeet(S.alt[0] * 1000)} – ${fmtFeet(S.alt[1] * 1000)} ft ${s.ref}`;
  $("legend").innerHTML = legendHtml();
  $("speedLegend").innerHTML = speedLegendHtml();
}

function gradient(stops) { return `linear-gradient(90deg, ${stops.map(c => css(c)).join(", ")})`; }
function scaleHtml(stops, labels, dims = "") {
  return `<div class="scale"><div class="bar" style="background:${gradient(stops)}">${dims}</div><div class="ticks">${labels.map(l => `<span>${esc(l)}</span>`).join("")}</div></div>`;
}
function modelLegendHtml() {
  return `<div class="models-legend">${S.settings.models.map((m, i) => `<span><i style="background:${css(modelColor(i))}"></i>${esc(modelShort(m))}</span>`).join("")}</div>`;
}
function legendHtml() {
  const s = S.settings, ceilK = s.ceilingFt / 1000;
  let out;
  if (s.colorMode === "temperature") {
    out = scaleHtml(Array.from({ length: 33 }, (_, i) => temperatureColor(-40 + 80 * i / 32)), [-40, -20, 0, 20, 40].map(t => tmp(t)));
  } else if (s.colorMode === "speed") {
    out = scaleHtml(Array.from({ length: 21 }, (_, i) => speedColor(50 * i / 20)), [0, 25, 50].map(kt => spd(kt / KT_PER_MS) + (kt === 50 ? `+ ${unitLabel()}` : "")));
  } else if (comparing()) {
    out = modelLegendHtml();
  } else {
    const dims = `<div class="dim" style="left:0;width:${(100 * S.alt[0]) / ceilK}%"></div><div class="dim" style="right:0;width:${100 - (100 * S.alt[1]) / ceilK}%"></div>`;
    const ticks = [0, Math.floor(ceilK / 3), Math.floor(2 * ceilK / 3), ceilK].map(k => (k === 0 ? "0" : `${k}k`));
    out = scaleHtml(Array.from({ length: 21 }, (_, i) => altitudeColor(s.ceilingFt * i / 20, s.ceilingFt)), ticks, dims);
  }
  if (comparing() && s.colorMode !== "auto" && s.colorMode !== "model") out += modelLegendHtml();
  return out;
}
/** Arrow sizes at a few speeds (stacked) or a reference arrow (fan). */
function speedLegendHtml() {
  const u = S.settings.unit;
  const inUnit = kt => fmtSpeed(kt / KT_PER_MS, u);
  const arrow = (len, w) => `<svg width="${w}" height="10" viewBox="0 0 ${w} 10" aria-hidden="true"><line x1="${(w - len) / 2}" y1="5" x2="${(w + len) / 2 - Math.min(len * 0.4, 6)}" y2="5" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><path d="M${(w + len) / 2} 5 l-${Math.min(len * 0.4, 6)} -3.6 v7.2 z" fill="currentColor"/></svg>`;
  if (S.settings.layout === "fan") return `${arrow(56, 56)}<span>&nbsp;${inUnit(20)} ${unitLabel()}</span>`;
  const cell = 18 * STACK_LENGTH;
  return [5, 20, STACK_FULL_KT].map(kt => `${arrow(cell * stackedFraction(kt), cell)}<span>${inUnit(kt)}${kt >= STACK_FULL_KT ? "+" : ""}&nbsp;</span>`).join("") + `<b>${unitLabel()}</b>`;
}

const CHEV_UP = '<svg class="chev" viewBox="0 0 24 24" aria-hidden="true"><path d="M7.41 15.41 12 10.83l4.59 4.58L18 14l-6-6-6 6z"/></svg>';
const CHEV_DOWN = '<svg class="chev" viewBox="0 0 24 24" aria-hidden="true"><path d="M7.41 8.59 12 13.17l4.59-4.58L18 10l-6 6-6-6z"/></svg>';
const CLOSE = '<svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/></svg>';

/** Small arrow pointing downwind, matching the map arrows. */
function glyphSvg(dir, rgb, size = 18) {
  return `<svg class="g" width="${size}" height="${size}" viewBox="0 0 18 18" aria-hidden="true"><g transform="rotate(${(dir + 180) % 360} 9 9)">` +
    `<line x1="9" y1="17" x2="9" y2="7" stroke="var(--ink)" stroke-width="4" stroke-linecap="round"/>` +
    `<line x1="9" y1="17" x2="9" y2="7" stroke="${css(rgb)}" stroke-width="2" stroke-linecap="round"/>` +
    `<path d="M9 1 L5.4 7 L12.6 7 Z" fill="${css(rgb)}"/></g></svg>`;
}

function renderPin() {
  const panel = $("pinPanel"), s = S.settings;
  const showPanel = S.panelMode === "full";
  $("hint").hidden = !showPanel || !!S.pin || !!S.pending;
  if (!S.pin || !showPanel) { panel.hidden = true; return; }
  panel.hidden = false;
  const scroller = panel.querySelector(".scroll");
  const scrollTop = scroller ? scroller.scrollTop : 0;
  const fav = S.favorites.some(f => sameSpot(f, S.pin));
  const elev = Object.values(S.forecasts)[0]?.elev;
  const age = S.fetchedAt ? fmtAge(S.fetchedAt) : null;
  const sub = [
    elev != null ? `Elev ${fmtElevation(elev)}` : null,
    comparing() ? `${s.models.length} models` : modelLabel(s.models[0]),
    S.online ? age : null,
  ].filter(Boolean).map(esc).join(" · ");
  // Offline gets its own line so it's never cut off: "Offline · data from 3 h ago".
  const offline = S.online ? "" : `<div class="pin-sub offline">Offline${age ? ` · data from ${age}` : " · no saved winds"}</div>`;
  let lines = "";
  if (s.showClouds && profiles.length) {
    lines += profiles.map(p => `<div class="pin-line${comparing() ? " one" : ""}">${esc((comparing() ? modelShort(p.model) + ": " : "☁ ") + fmtClouds(p.clouds, comparing()))}</div>`).join("");
  }
  if (!S.expanded) {
    const near = S.obs.list.find(o => o.obs);
    if (near && (S.obs.status === "ok" || S.obs.status === "stale")) lines += `<div class="pin-line pin-obs">Observed ${esc(near.id)} (${near.distKm.toFixed(0)} km): ${esc(obsText(near))}</div>`;
  }
  let html = `<div class="pin-head" data-act="toggleTable" aria-expanded="${S.expanded}">
      <div class="pin-text"><div class="pin-title">${esc(S.pinLabel || fmtCoords(S.pin))}</div><div class="pin-sub">${sub}</div>${offline}${lines}</div>
      ${S.loading ? '<span class="spinner" aria-label="Loading"></span>' : ""}
      ${S.expanded ? CHEV_DOWN : CHEV_UP}
      <button type="button" class="icon-btn" data-act="favorite" aria-label="${fav ? "Remove from saved places" : "Save this place"}"><span class="star${fav ? " on" : ""}">${fav ? "★" : "☆"}</span></button>
      <button type="button" class="icon-btn" data-act="clearPin" aria-label="Remove pin">${CLOSE}</button>
    </div>`;
  if (S.chartAt) html += `<button type="button" class="text-btn reattach" data-act="reattach">Put the chart back on the forecast spot</button>`;
  if (S.expanded) {
    html += `<div class="pin-body scroll">`;
    if (!profiles.length) html += `<div class="msg">${S.loading ? "Loading forecast…" : "No forecast loaded"}</div>`;
    else html += comparing() ? compareTableHtml() : profileTableHtml();
    html += obsSectionHtml() + `</div>`;
  }
  panel.innerHTML = html;
  const sc = panel.querySelector(".scroll");
  if (sc) sc.scrollTop = scrollTop;
  if (!S.expanded) requestAnimationFrame(followPanels);
}

function altitudeText(ft, w) {
  if (w?.surface && S.settings.ref === "MSL") return "SFC " + fmtFeet(w.alt);
  if (w?.surface || (ft === 0 && S.settings.ref === "AGL")) return "SFC";
  return fmtFeet(ft);
}

function profileTableHtml() {
  const s = S.settings, p = profiles[0], alts = altitudes();
  const rows = alts.map((ft, i) => [ft, p.winds[i]]).reverse().map(([ft, w]) => {
    // Same colour as the symbol on the map, whatever the colour setting.
    const rgb = w ? windColor(s.colorMode, w, null, s.ceilingFt) : altitudeColor(ft, s.ceilingFt);
    if (!w) return `<div class="tr"><i class="dot" style="background:${css(rgb)}"></i><span class="c-alt">${esc(altitudeText(ft, w))}</span><span class="none">${s.ref === "MSL" ? "below ground / no data" : "no data"}</span></div>`;
    return `<div class="tr"><i class="dot" style="background:${css(rgb)}"></i><span class="c-alt">${esc(altitudeText(ft, w))}</span>` +
      `<span class="c-dir">${glyphSvg(w.d, rgb)}${fmtDir(w.d)}</span><span class="c-spd">${esc(fmtSpeedGust(w, s.unit))}</span>` +
      `<span class="c-tmp">${w.t != null ? esc(tmp(w.t)) : ""}</span></div>`;
  }).join("");
  return `<div class="tbl"><div class="th"><span class="c-alt">Altitude</span><span class="c-dir">From</span><span class="c-spd">Speed</span><span>Temp</span><span class="unit">${esc(unitLabel())}</span></div><div class="rows">${rows}</div></div>`;
}

/** Each model's direction/speed per altitude side by side, plus Δ, the largest vector difference between them. */
function compareTableHtml() {
  const s = S.settings, alts = altitudes();
  const head = `<div class="th"><span class="c-alt">Alt</span>${profiles.map(p => `<span class="c-m"><i style="background:${css(p.color)}"></i>${esc(modelShort(p.model))}</span>`).join("")}<span class="c-d">Δ ${esc(unitLabel())}</span></div>`;
  const rows = alts.map((ft, i) => i).reverse().map(i => {
    const ws = profiles.map(p => p.winds[i]);
    const cells = ws.map((w, k) => {
      if (!w) return `<span class="c-m none">—</span>`;
      const rgb = windColor(s.colorMode, w, profiles[k].color, s.ceilingFt);
      const t = w.t != null ? ` <span class="${w.t < 0 ? "t-cold" : "t-warm"}">${esc(tmp(w.t))}</span>` : "";
      return `<span class="c-m">${glyphSvg(w.d, rgb, 12)}${String(Math.round(w.d) % 360).padStart(3, "0")}/${esc(fmtSpeedGust(w, s.unit))}${t}</span>`;
    }).join("");
    const spread = maxVectorDifference(ws.filter(Boolean));
    const big = spread != null && spread * KT_PER_MS >= 10;
    return `<div class="tr"><span class="c-alt">${ws.some(w => w?.surface) ? "SFC" : fmtFeet(alts[i])}</span>${cells}<span class="c-d${big ? " big" : ""}">${spread != null ? spd(spread) : ""}</span></div>`;
  }).join("");
  return `<div class="tbl compare">${head}<div class="rows">${rows}</div></div>`;
}

function obsSectionHtml() {
  const o = S.obs;
  let body;
  if (o.status === "nonus") body = `<div class="note">Airport reports are US-only in the web version.</div>`;
  else if (o.status === "offline") body = `<div class="note">Airport reports need a connection.</div>`;
  else if (o.status === "error" && !o.list.length) body = `<div class="note">Couldn't load airport reports from weather.gov.</div>`;
  else if (o.status === "loading" && !o.list.length) body = `<div class="note">Loading airport reports…</div>`;
  else if (!o.list.length) body = `<div class="note">No airport reports within 100 km.</div>`;
  else body = o.list.map(st => `<div class="o"><b>${esc(st.id)}</b> ${st.distKm.toFixed(0)} km · ${esc(obsText(st))}<code>${esc(st.obs?.raw || st.name)}</code></div>`).join("") +
    (o.status === "stale" ? `<div class="note">Offline: these are the last reports loaded.</div>` : "");
  return `<div class="obs"><h3>Observed now · nearby airports (NOAA)</h3>${body}</div>`;
}

// ---------------------------------------------------------------- settings sheet

const SETTINGS_SPEC = [
  { title: "Wind speed units", key: "unit", options: Object.entries(SPEED_UNITS).map(([k, v]) => [k, v.label]) },
  { title: "Altitude reference", key: "ref", options: [["MSL", "MSL (sea level)"], ["AGL", "AGL (ground)"]] },
  { title: "Altitude ceiling", key: "ceilingFt", options: CEILINGS.map(c => [c, `${fmtFeet(c)} ft`]) },
  { title: "Map style", key: "layer", options: Object.entries(LAYERS).map(([k, v]) => [k, v.label]) },
  { title: "Wind symbol", key: "glyph", options: [["arrow", "Arrow (windsock)"], ["triangle", "Triangle (tetrahedron)"], ["barb", "Wind barb"]] },
  { title: "On the column line", key: "anchor", options: [["tail", "Tail (windsock)"], ["nose", "Nose"], ["center", "Centre"]], note: s => ({
    arrow: "Arrows point downwind. Tail on the line works like a windsock.",
    triangle: "Triangles point into the wind. Nose on the line works like a landing tetrahedron.",
    barb: "Barbs point into the wind, feathers at the far end. Tail (station) on the line is the weather-chart convention.",
  })[s.glyph] },
  { title: "Colour", key: "colorMode", options: [["auto", "Auto"], ["altitude", "Altitude"], ["speed", "Speed"], ["temperature", "Temperature"], ["model", "Model"], ["white", "White"]] },
  { title: "Numbers on the map", toggles: [["showSpeed", "Speed"], ["showDirection", "Direction (from)"], ["showTemperature", "Temperature"]] },
  { title: "Temperature unit", key: "tempUnit", options: [["C", "Celsius (C)"], ["F", "Fahrenheit (F)"]] },
  { title: "Cloud layers", toggles: [["showClouds", "Show cloud bases & tops"]] },
  { title: "Layout", key: "layout", options: [["stacked", "Stacked"], ["fan", "Fan"]] },
];

function renderSettings() {
  const s = S.settings;
  let html = "";
  for (const sec of SETTINGS_SPEC) {
    html += `<h3>${esc(sec.title)}</h3><div class="chips">`;
    if (sec.options) html += sec.options.map(([v, l]) => `<button type="button" class="chip" data-set="${sec.key}" data-val="${esc(v)}" aria-pressed="${String(s[sec.key]) === String(v)}">${esc(l)}</button>`).join("");
    else html += sec.toggles.map(([k, l]) => `<button type="button" class="chip" data-toggle="${k}" aria-pressed="${!!s[k]}">${esc(l)}</button>`).join("");
    html += `</div>`;
    if (sec.note) html += `<p class="note">${esc(sec.note(s))}</p>`;
  }
  html += `<h3>Forecast models (pick up to ${MAX_MODELS} to compare)</h3>`;
  html += MODELS.map(m => {
    const slot = s.models.indexOf(m.id), on = slot >= 0;
    const enabled = on ? s.models.length > 1 : s.models.length < MAX_MODELS;
    return `<label class="check${enabled || on ? "" : " disabled"}"><input type="checkbox" data-model="${m.id}" ${on ? "checked" : ""} ${enabled ? "" : "disabled"}>${esc(m.label)}${on && comparing() ? `<i class="swatch" style="background:${css(modelColor(slot))}"></i>` : ""}</label>`;
  }).join("");
  html += `<p class="note">Each picked model gets its own column of arrows on the map and in the table.</p>`;
  html += `<h3>Offline</h3><p class="note">Winds for your saved places are downloaded whenever you're online (and refreshed when over an hour old), and the last ${MAX_RECENTS} spots you looked at are kept too, so they work with no signal. Map tiles you've looked at are kept on this device (up to about 3,000 tiles): before you go, open your drop zone and zoom around it once while online.</p><p class="note" id="storageInfo">${esc(storageText)}</p>`;
  html += `<h3>About</h3><p class="note">Each altitude is a row, lowest at the pin; a longer symbol is a stronger wind. Directions are in degrees (°) the wind comes from; temperatures end in C or F. The bottom SFC row is the ground (10 m) wind, with forecast gusts in brackets: 4 (18). Long-press the map to move the forecast spot.</p>
    <p class="note">Forecasts are model output from <a href="https://open-meteo.com">Open-Meteo</a> (CC BY 4.0), not an official aviation product. Use them alongside official winds aloft. Airport reports: NOAA <a href="https://www.weather.gov/documentation/services-web-api">api.weather.gov</a> (US only). Maps: © OpenStreetMap contributors, © OpenTopoMap (CC-BY-SA), Imagery © Esri.</p>
    <p class="note">${DEMO ? 'Demo mode: winds come from a captured forecast for three sample spots. <a href="./">Use live forecasts</a>' : 'No network or out of API quota? <a href="?demo=1">Try the demo data</a>.'}</p>`;
  $("settingsBody").innerHTML = html;
}

let storageText = "";
async function updateStorageInfo() {
  try {
    const est = await navigator.storage?.estimate?.();
    const tiles = await caches.open("wa-tiles-v1").then(c => c.keys()).then(k => k.length).catch(() => null);
    storageText = [
      tiles != null ? `Map tiles saved: ${tiles.toLocaleString()}` : null,
      `forecasts saved: ${cache.count()}`,
      est?.usage != null ? `storage used ${(est.usage / 1e6).toFixed(1)} MB` : null,
    ].filter(Boolean).join(" · ");
    const el = $("storageInfo");
    if (el) el.textContent = storageText;
  } catch { /* not supported */ }
}

function updateSettings(next) {
  const old = S.settings;
  S.settings = next;
  save("settings", next);
  const ceilK = next.ceilingFt / 1000;
  S.alt = [Math.min(S.alt[0], ceilK), Math.min(S.alt[1], ceilK)];
  save("alt", S.alt);
  if (old.layer !== next.layer) setLayer();
  if (old.models.join() !== next.models.join() || old.ceilingFt !== next.ceilingFt) {
    if (S.pin) loadPin(false);
    updateSavedPlaces(false);
  }
  if (old.unit !== next.unit || old.tempUnit !== next.tempUnit) renderObsMarkers();
  render();
  if (!$("settingsSheet").hidden) renderSettings();
}

// ---------------------------------------------------------------- places sheet

function renderPlaces() {
  if ($("placesSheet").hidden) return;
  if (S.placesTab == null) S.placesTab = !S.favorites.length && S.recents.length ? 1 : 0;
  const tab = S.placesTab, list = tab === 0 ? S.favorites : S.recents;
  let html = `<div class="tabs" role="tablist"><button type="button" role="tab" data-act="tab0" aria-selected="${tab === 0}">Saved (${S.favorites.length})</button><button type="button" role="tab" data-act="tab1" aria-selected="${tab === 1}">Recent (${S.recents.length})</button></div>`;
  if (!S.online) html += `<div class="offline-note">Offline: places show the winds saved on this device.</div>`;
  if (!list.length) {
    html += `<p class="note">${tab === 0 ? "No saved places yet. Tap ☆ on a spot's panel to save it. Saved places have their winds downloaded whenever you're online, ready for when you're not." : `Spots you look at appear here (the last ${MAX_RECENTS}).`}</p>`;
  } else {
    html += list.map((p, i) => {
      if (tab === 0 && S.renaming === i) {
        return `<div class="rename"><input type="text" id="renameInput" value="${esc(p.name || "")}" placeholder="Name" enterkeyhint="done" aria-label="Name"><button type="button" class="text-btn" data-act="renameCancel">Cancel</button><button type="button" class="text-btn" data-act="renameSave" data-i="${i}">Save</button></div>`;
      }
      const saved = cache.spotTime(p, S.settings.models);
      const favorite = tab === 0 || S.favorites.some(f => sameSpot(f, p));
      const meta = [saved != null ? `Winds saved ${fmtAge(saved)}` : "No winds saved", p.name ? fmtCoords(p) : null].filter(Boolean).join(" · ");
      const buttons = tab === 0
        ? `<button type="button" class="icon-btn" data-act="rename" data-i="${i}" aria-label="Rename"><svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/></svg></button>
           <button type="button" class="icon-btn" data-act="unfav" data-i="${i}" aria-label="Remove from saved places"><svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg></button>`
        : `<button type="button" class="icon-btn" data-act="favRecent" data-i="${i}" aria-label="${favorite ? "Remove from saved places" : "Save this place"}"><span class="star${favorite ? " on" : ""}">${favorite ? "★" : "☆"}</span></button>
           <button type="button" class="icon-btn" data-act="removeRecent" data-i="${i}" aria-label="Remove from history">${CLOSE}</button>`;
      return `<div class="place"><button type="button" class="go" data-act="go" data-i="${i}"><div class="name">${esc(p.name || fmtCoords(p))}</div><div class="meta${saved == null && !S.online ? " err" : ""}">${esc(meta)}</div></button>${buttons}</div>`;
    }).join("");
  }
  if (tab === 0 && S.favorites.length) {
    html += S.saving
      ? `<p class="note">Downloading winds… ${S.saving[0] + 1} of ${S.saving[1]}</p>`
      : `<button type="button" class="primary-btn wide-btn" data-act="saveAll" ${S.online ? "" : "disabled"}>Download winds for all saved places</button>`;
  }
  if (tab === 1 && S.recents.length) html += `<div style="text-align:right"><button type="button" class="text-btn" data-act="clearRecents">Clear history</button></div>`;
  $("placesBody").innerHTML = html;
  if (S.renaming != null) {
    const inp = $("renameInput");
    if (inp && document.activeElement !== inp) inp.focus();
  }
}

function openSheet(id) {
  closeSheet();
  $("backdrop").hidden = false;
  $(id).hidden = false;
  if (id === "settingsSheet") { renderSettings(); updateStorageInfo(); }
  else { S.placesTab = null; S.renaming = null; renderPlaces(); }
}
function closeSheet() {
  $("backdrop").hidden = true;
  $("settingsSheet").hidden = true;
  $("placesSheet").hidden = true;
  S.renaming = null;
}

// ---------------------------------------------------------------- events

function setPanelMode(m) { S.panelMode = m; render(); }

const ACTIONS = {
  cancelPending: () => { S.pending = null; render(); },
  confirmPending: () => { if (S.pending) setPin(S.pending); },
  panelsTime: () => setPanelMode("time"),
  panelsFull: () => setPanelMode("full"),
  panelsHidden: () => setPanelMode("hidden"),
  cycleLayer: () => {
    const keys = Object.keys(LAYERS), next = keys[(keys.indexOf(S.settings.layer) + 1) % keys.length];
    updateSettings({ ...S.settings, layer: next });
    toast(`Map: ${LAYERS[next].label}`);
  },
  refresh,
  myLocation: () => {
    if (!navigator.geolocation) { toast("Location isn't available in this browser"); return; }
    toast("Waiting for a location fix…");
    navigator.geolocation.getCurrentPosition(
      p => setPin({ lat: p.coords.latitude, lon: p.coords.longitude }, "My location", { zoom: 10 }),
      e => toast(e.code === 1 ? "Location permission denied" : "Couldn't get your location"),
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 60000 },
    );
  },
  toggleTable: () => { S.expanded = !S.expanded; render(); },
  favorite: () => toggleFavorite(),
  clearPin,
  reattach: () => { S.chartAt = null; renderPin(); scheduleDraw(); },
  closeSheet,
  tab0: () => { S.placesTab = 0; S.renaming = null; renderPlaces(); },
  tab1: () => { S.placesTab = 1; S.renaming = null; renderPlaces(); },
  go: el => goToPlace((S.placesTab === 0 ? S.favorites : S.recents)[+el.dataset.i]),
  rename: el => { S.renaming = +el.dataset.i; renderPlaces(); },
  renameCancel: () => { S.renaming = null; renderPlaces(); },
  renameSave: el => { const v = $("renameInput").value; S.renaming = null; renameFavorite(+el.dataset.i, v); renderPlaces(); },
  unfav: el => toggleFavorite(S.favorites[+el.dataset.i]),
  favRecent: el => toggleFavorite(S.recents[+el.dataset.i]),
  removeRecent: el => { S.recents = S.recents.filter((_, k) => k !== +el.dataset.i); save("recents", S.recents); pruneCache(); renderPlaces(); },
  clearRecents: () => { S.recents = []; save("recents", []); pruneCache(); renderPlaces(); },
  saveAll: () => updateSavedPlaces(true),
};

document.addEventListener("click", e => {
  const el = e.target.closest("[data-act],[data-set],[data-toggle]");
  if (!el) return;
  if (el.dataset.act) {
    // Buttons inside the pin header shouldn't also toggle the table.
    e.stopPropagation();
    ACTIONS[el.dataset.act]?.(el);
  } else if (el.dataset.set) {
    const key = el.dataset.set, raw = el.dataset.val;
    const val = key === "ceilingFt" ? +raw : raw;
    const next = { ...S.settings, [key]: val };
    // Picking a symbol also applies its natural anchor (windsock, tetrahedron, chart barb).
    if (key === "glyph") next.anchor = DEFAULT_ANCHOR[val];
    updateSettings(next);
  } else if (el.dataset.toggle) {
    updateSettings({ ...S.settings, [el.dataset.toggle]: !S.settings[el.dataset.toggle] });
  }
});
document.addEventListener("change", e => {
  const m = e.target.dataset?.model;
  if (!m) return;
  const s = S.settings;
  const models = e.target.checked ? [...s.models, m].slice(0, MAX_MODELS) : s.models.filter(x => x !== m);
  if (!models.length) { e.target.checked = true; return; }
  updateSettings({ ...s, models });
});
document.addEventListener("keydown", e => {
  if (e.key === "Escape") closeSheet();
  if (e.key === "Enter" && e.target.id === "renameInput") { e.preventDefault(); $("placesBody").querySelector('[data-act="renameSave"]')?.click(); }
});
$("backdrop").addEventListener("click", closeSheet);
$("placesBtn").addEventListener("click", () => openSheet("placesSheet"));
$("settingsBtn").addEventListener("click", () => openSheet("settingsSheet"));

// Sliders
$$(".timeRange").forEach(r => r.addEventListener("input", () => { S.hourOffset = +r.value; render(); }));
$("altLo").addEventListener("input", e => { S.alt = [Math.min(+e.target.value, S.alt[1]), S.alt[1]]; e.target.value = S.alt[0]; save("alt", S.alt); render(); });
$("altHi").addEventListener("input", e => { S.alt = [S.alt[0], Math.max(+e.target.value, S.alt[0])]; e.target.value = S.alt[1]; save("alt", S.alt); render(); });

// Search
$("q").addEventListener("input", () => { $("clearQ").hidden = !$("q").value; });
$("clearQ").addEventListener("click", () => { $("q").value = ""; $("clearQ").hidden = true; $("results").hidden = true; $("q").focus(); });
$("searchForm").addEventListener("submit", async e => {
  e.preventDefault();
  const q = $("q").value.trim();
  if (!q) return;
  $("q").blur();
  const c = parseCoordinates(q);
  if (c) { setPin(c, null, { zoom: 10 }); return; }
  try {
    const r = await fetchWithTimeout(`https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(q)}&count=6&language=en&format=json`, 12000);
    const d = await r.json();
    const results = (d.results || []).filter(x => Number.isFinite(x.latitude) && Number.isFinite(x.longitude));
    if (!results.length) { toast(`No places found for "${q}"`); $("results").hidden = true; return; }
    $("results").innerHTML = results.map((x, i) => `<li><button type="button" data-i="${i}"><div>${esc(x.name)}</div><div class="sub">${esc([x.admin1, x.country].filter(Boolean).join(", "))}</div></button></li>`).join("");
    $("results").hidden = false;
    $("results").onclick = ev => {
      const b = ev.target.closest("button[data-i]");
      if (!b) return;
      const x = results[+b.dataset.i];
      $("q").value = x.name;
      setPin({ lat: x.latitude, lon: x.longitude }, [x.name, x.admin1].filter(Boolean).join(", "), { zoom: 10 });
    };
  } catch (err) {
    toast(navigator.onLine === false ? "Search needs a connection. Type lat, lon instead" : `Search failed: ${err.message}`);
  }
});

// iOS: no page pinch-zoom over the panels (the map has its own pinch zoom).
document.addEventListener("gesturestart", e => { if (!e.target.closest?.("#map")) e.preventDefault(); }, { passive: false });
window.addEventListener("resize", () => { sizeCanvas(); map.invalidateSize(); });
map.on("moveend", () => { const c = map.getCenter(); save("camera", { lat: c.lat, lon: c.lng, zoom: map.getZoom() }); });
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState !== "visible") return;
  const now = currentHour();
  if (now !== S.nowHour) { S.nowHour = now; render(); }
  if (S.online && S.pin && Date.now() - S.fetchedAt > 30 * 60_000) fetchPinNow(++pinGen, S.pin, S.settings.models.slice());
  updateSavedPlaces(false);
});
setInterval(() => { const now = currentHour(); if (now !== S.nowHour) { S.nowHour = now; render(); } else renderPin(); }, 60_000);

// ---------------------------------------------------------------- start

async function start() {
  if (DEMO) {
    $("demoBadge").hidden = false;
    try { await loadDemo(); } catch { toast("Couldn't load the demo data"); }
  }
  const pin = load("pin");
  const cam = load("camera");
  const alt = load("alt", [0, 14]);
  const ceilK = S.settings.ceilingFt / 1000;
  S.alt = [Math.min(Math.max(0, alt[0] | 0), ceilK), Math.min(Math.max(0, alt[1] | 0), ceilK)];
  if (S.alt[0] > S.alt[1]) S.alt = [S.alt[1], S.alt[0]];

  if (cam) map.setView([cam.lat, cam.lon], cam.zoom, { animate: false });
  else if (pin) map.setView([pin.lat, pin.lon], 10, { animate: false });
  else if (DEMO && demoData) map.setView([demoData.locations[0].lat, demoData.locations[0].lon], 10, { animate: false });
  else map.setView([39.5, -98.35], 4, { animate: false });
  setLayer();
  sizeCanvas();

  if (pin && Number.isFinite(pin.lat)) {
    S.pin = { lat: pin.lat, lon: pin.lon };
    S.pinLabel = pin.label ?? null;
    render();
    loadPin(true);
    refreshObs();
    if (!cam) keepAbovePanels(S.pin, 10);
  } else if (DEMO && demoData) {
    const l = demoData.locations[0];
    setPin({ lat: l.lat, lon: l.lon }, l.name, { zoom: 10 });
  }
  render();
  if (S.online) updateSavedPlaces(false);

  if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => { /* e.g. file:// */ });
  navigator.storage?.persist?.().catch(() => {});
}
start();

// For tests and debugging in the console.
window.__wa = { S, map, render, setPin };
