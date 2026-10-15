#!/usr/bin/env python3
"""
Captures a forecast + map-tile snapshot for the design playground, so it works where it can't
reach the network (e.g. as a claude.ai artifact). Writes playground/snapshot.js.

    python3 playground/tools/make_snapshot.py           # fresh snapshot
    python3 playground/tools/make_snapshot.py --merge   # keep what's there, fetch only what's missing
    python3 playground/tools/make_snapshot.py --clouds  # add cloud cover to an existing snapshot

Needs network access and Pillow (tiles are re-encoded as JPEG to keep the file small).
"""
import base64
import io
import json
import math
import subprocess
import sys
import time
from pathlib import Path

from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "snapshot.js"

LOCATIONS = [
    {"id": "boulder", "name": "Boulder, CO", "lat": 40.0150, "lon": -105.2705},
    {"id": "perris", "name": "Perris, CA", "lat": 33.7825, "lon": -117.2287},
    {"id": "deland", "name": "DeLand, FL", "lat": 29.0283, "lon": -81.3031},
]
MODELS = ["best_match", "ncep_gfs_seamless", "ncep_hrrr_conus", "ecmwf_ifs025", "icon_seamless", "gem_seamless", "ukmo_seamless"]
LEVELS = [1000, 975, 950, 925, 900, 850, 800, 750, 700, 650, 600, 550, 500, 450, 400, 350, 300, 250]
SURFACE = [(10, 2), (80, 80), (120, 120), (180, 180)]
HOURS = 72
ZOOM = 10
TILE_URL = "https://a.tile.opentopomap.org/{z}/{x}/{y}.png"


def hourly_vars():
    v = []
    for h, t in SURFACE:
        v += [f"wind_speed_{h}m", f"wind_direction_{h}m", f"temperature_{t}m"]
    for p in LEVELS:
        v += [f"wind_speed_{p}hPa", f"wind_direction_{p}hPa", f"geopotential_height_{p}hPa", f"temperature_{p}hPa", f"cloud_cover_{p}hPa"]
    return v


def get(url, binary=False):
    for attempt in range(3):
        r = subprocess.run(["curl", "-sS", "--max-time", "30", "-A", "WindsAloftPlayground/1.0", url], capture_output=True)
        if r.returncode == 0 and r.stdout:
            return r.stdout if binary else r.stdout.decode()
        time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"failed: {url}")


def forecast(loc, model):
    url = (
        "https://api.open-meteo.com/v1/forecast"
        f"?latitude={loc['lat']}&longitude={loc['lon']}&hourly={','.join(hourly_vars())}"
        f"&models={model}&wind_speed_unit=ms&timeformat=unixtime&forecast_hours={HOURS}"
    )
    d = json.loads(get(url))
    if d.get("error"):
        raise RuntimeError(f"{model}: {d.get('reason')}")
    hourly = {k: [None if x is None else round(x, 1) for x in v] for k, v in d["hourly"].items() if k != "time"}
    # Drop variables the model doesn't provide at all.
    hourly = {k: v for k, v in hourly.items() if any(x is not None for x in v)}
    hourly["time"] = d["hourly"]["time"]
    return {"elevation": d.get("elevation", 0), "hourly": hourly}


def tile_xy(lat, lon, z):
    n = 2 ** z
    x = (lon + 180) / 360 * n
    y = (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n
    return x, y


def tiles(loc):
    x, y = tile_xy(loc["lat"], loc["lon"], ZOOM)
    tx, ty = int(x), int(y)
    out = {}
    for yy in range(ty - 3, ty + 3):
        for xx in range(tx - 2, tx + 3):
            png = get(TILE_URL.format(z=ZOOM, x=xx, y=yy), binary=True)
            img = Image.open(io.BytesIO(png)).convert("RGB")
            buf = io.BytesIO()
            img.save(buf, "JPEG", quality=72, optimize=True)
            out[f"{ZOOM}/{xx}/{yy}"] = "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()
            time.sleep(0.15)  # be gentle with the tile server
    return out


def load_existing():
    text = OUT.read_text()
    return json.loads(text[text.index("=") + 1:].strip().rstrip(";"))


def add_clouds():
    """Fetches only per-level cloud cover and merges it into the existing snapshot by timestamp."""
    snap = load_existing()
    names = [f"cloud_cover_{p}hPa" for p in LEVELS]
    for loc in snap["locations"]:
        for model, fc in loc["forecasts"].items():
            if any(n in fc["hourly"] for n in names):
                continue
            url = (f"https://api.open-meteo.com/v1/forecast?latitude={loc['lat']}&longitude={loc['lon']}"
                   f"&hourly={','.join(names)}&models={model}&timeformat=unixtime&forecast_hours=96")
            try:
                d = json.loads(get(url))
                if d.get("error"):
                    raise RuntimeError(d.get("reason"))
            except Exception as e:
                print(f"{loc['id']} {model}: clouds skipped ({e})", file=sys.stderr)
                continue
            by_time = {t: i for i, t in enumerate(d["hourly"]["time"])}
            got = 0
            for n in names:
                src = d["hourly"].get(n)
                if not src or all(x is None for x in src):
                    continue
                fc["hourly"][n] = [src[by_time[t]] if t in by_time else None for t in fc["hourly"]["time"]]
                got += 1
            print(f"{loc['id']} {model}: clouds at {got} levels", file=sys.stderr)
            write({**snap, "locations": []}, snap["locations"])
            time.sleep(1)


def main():
    if "--clouds" in sys.argv:
        add_clouds()
        return
    merge = "--merge" in sys.argv and OUT.exists()
    old = {l["id"]: l for l in load_existing()["locations"]} if merge else {}
    snap = {"capturedAt": load_existing()["capturedAt"] if merge else int(time.time()), "zoom": ZOOM, "locations": []}
    for loc in LOCATIONS:
        prev = old.get(loc["id"], {})
        entry = dict(loc, forecasts=dict(prev.get("forecasts", {})), tiles=dict(prev.get("tiles", {})))
        for m in MODELS:
            if m in entry["forecasts"]:
                continue
            try:
                entry["forecasts"][m] = forecast(loc, m)
                print(f"{loc['id']} {m}: ok", file=sys.stderr)
            except Exception as e:  # a model may not cover a location (e.g. HRRR outside the US)
                print(f"{loc['id']} {m}: skipped ({e})", file=sys.stderr)
        if not entry["tiles"]:
            entry["tiles"] = tiles(loc)
        print(f"{loc['id']} tiles: {len(entry['tiles'])}", file=sys.stderr)
        snap["locations"].append(entry)
        # Save after each location so a slow or interrupted run keeps its progress (resume with --merge).
        done = {l["id"] for l in snap["locations"]}
        write(snap, [l for l in old.values() if l["id"] not in done])


def write(snap, extra=()):
    snap = dict(snap, locations=snap["locations"] + list(extra))
    OUT.write_text("// Generated by playground/tools/make_snapshot.py — forecast + map tile snapshot.\n"
                   "window.WA_SNAPSHOT = " + json.dumps(snap, separators=(",", ":")) + ";\n")
    print(f"wrote {OUT} ({OUT.stat().st_size / 1e6:.1f} MB)", file=sys.stderr)


if __name__ == "__main__":
    main()
