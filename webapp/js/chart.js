// The on-map wind chart, ported from map/WindOverlay.kt (and the playground's canvas port of it).
// Units are CSS pixels (Android dp); the caller scales the context for devicePixelRatio.
//
// Stacked: one vertical column per model rising from the pin, one row per 1,000 ft (lowest at the
// bottom), at a fixed spacing. Each column reserves the farthest an arrow could reach plus a label slot,
// so nothing moves or resizes as the forecast time changes. Arrows are windsocks: tail on the column
// line, pointing downwind. Cloud layers are bands behind the rows; layers outside the shown altitudes
// get ▲/▼ markers. Fan: all arrows share the pin as origin, length proportional to speed.
import { KT_PER_MS } from "./wind.js";
import { css, haloFor, windColor, WHITE } from "./colors.js";

export const ROW = 38;               // row spacing
export const STACK_LENGTH = 1.25;    // full-speed arrow length, as a fraction of the row
export const COMPARE_LENGTH = 0.85;  // shorter when comparing, so three columns fit on a phone
export const STACK_FULL_KT = 40;     // full length from this speed
export const HANDLE_R = 9;
export const HANDLE_GAP = 6;
const CLOUD_ALPHA = { SCT: 0.32, BKN: 0.5, OVC: 0.66 };
const FONT = 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';

/** Stacked arrow length as a fraction of full length: half at calm, full from STACK_FULL_KT. */
export const stackedFraction = kt => 0.5 + 0.5 * Math.min(1, Math.max(0, kt / STACK_FULL_KT));
/** Fan arrow length for a speed in knots. */
export const arrowLength = kt => Math.min(160, 12 + kt * 2.2);
export const DEFAULT_ANCHOR = { arrow: "tail", triangle: "nose", barb: "tail" };

const clamp = (v, a, b) => Math.min(b, Math.max(a, v));

function setFont(ctx, size) { ctx.font = `700 ${size}px ${FONT}`; }

function text(ctx, s, x, y, align, color) {
  ctx.textAlign = align;
  ctx.textBaseline = "alphabetic";
  ctx.lineJoin = "round";
  ctx.lineWidth = 3.5;
  ctx.strokeStyle = "rgba(0,0,0,0.9)";
  ctx.strokeText(s, x, y);
  ctx.fillStyle = color;
  ctx.fillText(s, x, y);
}

function line(ctx, x0, y0, x1, y1, color, width) {
  ctx.strokeStyle = color; ctx.lineWidth = width;
  ctx.beginPath(); ctx.moveTo(x0, y0); ctx.lineTo(x1, y1); ctx.stroke();
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath();
  if (ctx.roundRect) ctx.roundRect(x, y, w, h, r);
  else { // older Safari
    ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
  }
}

function shortFt(ft) {
  const h = Math.round(ft / 100);
  if (ft === 0) return "SFC";
  if (h >= 10) return h % 10 === 0 ? `${h / 10}k` : `${Math.floor(h / 10)}.${h % 10}k`;
  return String(h * 100);
}

/**
 * Draws pending marker, leader line, pin, chart and handle. scene:
 * { pin:{x,y}|null, chart:{x,y}|null, pending:{x,y}|null, columns:[{label, color, winds, clouds}],
 *   altitudesFt, o:{layout, glyph, anchor, colorMode, showTemperature, showSpeed, showDirection,
 *   showClouds, ceilingFt, speedLabel(ms), tempLabel(c)} }
 * Returns the handle's centre {x, y} (for hit testing), or null when there's no pin.
 */
export function drawScene(ctx, scene) {
  const { pin, pending } = scene;
  if (pending) drawPendingMarker(ctx, pending.x, pending.y);
  if (!pin) return null;
  const at = scene.chart || pin;
  const detached = !!scene.chart;
  if (detached) {
    ctx.setLineDash([7, 5]); ctx.lineCap = "butt";
    line(ctx, at.x, at.y, pin.x, pin.y, "rgba(0,0,0,0.6)", 5);
    line(ctx, at.x, at.y, pin.x, pin.y, "#ffffff", 2.2);
    ctx.setLineDash([]);
  }
  drawPin(ctx, pin.x, pin.y);
  if (scene.columns.length) {
    if (scene.o.layout === "fan") drawFan(ctx, at.x, at.y, scene);
    else drawColumns(ctx, at.x, at.y, scene);
  }
  return drawHandle(ctx, at.x, at.y, detached);
}

/** A round grip just below the chart's base: drag it to move the chart. */
function drawHandle(ctx, x, y, detached) {
  const cy = y + HANDLE_R + HANDLE_GAP;
  ctx.fillStyle = detached ? "#1C2733" : "rgba(28,39,51,0.9)";
  ctx.beginPath(); ctx.arc(x, cy, HANDLE_R, 0, Math.PI * 2); ctx.fill();
  ctx.lineCap = "round";
  ctx.strokeStyle = "#ffffff"; ctx.lineWidth = 1.5; ctx.stroke();
  const a = HANDLE_R * 0.55;
  line(ctx, x - a, cy, x + a, cy, "#ffffff", 1.6);
  line(ctx, x, cy - a, x, cy + a, "#ffffff", 1.6);
  return { x, y: cy };
}

/** A provisional spot from a long-press, shown until the user confirms or cancels. */
function drawPendingMarker(ctx, x, y) {
  ctx.setLineDash([5, 4]);
  ctx.strokeStyle = "rgba(0,0,0,0.6)"; ctx.lineWidth = 5;
  ctx.beginPath(); ctx.arc(x, y, 14, 0, Math.PI * 2); ctx.stroke();
  ctx.strokeStyle = "#ffffff"; ctx.lineWidth = 2.2; ctx.stroke();
  ctx.setLineDash([]);
  ctx.fillStyle = "#fff"; ctx.strokeStyle = "#202020"; ctx.lineWidth = 1;
  ctx.beginPath(); ctx.arc(x, y, 3, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
}

function drawPin(ctx, x, y) {
  ctx.fillStyle = "#ffffff"; ctx.strokeStyle = "#202020"; ctx.lineWidth = 1.5;
  ctx.beginPath(); ctx.arc(x, y, 5, 0, Math.PI * 2); ctx.fill(); ctx.stroke();
  ctx.strokeStyle = "#E53935"; ctx.lineWidth = 2.5;
  ctx.beginPath(); ctx.arc(x, y, 9, 0, Math.PI * 2); ctx.stroke();
}

// ---------------------------------------------------------------- stacked columns

function drawColumns(ctx, x, y, scene) {
  const { columns, altitudesFt, o } = scene;
  const rows = Math.max(...columns.map(c => c.winds.length));
  if (!rows) return;
  const cell = ROW;
  // Rows below the terrain (MSL altitudes under the ground) are left out, so the column starts at the pin.
  let skip = 0;
  while (skip < rows && !columns.some(c => c.winds[skip])) skip++;
  if (skip >= rows) return;
  const rowY = i => y - cell * (i - skip + 0.5);
  const rowAlt = i => altitudesFt[i] ?? columns.map(c => c.winds[i]).find(Boolean)?.alt;
  const loShown = rowAlt(skip);
  const hiShown = rowAlt(rows - 1) ?? loShown;
  const altY = ft => y - cell * ((ft - loShown) / 1000 + 0.5);
  const top = y - cell * (rows - skip);

  const textSize = clamp(cell * 0.36, 10, 15);
  const tempSize = textSize * 0.86;
  const stroke = clamp(cell * 0.085, 2.4, 4.2);
  const labelEvery = Math.max(1, Math.ceil(textSize * 1.15 / cell));
  const comparing = columns.length > 1;
  const width = (s, size) => { setFont(ctx, size); return ctx.measureText(s).width; };

  // Fixed geometry, independent of the forecast: each column reserves the farthest an arrow could
  // reach in any direction, then a label slot sized for the widest possible text.
  const lenScale = comparing ? COMPARE_LENGTH : STACK_LENGTH;
  const reach = (o.anchor === "center" ? cell * lenScale / 2 : cell * lenScale) + stroke * 1.5 + 1;
  // The ground row also shows gusts, "12 (25)": reserve room for that whenever there is a ground row.
  const hasSurface = columns.some(c => c.winds.some(w => w?.surface));
  const topTemplate = [o.showDirection ? "888°" : null, o.showSpeed ? (hasSurface ? "88 (88)" : "888") : null].filter(Boolean).join(" ");
  const topW = topTemplate ? width(topTemplate, textSize) : 0;
  const tempW = o.showTemperature ? width("-88F", tempSize) : 0;
  const labelW = topW === 0 && tempW === 0 ? 0 : Math.max(topW, tempW) + 6;
  const gap = 4;
  const colW = 2 * reach + labelW + gap;
  const altW = width("14k", textSize) + 12;
  const blockW = altW + columns.length * colW - gap;
  const cx0 = comparing ? x - blockW / 2 + altW + reach : x; // one column sits on the pin
  const cxOf = j => cx0 + j * colW;
  const colL = j => cxOf(j) - reach;
  const colR = j => cxOf(j) + reach + labelW;
  const blockRight = colR(columns.length - 1);

  // Clouds: bands for layers within the shown altitudes, markers for layers above or below them.
  const above = [], below = [];
  if (o.showClouds) columns.forEach((col, j) => {
    for (const L of col.clouds || []) {
      if (L.base > hiShown) { above.push([j, L]); continue; }
      if (L.top < loShown) { below.push([j, L]); continue; }
      const yb = Math.min(y, altY(L.base));
      const yt = Math.max(top, altY(L.top + 100));
      if (yb <= yt) continue;
      roundRect(ctx, colL(j), yt, colR(j) - colL(j), yb - yt, 6);
      ctx.fillStyle = `rgba(236,242,248,${CLOUD_ALPHA[L.cover]})`; ctx.fill();
      ctx.strokeStyle = "rgba(255,255,255,0.95)"; ctx.lineWidth = 1.2; ctx.stroke();
      if (!comparing) {
        const mid = (yb + yt) / 2;
        setFont(ctx, textSize * 0.9); text(ctx, L.cover, blockRight + 8, mid - 2, "left", "#ffffff");
        setFont(ctx, textSize * 0.8); text(ctx, `${shortFt(L.base)}–${shortFt(L.top)}`, blockRight + 8, mid + textSize * 0.85, "left", "#E6EEF5");
      }
    }
  });

  ctx.lineCap = "round";
  if (comparing) {
    // Base bar tying the columns to the pin.
    const x0 = Math.min(cxOf(0), x), x1 = Math.max(cxOf(columns.length - 1), x);
    line(ctx, x0, y, x1, y, "rgba(0,0,0,0.533)", 4);
    line(ctx, x0, y, x1, y, "rgba(255,255,255,0.933)", 2);
  }

  columns.forEach((col, j) => {
    const cx = cxOf(j);
    line(ctx, cx, y, cx, top, "rgba(0,0,0,0.533)", 4);
    line(ctx, cx, y, cx, top, col.color ? css(col.color) : "rgba(255,255,255,0.933)", 1.6);
    col.winds.forEach((w, i) => {
      if (!w) return;
      const cy = rowY(i);
      const color = windColor(o.colorMode, w, col.color, o.ceilingFt);
      drawGlyph(ctx, cx, cy, w, cell * lenScale, cell * 0.16, stroke, color, o.anchor, o.glyph, true);
      if (i % labelEvery !== 0 && !w.surface) return;
      // Label slot: direction + speed on top, temperature underneath, always at the same spot.
      const lx = cx + reach + 4;
      const hasTemp = o.showTemperature && w.t != null;
      const topLine = [
        o.showDirection ? String(Math.round(w.d) % 360).padStart(3, "0") + "°" : null,
        o.showSpeed ? o.speedLabel(w.sp) + (w.gust != null ? ` (${o.speedLabel(w.gust)})` : "") : null,
      ].filter(Boolean).join(" ");
      if (topLine) {
        setFont(ctx, textSize);
        text(ctx, topLine, lx, hasTemp ? cy - 1 : cy + textSize * 0.36, "left", "#ffffff");
      }
      if (hasTemp) {
        setFont(ctx, tempSize);
        const tint = o.colorMode === "temperature" ? "#ffffff" : temperatureTint(w.t);
        text(ctx, o.tempLabel(w.t), lx, topLine ? cy + tempSize + 1 : cy + tempSize * 0.36, "left", tint);
      }
    });
    if (col.label) {
      setFont(ctx, textSize);
      text(ctx, col.label, cx + labelW / 2, top - 6, "center", css(col.color || WHITE));
    }
  });

  // Out-of-range cloud markers: stacked upward above the columns, downward below the pin.
  const pillH = textSize + 8;
  const stack = {};
  for (const [j, L] of above) {
    const n = (stack["a" + j] = (stack["a" + j] ?? -1) + 1);
    const yc = top - (comparing ? textSize + 12 : 8) - pillH / 2 - n * (pillH + 3);
    cloudMarker(ctx, (colL(j) + colR(j)) / 2, yc, `▲ ${L.cover} ${shortFt(L.base)}–${shortFt(L.top)}`, L.cover, textSize * 0.82, pillH);
  }
  for (const [j, L] of below) {
    const n = (stack["b" + j] = (stack["b" + j] ?? -1) + 1);
    // Below the pin, past the chart handle.
    const yc = y + 12 + pillH / 2 + n * (pillH + 3) + HANDLE_R * 2;
    cloudMarker(ctx, (colL(j) + colR(j)) / 2, yc, `▼ ${L.cover} ${shortFt(L.base)}–${shortFt(L.top)}`, L.cover, textSize * 0.82, pillH);
  }

  // Altitude labels down the left side.
  setFont(ctx, textSize);
  const altX = colL(0) - 6;
  let surfaceRow = -1;
  for (let i = skip; i < rows; i++) if (columns.some(c => c.winds[i]?.surface)) { surfaceRow = i; break; }
  for (let i = skip; i < rows; i++) {
    if (i % labelEvery !== 0 && i !== surfaceRow) continue;
    const alt = rowAlt(i);
    if (alt == null) continue;
    const label = alt === 0 || i === surfaceRow ? "SFC" : alt % 1000 === 0 ? `${alt / 1000}k` : String(alt);
    text(ctx, label, altX, rowY(i) + textSize * 0.36, "right", "#ffffff");
  }
  // When MSL rows were dropped, the ground is at the pin: mark it (unless the ground-wind row already does).
  if (skip > 0 && surfaceRow !== skip) {
    ctx.setLineDash([6, 4]);
    line(ctx, colL(0), y, blockRight, y, "rgba(0,0,0,0.533)", 4);
    line(ctx, colL(0), y, blockRight, y, "#ffffff", 2);
    ctx.setLineDash([]);
    text(ctx, "GND", altX, y + textSize * 0.36, "right", "#ffffff");
  }
}

/** A small pill naming a cloud layer that lies outside the shown altitudes. */
function cloudMarker(ctx, cx, yc, label, cover, size, h) {
  setFont(ctx, size);
  const w = ctx.measureText(label).width + 14;
  roundRect(ctx, cx - w / 2, yc - h / 2, w, h, h / 2);
  ctx.fillStyle = `rgba(236,242,248,${Math.min(1, 0.55 + CLOUD_ALPHA[cover] * 0.6)})`; ctx.fill();
  ctx.strokeStyle = "rgba(255,255,255,0.95)"; ctx.lineWidth = 1.2; ctx.stroke();
  ctx.fillStyle = "#1C2733"; ctx.textAlign = "center"; ctx.textBaseline = "middle";
  ctx.fillText(label, cx, yc + 0.5);
  ctx.textBaseline = "alphabetic";
}

/** Label tint: pale blue below freezing, pale orange above, white around 0 C. */
const temperatureTint = c => (c <= -1 ? "#B8E4FF" : c >= 1 ? "#FFD6AD" : "#FFFFFF");

// ---------------------------------------------------------------- fan

function drawFan(ctx, x, y, scene) {
  const { columns, o } = scene;
  // First (primary) model last so it ends up on top.
  for (const col of columns.slice().reverse()) {
    for (const w of col.winds.filter(Boolean).sort((a, b) => b.alt - a.alt)) {
      const color = windColor(o.colorMode, w, col.color, o.ceilingFt);
      const len = o.glyph === "barb" ? 44 : arrowLength(w.sp * KT_PER_MS);
      drawGlyph(ctx, x, y, w, len, 8, 3, color, "tail", o.glyph, false);
    }
  }
}

// ---------------------------------------------------------------- glyphs

/**
 * One wind glyph, running from its tail to its nose (arrowhead, triangle apex, or a barb's feathered
 * end); anchor picks which end sits at (cx, cy). Arrows point downwind (a windsock), triangles point
 * into the wind (a landing tetrahedron), barbs follow the weather-chart convention.
 */
function drawGlyph(ctx, cx, cy, w, fullLen, calmRadius, stroke, rgb, anchor, glyph, scaleBySpeed) {
  const color = css(rgb);
  const kt = w.sp * KT_PER_MS;
  if (glyph === "barb") return drawBarb(ctx, cx, cy, w, fullLen, calmRadius, stroke, rgb, anchor);
  if (kt < 1) {
    ctx.strokeStyle = color; ctx.lineWidth = stroke * 0.8;
    ctx.beginPath(); ctx.arc(cx, cy, calmRadius, 0, Math.PI * 2); ctx.stroke();
    return;
  }
  const len = scaleBySpeed ? fullLen * stackedFraction(kt) : fullLen;
  const a = (w.d + (glyph === "triangle" ? 0 : 180)) * Math.PI / 180;
  const dx = Math.sin(a), dy = -Math.cos(a);
  const back = anchor === "nose" ? len : anchor === "center" ? len / 2 : 0;
  const sx = cx - dx * back, sy = cy - dy * back;
  const ex = sx + dx * len, ey = sy + dy * len;
  const halo = haloFor(rgb);
  if (glyph === "triangle") {
    const hw = len * 0.22;
    ctx.beginPath(); ctx.moveTo(ex, ey); ctx.lineTo(sx - dy * hw, sy + dx * hw); ctx.lineTo(sx + dy * hw, sy - dx * hw); ctx.closePath();
    ctx.lineJoin = "round"; ctx.lineWidth = 2.2; ctx.strokeStyle = halo; ctx.stroke();
    ctx.fillStyle = color; ctx.fill();
    return;
  }
  // Arrow: shaft then a filled head.
  const head = Math.min(stroke * 3, len * 0.45);
  const bx = ex - dx * head, by = ey - dy * head;
  ctx.lineCap = "round";
  line(ctx, sx, sy, bx, by, halo, stroke + 2.4);
  line(ctx, sx, sy, bx, by, color, stroke);
  const hw = head * 0.62;
  ctx.beginPath(); ctx.moveTo(ex, ey); ctx.lineTo(bx - dy * hw, by + dx * hw); ctx.lineTo(bx + dy * hw, by - dx * hw); ctx.closePath();
  ctx.lineJoin = "round"; ctx.lineWidth = 1.8; ctx.strokeStyle = halo; ctx.stroke();
  ctx.fillStyle = color; ctx.fill();
}

/** Wind barb: the staff points into the wind; 50 kt pennants, 10 kt barbs, 5 kt half-barbs. */
function drawBarb(ctx, cx, cy, w, len, calmRadius, stroke, rgb, anchor) {
  const color = css(rgb);
  const kt = Math.round(w.sp * KT_PER_MS / 5) * 5;
  if (kt < 5) {
    ctx.strokeStyle = color; ctx.lineWidth = stroke * 0.8;
    ctx.beginPath(); ctx.arc(cx, cy, calmRadius, 0, Math.PI * 2); ctx.stroke();
    return;
  }
  const a = w.d * Math.PI / 180; // toward where the wind comes from
  const dx = Math.sin(a), dy = -Math.cos(a);
  // Tail = the plain (station) end, nose = the feathered end, which faces into the wind.
  const back = anchor === "nose" ? len : anchor === "center" ? len / 2 : 0;
  const sx = cx - dx * back, sy = cy - dy * back;
  const ex = sx + dx * len, ey = sy + dy * len;
  const px = -dy, py = dx; // feathers on the clockwise side
  const fl = len * 0.42, step = len * 0.15;
  let rest = kt, pos = 0;
  const pennants = [], feathers = [];
  while (rest >= 50) { pennants.push(pos); pos += step * 1.3; rest -= 50; }
  while (rest >= 10) { feathers.push([pos, fl]); pos += step; rest -= 10; }
  if (rest >= 5) feathers.push([pos === 0 ? step : pos, fl * 0.55]);
  const path = () => {
    ctx.beginPath(); ctx.moveTo(sx, sy); ctx.lineTo(ex, ey);
    for (const [at, l] of feathers) {
      const bx = ex - dx * at, by = ey - dy * at;
      ctx.moveTo(bx, by); ctx.lineTo(bx + px * l + dx * l * 0.35, by + py * l + dy * l * 0.35);
    }
  };
  const sw = Math.max(1.6, stroke * 0.7);
  ctx.lineCap = "round"; ctx.lineJoin = "round";
  path(); ctx.strokeStyle = haloFor(rgb); ctx.lineWidth = sw + 2.4; ctx.stroke();
  path(); ctx.strokeStyle = color; ctx.lineWidth = sw; ctx.stroke();
  ctx.fillStyle = color;
  for (const p of pennants) {
    const bx = ex - dx * p, by = ey - dy * p;
    ctx.beginPath(); ctx.moveTo(bx, by); ctx.lineTo(bx + px * fl, by + py * fl); ctx.lineTo(bx - dx * step * 1.1, by - dy * step * 1.1); ctx.closePath();
    ctx.fill();
  }
}
