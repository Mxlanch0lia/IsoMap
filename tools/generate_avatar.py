#!/usr/bin/env python3
"""Original pixel-art avatars for Mxlanch0lia: a pale planet over a dark horizon, and an M monogram.

Usage:
    python -m pip install pillow
    python tools/generate_avatar.py
"""
import math
import random
from pathlib import Path

from PIL import Image

N = 64
OUT = Path(__file__).resolve().parent.parent / "publish/author"
BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]
PLANET = [(38, 58, 104), (58, 86, 140), (88, 124, 174), (134, 170, 208), (190, 216, 236), (232, 243, 250)]

LETTER_M = [
    "11.....11",
    "111...111",
    "1111.1111",
    "11.111.11",
    "11..1..11",
    "11.....11",
    "11.....11",
    "11.....11",
    "11.....11",
]


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def dithered(palette, value, x, y):
    value = max(0.0, min(0.999, value)) * (len(palette) - 1)
    low = int(value)
    return palette[low + 1] if value - low > (BAYER[y % 4][x % 4] + 0.5) / 16 else palette[low]


def sky(image):
    rng = random.Random(7)
    top, bottom = (7, 9, 24), (34, 34, 74)
    palette = [mix(top, bottom, i / 5) for i in range(6)]
    for y in range(N):
        for x in range(N):
            image.putpixel((x, y), dithered(palette, y / (N - 1), x, y))
    for _ in range(34):
        x, y = rng.randrange(N), rng.randrange(44)
        image.putpixel((x, y), rng.choice([(200, 210, 235), (130, 145, 190), (92, 104, 150)]))


def planet(image, cx, cy, r):
    light = (-0.62, -0.5, 0.6)
    for y in range(N):
        for x in range(N):
            dx, dy = (x - cx) / r, (y - cy) / r
            d2 = dx * dx + dy * dy
            d = math.sqrt(d2)
            if d2 <= 1:
                z = math.sqrt(1 - d2)
                lit = max(0.0, dx * light[0] + dy * light[1] + z * light[2])
                # Soft cloud bands crossing the disc at an angle.
                band = 0.07 * math.sin((dy * 0.92 + dx * 0.38) * 11 + 1.2 * math.sin(dx * 5))
                image.putpixel((x, y), dithered(PLANET, 0.06 + lit * 0.98 + band, x, y))
            elif d < 1.2:
                # A thin glow around the disc, stronger on the lit side.
                glow = (1.2 - d) / 0.2 * (0.28 + 0.34 * max(0.0, -dx * 0.6 - dy * 0.5))
                if glow > (BAYER[y % 4][x % 4] + 0.5) / 16:
                    image.putpixel((x, y), mix(image.getpixel((x, y)), (120, 156, 208), 0.5))


def scene():
    image = Image.new("RGB", (N, N))
    sky(image)
    planet(image, 40, 23, 19)
    ground = (6, 7, 15)
    edge = (28, 34, 62)
    heights = []
    for x in range(N):
        h = round(52 + 2.6 * math.sin(x * 0.11 + 0.6) + 1.2 * math.sin(x * 0.31))
        heights.append(h)
        for y in range(h, N):
            image.putpixel((x, y), ground)
        image.putpixel((x, h), edge)
    # A lone figure on the rise, looking up.
    fx = 15
    top = heights[fx] - 7
    for y, (a, b) in enumerate([(0, 1), (0, 1), (-1, 2), (-1, 2), (0, 1), (0, 1), (0, 1)]):
        for x in range(a, b + 1):
            image.putpixel((fx + x, top + y), ground)
    image.putpixel((fx + 1, top), (88, 124, 174))
    image.putpixel((fx + 2, top + 2), (58, 86, 140))
    return image


def monogram():
    image = Image.new("RGB", (N, N))
    sky(image)
    planet(image, 44, 18, 26)
    # The letter, with a dark outline so it stays readable over the planet.
    scale = 5
    width, height = len(LETTER_M[0]) * scale, len(LETTER_M) * scale
    ox, oy = (N - width) // 2, (N - height) // 2 + 3
    cells = {(ox + cx * scale + i, oy + cy * scale + j)
             for cy, row in enumerate(LETTER_M) for cx, c in enumerate(row) if c == "1"
             for i in range(scale) for j in range(scale)}
    for x, y in cells:
        for dx in range(-2, 3):
            for dy in range(-2, 3):
                if 0 <= x + dx < N and 0 <= y + dy < N:
                    image.putpixel((x + dx, y + dy), (7, 9, 24))
    for x, y in cells:
        image.putpixel((x, y), mix((246, 250, 255), (150, 182, 222), (y - oy) / height))
    return image


OUT.mkdir(parents=True, exist_ok=True)
sheet = Image.new("RGB", (1000, 620), (24, 25, 30))
for i, (name, art) in enumerate((("avatar_planet", scene()), ("avatar_monogram", monogram()))):
    big = art.resize((1024, 1024), Image.NEAREST)
    big.save(OUT / f"{name}.png", optimize=True)
    # Both side by side, including the sizes they appear at next to a comment.
    sheet.paste(big.resize((480, 480), Image.NEAREST), (14 + i * 490, 14))
    sheet.paste(big.resize((96, 96), Image.LANCZOS), (14 + i * 490, 508))
    sheet.paste(big.resize((48, 48), Image.LANCZOS), (124 + i * 490, 556))
sheet.save(OUT / "preview.png")
print("done")
