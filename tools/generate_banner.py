"""Social preview banner for the GitHub repository (1280x640): the mod's logo, its name and one line about it."""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
WIDTH, HEIGHT = 1280, 640
BACKGROUND_TOP = (18, 20, 24)
BACKGROUND_BOTTOM = (30, 34, 41)
TEXT = (233, 235, 238)
TEXT_DIM = (163, 168, 176)
ACCENT = (111, 177, 255)


def font(name: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(Path("C:/Windows/Fonts") / name), size)


def main(out: Path) -> None:
    image = Image.new("RGB", (WIDTH, HEIGHT))
    draw = ImageDraw.Draw(image)
    for y in range(HEIGHT):
        t = y / (HEIGHT - 1)
        draw.line([(0, y), (WIDTH, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(BACKGROUND_TOP, BACKGROUND_BOTTOM)))

    # The logo has its own dark square; rounded and outlined it reads as an app icon instead of a hole in the banner.
    size = 360
    logo = Image.open(ROOT / "publish" / "logo.png").convert("RGBA").resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=56, fill=255)
    left, top = 110, (HEIGHT - size) // 2
    image.paste(logo, (left, top), mask)
    draw.rounded_rectangle([left, top, left + size - 1, top + size - 1], radius=56, outline=(70, 76, 86), width=2)

    x = 530
    draw.text((x, 200), "IsoMap", font=font("segoeuib.ttf", 112), fill=TEXT)
    draw.rectangle([x + 4, 340, x + 124, 346], fill=ACCENT)
    draw.text((x, 372), "2.5D isometric minimap", font=font("segoeui.ttf", 44), fill=TEXT)
    draw.text((x, 432), "Minecraft 1.21.1  ·  NeoForge", font=font("segoeui.ttf", 34), fill=TEXT_DIM)
    image.save(out)


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "publish" / "github_banner.png")
