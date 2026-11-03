#!/usr/bin/env python3
"""Adapts the author's existing logo (publish/author/source_logo.jpg) for use as a profile picture on mod sites.

The emblem itself is kept: it is lifted from the source as a mask and redrawn crisply at a higher resolution.

Usage:
    python -m pip install pillow numpy
    python tools/adapt_author_logo.py
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

DIR = Path(__file__).resolve().parent.parent / "publish/author"
SIZE = 1024
UPSCALE = 4
RED_CENTER = np.array([246, 30, 16], dtype=np.float32)
RED_EDGE = np.array([206, 14, 8], dtype=np.float32)
BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


def smoothstep(a, b, x):
    t = np.clip((x - a) / (b - a), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def crisp_mask(source):
    """The emblem is the only near-white thing: red and its pattern have a very low green channel."""
    green = Image.fromarray(np.asarray(source)[:, :, 1])
    big = green.resize((source.width * UPSCALE, source.height * UPSCALE), Image.LANCZOS)
    big = big.filter(ImageFilter.GaussianBlur(UPSCALE * 0.6))
    return smoothstep(118.0, 138.0, np.asarray(big, dtype=np.float32))


def emblem_box(mask):
    ys, xs = np.where(mask > 0.5)
    return xs.min(), ys.min(), xs.max() + 1, ys.max() + 1


def place(mask, width_share, size):
    """Crops the mask to the emblem and centres it at the given share of the picture's width."""
    x0, y0, x1, y1 = emblem_box(mask)
    emblem = Image.fromarray((mask[y0:y1, x0:x1] * 255).astype(np.uint8))
    width = int(size * width_share)
    height = int(width * (y1 - y0) / (x1 - x0))
    emblem = emblem.resize((width, height), Image.LANCZOS)
    canvas = Image.new("L", (size, size), 0)
    canvas.paste(emblem, ((size - width) // 2, (size - height) // 2))
    return np.asarray(canvas, dtype=np.float32) / 255.0


def red_field(size):
    ys, xs = np.mgrid[0:size, 0:size].astype(np.float32)
    d = np.hypot(xs - size / 2, ys - size / 2) / (size * 0.72)
    t = np.clip(d, 0, 1)[..., None]
    return RED_CENTER * (1 - t) + RED_EDGE * t


def compose(background, alpha):
    white = np.full_like(background, 247.0)
    out = background * (1 - alpha[..., None]) + white * alpha[..., None]
    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8))


def with_pattern(source, mask):
    """The original composition, zoomed so that the emblem is larger, with the emblem redrawn on top."""
    x0, y0, x1, y1 = emblem_box(mask)
    array = np.asarray(source, dtype=np.float32)
    # Erase the old soft emblem from the background using the surrounding red.
    small = Image.fromarray((mask * 255).astype(np.uint8)).resize(source.size, Image.BILINEAR)
    covered = np.asarray(small.filter(ImageFilter.MaxFilter(9)), dtype=np.float32) / 255.0
    weight = 1.0 - (covered > 0.02)
    blurred = np.zeros_like(array)
    norm = np.asarray(Image.fromarray((weight * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(14)), dtype=np.float32) / 255.0
    for channel in range(3):
        layer = Image.fromarray(np.clip(array[:, :, channel] * weight, 0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(14))
        blurred[:, :, channel] = np.asarray(layer, dtype=np.float32) / np.maximum(norm, 0.01)
    clean = array * weight[..., None] + blurred * (1 - weight[..., None])

    # Crop around the emblem: its share of the width grows, and the pattern is simply larger.
    cx, cy = (x0 + x1) / 2 / UPSCALE, (y0 + y1) / 2 / UPSCALE
    side = (x1 - x0) / UPSCALE / 0.80
    box = (cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2)
    background = Image.fromarray(np.clip(clean, 0, 255).astype(np.uint8)).resize((SIZE, SIZE), Image.LANCZOS, box=box)
    return compose(np.asarray(background, dtype=np.float32), place(mask, 0.80, SIZE))


def pixel(mask):
    """A Minecraft-styled version: the emblem on a 64x64 grid over dithered red."""
    n = 64
    alpha = place(mask, 0.90, n * 8)
    small = np.asarray(Image.fromarray((alpha * 255).astype(np.uint8)).resize((n, n), Image.BOX), dtype=np.float32) / 255.0
    field = red_field(n)
    out = np.zeros((n, n, 3), dtype=np.uint8)
    for y in range(n):
        for x in range(n):
            d = float(np.hypot(x - n / 2, y - n / 2) / (n * 0.72))
            shade = 0.9 if d > (BAYER[y % 4][x % 4] + 0.5) / 16 else 1.0
            out[y, x] = (247, 247, 247) if small[y, x] > 0.42 else np.clip(field[y, x] * shade, 0, 255)
    return Image.fromarray(out).resize((SIZE, SIZE), Image.NEAREST)


def main():
    source = Image.open(DIR / "source_logo.jpg").convert("RGB")
    mask = crisp_mask(source)
    print("emblem box in the upscaled source:", emblem_box(mask))
    arts = {
        "logo_clean": compose(red_field(SIZE), place(mask, 0.82, SIZE)),
        "logo_pattern": with_pattern(source, mask),
        "logo_pixel": pixel(mask),
    }
    sheet = Image.new("RGB", (1110, 500), (24, 25, 30))
    for i, (name, art) in enumerate(arts.items()):
        art.save(DIR / f"{name}.png", optimize=True)
        sheet.paste(art.resize((350, 350), Image.LANCZOS), (15 + i * 365, 15))
        sheet.paste(art.resize((96, 96), Image.LANCZOS), (15 + i * 365, 380))
        sheet.paste(art.resize((48, 48), Image.LANCZOS), (125 + i * 365, 428))
    sheet.save(DIR / "preview_logo.png")


main()
