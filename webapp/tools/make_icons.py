#!/usr/bin/env python3
"""Draws the web app icons (PNG) from the same design as the Android launcher icon
(app/src/main/res/drawable/ic_launcher_foreground.xml on #0D3B66): three wind arrows,
blue / yellow-green / orange, fanning out from one white dot.

    python3 webapp/tools/make_icons.py

Writes webapp/icons/{icon-192,icon-512,icon-maskable-512,apple-touch-icon,favicon-32}.png.
Needs Pillow.
"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "icons")
BG = (0x0D, 0x3B, 0x66, 255)

# The Android vector, in its 108x108 viewport: (stroke start, stroke end, arrowhead triangle, colour).
ARROWS = [
    ((38, 70), (64, 62), [(74, 59), (62, 55), (65, 68)], (0x3F, 0xC1, 0xE8, 255)),
    ((38, 70), (66, 46), [(74, 39), (61, 41), (69, 52)], (0xC6, 0xE8, 0x3F, 255)),
    ((38, 70), (55, 36), [(59, 27), (49, 35), (62, 40)], (0xF2, 0x62, 0x2E, 255)),
]


def draw(size, view):
    """Renders the viewport window `view` = (x0, y0, width) of the 108 grid onto a size x size square."""
    ss = 4  # supersample for smooth edges
    big = size * ss
    img = Image.new("RGBA", (big, big), BG)
    d = ImageDraw.Draw(img)
    x0, y0, w = view
    k = big / w

    def p(pt):
        return ((pt[0] - x0) * k, (pt[1] - y0) * k)

    for start, end, head, colour in ARROWS:
        sw = 5 * k
        d.line([p(start), p(end)], fill=colour, width=round(sw))
        for q in (start, end):  # round caps
            cx, cy = p(q)
            d.ellipse([cx - sw / 2, cy - sw / 2, cx + sw / 2, cy + sw / 2], fill=colour)
        d.polygon([p(q) for q in head], fill=colour)
    cx, cy = p((38, 70))
    r = 5 * k
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(255, 255, 255, 255))
    return img.resize((size, size), Image.LANCZOS).convert("RGB")


def main():
    os.makedirs(OUT, exist_ok=True)
    # "any" icons: crop to the artwork so it fills the tile (iOS / launchers round the corners).
    tight = (20, 16, 72)
    # Maskable: the full 108 grid, whose art already sits inside Android's 66/108 safe zone.
    full = (0, 0, 108)
    draw(192, tight).save(os.path.join(OUT, "icon-192.png"), optimize=True)
    draw(512, tight).save(os.path.join(OUT, "icon-512.png"), optimize=True)
    draw(512, full).save(os.path.join(OUT, "icon-maskable-512.png"), optimize=True)
    draw(180, tight).save(os.path.join(OUT, "apple-touch-icon.png"), optimize=True)
    draw(32, tight).save(os.path.join(OUT, "favicon-32.png"), optimize=True)
    print("icons written to", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
