#!/usr/bin/env python3
"""Builds webapp/demo/demo-data.json (the ?demo=1 data) from the playground's captured forecast,
playground/snapshot.js, without its map tiles.

    python3 -I webapp/tools/make_demo.py            # also tries to fetch real 10 m gusts
    python3 -I webapp/tools/make_demo.py --offline  # synthetic gusts only

The playground snapshot predates the ground-wind row, so it has no wind_gusts_10m. The script asks
Open-Meteo for just that variable over the same hours (past_days=1); where that fails it writes a
rough synthetic gust (1.5 x the 10 m wind + 2 m/s) and marks the model with "syntheticGusts": true.
"""
import json
import os
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SNAPSHOT = os.path.join(HERE, "..", "..", "playground", "snapshot.js")
OUT = os.path.join(HERE, "..", "demo", "demo-data.json")


def fetch_gusts(lat, lon, model):
    url = ("https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&hourly=wind_gusts_10m"
           "&models=%s&wind_speed_unit=ms&timeformat=unixtime&past_days=1&forecast_days=4" % (lat, lon, model))
    req = urllib.request.Request(url, headers={"User-Agent": "WindsAloft-webapp-demo-builder"})
    with urllib.request.urlopen(req, timeout=20) as r:
        d = json.load(r)
    h = d.get("hourly") or {}
    return dict(zip(h.get("time", []), h.get("wind_gusts_10m", [])))


def main():
    offline = "--offline" in sys.argv
    src = open(SNAPSHOT, encoding="utf-8").read()
    snap = json.loads(src[src.index("{"):src.rindex("}") + 1])
    out = {"capturedAt": snap["capturedAt"], "source": "playground/snapshot.js", "locations": []}
    for loc in snap["locations"]:
        entry = {k: loc[k] for k in ("id", "name", "lat", "lon")}
        entry["forecasts"] = {}
        for model, fc in loc["forecasts"].items():
            hourly = fc["hourly"]
            times = hourly["time"]
            gusts, synthetic = None, False
            for attempt in range(0 if offline else 3):
                try:
                    live = fetch_gusts(loc["lat"], loc["lon"], model)
                    g = [live.get(t) for t in times]
                    if sum(v is not None for v in g) > len(times) // 2:
                        gusts = g
                    break
                except Exception as e:  # noqa: BLE001 - any failure falls back to synthetic gusts
                    print("  %s/%s: no live gusts (%s)" % (loc["id"], model, e))
            if gusts is None:
                synthetic = True
                gusts = [None if s is None else round(s * 1.5 + 2.0, 1) for s in hourly.get("wind_speed_10m", [])]
            hourly["wind_gusts_10m"] = gusts
            entry["forecasts"][model] = {"elevation": fc["elevation"], "hourly": hourly, "syntheticGusts": synthetic}
            print("%s %s gusts=%s" % (loc["id"], model, "synthetic" if synthetic else "live"))
        out["locations"].append(entry)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, separators=(",", ":"))
    print("wrote", os.path.normpath(OUT), os.path.getsize(OUT), "bytes")


if __name__ == "__main__":
    main()
