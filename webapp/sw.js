// Service worker: keeps the app shell and viewed map tiles available offline.
// All paths are relative to the registration scope, so the app works under any sub-path
// (e.g. https://<user>.github.io/winds-aloft/app/).
const VERSION = "v1";
const SHELL_CACHE = `wa-shell-${VERSION}`;
const TILE_CACHE = "wa-tiles-v1";
const MAX_TILES = 3000;

const SHELL = [
  "./",
  "index.html",
  "manifest.webmanifest",
  "css/app.css",
  "js/app.js",
  "js/wind.js",
  "js/colors.js",
  "js/format.js",
  "js/chart.js",
  "js/store.js",
  "js/metar.js",
  "vendor/leaflet-1.9.4/leaflet.js",
  "vendor/leaflet-1.9.4/leaflet.css",
  "vendor/leaflet-1.9.4/images/layers.png",
  "vendor/leaflet-1.9.4/images/layers-2x.png",
  "vendor/leaflet-1.9.4/images/marker-icon.png",
  "icons/icon-192.png",
  "icons/apple-touch-icon.png",
  "icons/favicon-32.png",
];
const scoped = p => new URL(p, self.registration.scope).href;

self.addEventListener("install", event => {
  event.waitUntil(caches.open(SHELL_CACHE).then(c => c.addAll(SHELL.map(scoped))).then(() => self.skipWaiting()));
});

self.addEventListener("activate", event => {
  event.waitUntil((async () => {
    for (const k of await caches.keys()) if (k.startsWith("wa-shell-") && k !== SHELL_CACHE) await caches.delete(k);
    await self.clients.claim();
  })());
});

/** Tile servers whose tiles are kept (cache-first). OpenTopoMap's a/b/c subdomains share one entry. */
function tileKey(url) {
  const u = new URL(url);
  if (/(^|\.)tile\.opentopomap\.org$/.test(u.hostname)) return `https://tile.opentopomap.org${u.pathname}`;
  if (u.hostname === "tile.openstreetmap.org" || u.hostname === "server.arcgisonline.com") return u.origin + u.pathname;
  return null;
}

let putsSinceTrim = 0;
async function trimTiles(cache) {
  const keys = await cache.keys(); // insertion order: oldest first
  for (let i = 0; i < keys.length - MAX_TILES; i++) await cache.delete(keys[i]);
}

async function tile(request, key) {
  const cache = await caches.open(TILE_CACHE);
  const hit = await cache.match(key);
  if (hit) return hit;
  const resp = await fetch(request);
  // Only cache real CORS responses: opaque ones are padded to megabytes each in storage quotas.
  if (resp.ok && resp.type !== "opaque") {
    await cache.put(key, resp.clone());
    if (++putsSinceTrim >= 50) { putsSinceTrim = 0; trimTiles(cache); }
  }
  return resp;
}

/** App shell: answer from the cache at once, refresh it in the background (stale-while-revalidate). */
async function shell(event) {
  const req = event.request;
  const cache = await caches.open(SHELL_CACHE);
  // Navigations (index.html?demo=1, ./) all get the one cached page.
  const hit = req.mode === "navigate"
    ? (await cache.match(scoped("index.html"))) || (await cache.match(scoped("./")))
    : await cache.match(req, { ignoreSearch: true });
  const network = fetch(req).then(resp => {
    if (resp.ok && resp.type === "basic") {
      const key = req.mode === "navigate" ? scoped("index.html") : new URL(req.url).origin + new URL(req.url).pathname;
      cache.put(key, resp.clone());
    }
    return resp;
  });
  if (hit) { event.waitUntil(network.catch(() => {})); return hit; }
  return network;
}

self.addEventListener("fetch", event => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  const key = tileKey(req.url);
  if (key) { event.respondWith(tile(req, key)); return; }
  // Same-origin files under our scope; forecasts, geocoding and weather.gov go straight to the network
  // (the app keeps its own copies of forecasts in IndexedDB).
  if (url.origin === self.location.origin && req.url.startsWith(self.registration.scope)) event.respondWith(shell(event));
});
