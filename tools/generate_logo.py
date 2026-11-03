#!/usr/bin/env python3
"""Draws the IsoMap logo: a small isometric piece of terrain with the map's player arrow on top. All original artwork.

Usage:
    python -m pip install pillow
    python tools/generate_logo.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 512
SCALE = 4  # drawn large and scaled down for clean edges
OUT = Path(__file__).resolve().parent.parent

BACKGROUND = (16, 18, 21)
GRASS = (127, 178, 56)
SAND = (214, 190, 110)
STONE = (136, 140, 146)
WATER = (63, 118, 228)
ARROW = (255, 255, 255)
ARROW_EDGE = (16, 18, 21)

# Height and surface of each column, north-west corner first.
HEIGHTS = [
    [3, 3, 2, 2, 1, 0, 0],
    [3, 4, 3, 2, 1, 0, 0],
    [2, 3, 3, 2, 1, 1, 0],
    [2, 2, 2, 2, 2, 1, 0],
    [1, 2, 2, 2, 2, 1, 1],
    [1, 1, 2, 2, 1, 1, 1],
    [0, 1, 1, 1, 1, 1, 1],
]


def surface(height):
    if height == 0:
        return WATER
    if height == 1:
        return SAND
    if height >= 4:
        return STONE
    return GRASS


def shade(color, factor):
    return tuple(min(255, int(c * factor)) for c in color)


def main():
    canvas = SIZE * SCALE
    image = Image.new("RGB", (canvas, canvas), BACKGROUND)
    draw = ImageDraw.Draw(image)

    n = len(HEIGHTS)
    half_w = canvas * 0.0615
    half_h = half_w / 2
    step = half_w * 0.66
    base = 1.2
    center_x = canvas / 2
    top_y = canvas * 0.335

    def point(i, j, level):
        return (center_x + (i - j) * half_w, top_y + (i + j) * half_h - level * step)

    for total in range(2 * n - 1):
        for i in range(n):
            j = total - i
            if not 0 <= j < n:
                continue
            height = HEIGHTS[j][i]
            level = height + base
            color = surface(height)
            west = point(i, j + 1, level)
            south = point(i + 1, j + 1, level)
            east = point(i + 1, j, level)
            north = point(i, j, level)
            floor = 0
            draw.polygon([west, south, point(i + 1, j + 1, floor), point(i, j + 1, floor)], fill=shade(color, 0.72))
            draw.polygon([south, east, point(i + 1, j, floor), point(i + 1, j + 1, floor)], fill=shade(color, 0.55))
            draw.polygon([north, east, south, west], fill=color)

    # The same arrow the map draws for the player, standing over the middle of the terrain.
    tip_x, tip_y = point(3.5, 3.5, HEIGHTS[3][3] + base)
    tip_y -= canvas * 0.075

    def arrow(scale, color):
        draw.polygon([
            (tip_x, tip_y - 0.15 * canvas * scale),
            (tip_x + 0.105 * canvas * scale, tip_y + 0.105 * canvas * scale),
            (tip_x, tip_y + 0.05 * canvas * scale),
            (tip_x - 0.105 * canvas * scale, tip_y + 0.105 * canvas * scale),
        ], fill=color)

    arrow(1.28, ARROW_EDGE)
    arrow(1.0, ARROW)

    image = image.resize((SIZE, SIZE), Image.LANCZOS)
    resources = OUT / "src/main/resources"
    image.save(resources / "isomap_logo.png", optimize=True)
    publish = OUT / "publish"
    publish.mkdir(exist_ok=True)
    image.save(publish / "logo.png", optimize=True)
    print("logo written")


main()
