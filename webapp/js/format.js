// Text formatting, ported from ui/Format.kt and data/Settings.kt.
import { M_PER_FT } from "./wind.js";

export const SPEED_UNITS = {
  kt: { label: "kt", perMs: 1.943844 },
  mph: { label: "mph", perMs: 2.236936 },
  kmh: { label: "km/h", perMs: 3.6 },
  ms: { label: "m/s", perMs: 1 },
};

const pad2 = n => String(n).padStart(2, "0");

/** "Thu 14:00 · 2000Z" */
export function fmtTime(epochSec) {
  const d = new Date(epochSec * 1000);
  const wd = d.toLocaleDateString(undefined, { weekday: "short" });
  return `${wd} ${pad2(d.getHours())}:${pad2(d.getMinutes())} · ${pad2(d.getUTCHours())}${pad2(d.getUTCMinutes())}Z`;
}

export const fmtFeet = ft => Math.round(ft).toLocaleString("en-US");
export const fmtElevation = m => fmtFeet(m / M_PER_FT) + " ft";
export const fmtSpeed = (ms, unit) => String(Math.round(ms * SPEED_UNITS[unit].perMs));
/** Speed plus the gust forecast in brackets: "4 (18)". */
export const fmtSpeedGust = (w, unit) => fmtSpeed(w.sp, unit) + (w.gust != null ? ` (${fmtSpeed(w.gust, unit)})` : "");
export const fmtDir = deg => String(Math.round(deg) % 360).padStart(3, "0") + "°";
/** "-3C" / "27F": the unit letter, not "°", which is kept for compass directions. */
export const fmtTemp = (c, unit) => (c == null ? "" : Math.round(unit === "F" ? c * 9 / 5 + 32 : c) + unit);

export function fmtCoords(p) {
  return `${Math.abs(p.lat).toFixed(4)}°${p.lat >= 0 ? "N" : "S"} ${Math.abs(p.lon).toFixed(4)}°${p.lon >= 0 ? "E" : "W"}`;
}

/** Cloud heights: "4,300" or, short, "4.3k". */
export function cloudFt(ft, short) {
  const h = Math.round(ft / 100);
  if (ft === 0) return "SFC";
  if (short && h >= 10) return h % 10 === 0 ? `${h / 10}k` : `${Math.floor(h / 10)}.${h % 10}k`;
  return fmtFeet(h * 100);
}

/** "BKN 4,300–7,800 · SCT 12,100–13,000" (or "BKN 4.3k–7.8k, …" when short). */
export function fmtClouds(layers, short) {
  if (layers == null) return "no cloud data";
  if (!layers.length) return "clear";
  return layers.map(l => `${l.cover} ${cloudFt(l.base, short)}–${cloudFt(l.top, short)}`).join(short ? ", " : " · ");
}

export function fmtAge(atMs, now = Date.now()) {
  const min = Math.max(0, Math.floor((now - atMs) / 60000));
  if (min < 1) return "just now";
  if (min < 60) return `${min} min ago`;
  if (min < 48 * 60) return `${Math.floor(min / 60)} h ago`;
  return `${Math.floor(min / (24 * 60))} days ago`;
}

export const escapeHtml = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
