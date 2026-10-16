# Winds Aloft for iPhone (web app)

A web-app version of the Android app: upper-wind forecasts at 1,000 ft steps, drawn as a column of
arrows rising from a pinned spot on the map. Plain HTML, CSS and JavaScript with no build step.
Leaflet 1.9.4 is included in `vendor/`.

## Try it on an iPhone

1. Open the app's address in **Safari** (for example `https://<user>.github.io/winds-aloft/app/`).
2. Tap **Share** (the square with an arrow), then **Add to Home Screen**, then **Add**.
3. Start it from the new **Winds Aloft** icon. It opens full screen like an app, and its saved
   places, settings and map tiles are kept on the phone.

To try it without live data, add `?demo=1` to the address. This uses a forecast captured for
Boulder, Perris and DeLand (from `playground/snapshot.js`), shifted so it starts at the current
hour. Demo mode keeps its own settings and places, separate from the real ones.

How to use it:
- **Pick a spot**: long-press the map (about half a second), then tap *Get winds*. A plain tap does
  nothing. You can also search for a place, type `lat, lon`, or tap *My location*.
- **Time and altitudes**: use the sliders. Tap the spot's panel to open the table.
- **Move the chart**: drag the ⊕ handle under the chart. Drop it near the pin to put it back.
- **More map room**: the full-screen button switches to the time slider only, then to no panels.
- **Saved places**: tap ☆ on the spot's panel. The bookmark button in the search bar opens your
  saved places and your recent spots.

## Hosting

Every path is relative, so the folder works from any sub-path. Copy `webapp/` as it is to any
static HTTPS host, such as GitHub Pages. Location and the service worker need HTTPS, or
`localhost` while testing. To test locally:

```bash
python3 -m http.server 8000 --directory webapp   # then open http://localhost:8000/
```

When you change any file, bump `VERSION` in `sw.js`. The app shell is served from the cache and
refreshed in the background, so users get the new version the next time they start the app.

## Same as on Android

- One column per model (up to 3), with a row every 1,000 ft. The layout is fixed, so nothing
  resizes when the time changes.
- Wind symbols: windsock arrows (default), triangles or wind barbs, plus the *On the column line*
  setting.
- Colours: auto, altitude, speed, temperature (−40 is black, 0C is blue, 40 is red), model or
  white. Dark symbols get a light outline.
- Numbers on the map: speed, direction and temperature ("-3C", "27F").
- Clouds: cloud bands, plus ▲/▼ markers for layers above or below the shown altitudes.
- The bottom **SFC** row is the 10 m wind with the gust forecast in brackets, e.g. "4 (18)".
- Altitudes in MSL or AGL. In MSL, rows below the ground are left out.
- Ceiling of 14k, 18k, 24k or 30k ft.
- Stacked or fan layout.
- Forecast time from now to the end of the data, in 1 h steps, in local time and Zulu time.
- Altitude range slider.
- Spot panel: elevation, model, data age and a cloud summary. It opens to the full table, or to the
  compare table with Δ (largest difference between the models) when you pick several models.
- Saved places (renameable) and the last 20 spots.
- Offline:
  - Forecasts are kept per spot and model for saved places, recent spots and the current pin.
  - The panel says *Offline · data from 3 h ago*.
  - Saved places are re-downloaded when you're online and their winds are over an hour old.
  - Viewed map tiles are cached, up to about 3,000.
- Same Open-Meteo request as the app, including `wind_gusts_10m`. Same model list; `ecmwf_ifs`
  is left out because it has no upper-air data.

## Different from Android

- **No map rotation, no compass and no landscape side layout.** Leaflet can't rotate the map.
  Turned sideways, the panels simply get wider.
- **Airport reports (observed now) are US-only.** They come from NOAA's `api.weather.gov`, because
  `aviationweather.gov` doesn't allow requests from web pages.
  - The panel shows the nearest 4 stations within 100 km: wind, gust, temperature, age and the raw
    METAR.
  - Small wind-arrow markers show the stations on the map; tap a marker for its METAR.
  - Outside the US the panel says so.
- **Offline storage limits:**
  - The tile cache holds about 3,000 tiles, not 1 GB.
  - iOS may clear website data for a page in Safari that hasn't been used for a while. Data kept
    by the app added to the Home Screen is kept.
  - Before a trip, open the drop zone once while online, zoom around it, and save it with ☆.
- **Online detection is simpler.** The app treats itself as offline when the browser says so or a
  download fails, then checks again every minute. Wi-Fi that needs a login before it works can
  take one failed download to notice.
- **Gestures:**
  - Long-press is the app's own 550 ms touch-hold.
  - On a computer, right-click also picks a spot.
  - There's no vibration feedback on iPhone.
- **Fonts and sizes** use the system font and CSS pixels instead of Android dp, so text can look
  slightly different.

## Files

| Path | What |
|---|---|
| `index.html` | page shell, iOS/PWA meta tags |
| `css/app.css` | layout, panels, sheets, dark mode |
| `js/app.js` | state, map, long-press, panels, settings, places, offline logic (like `MainViewModel.kt` + `WindsAloftApp.kt`) |
| `js/wind.js` | u/v interpolation, clouds, surface row with gusts, Open-Meteo URL + parser (`WindMath.kt`, `OpenMeteo.kt`) |
| `js/chart.js` | the canvas chart: columns, glyphs, clouds, handle, leader (`WindOverlay.kt`) |
| `js/colors.js` | colour ramps and halo (`AltitudeColors.kt`) |
| `js/format.js` | number, time and cloud text (`Format.kt`) |
| `js/store.js` | localStorage settings and places, IndexedDB forecast cache (`Settings.kt`, `Places.kt`, `WindCache.kt`) |
| `js/metar.js` | api.weather.gov stations and latest observations |
| `sw.js` | service worker: app shell (stale-while-revalidate) and map tiles (cache-first, capped) |
| `manifest.webmanifest`, `icons/` | install metadata and icons (`tools/make_icons.py`) |
| `demo/demo-data.json` | demo forecast (`tools/make_demo.py`, built from `playground/snapshot.js`) |
| `vendor/leaflet-1.9.4/` | Leaflet 1.9.4 (BSD-2-Clause), from `npm pack leaflet@1.9.4` |

## Data and attribution

- Weather: [Open-Meteo](https://open-meteo.com) (CC BY 4.0).
- Airport reports: NOAA/NWS api.weather.gov.
- Maps: © OpenStreetMap contributors, © OpenTopoMap (CC-BY-SA), Imagery © Esri.

Forecasts are model output, not an official aviation product. Use them alongside official winds
aloft.
