#!/usr/bin/env python3
"""Make the app's icon and the launcher's logo from a square, round badge. Needs Pillow.

  make_icon.py icono.png
"""
import argparse
from pathlib import Path

from PIL import Image, ImageDraw

RES = Path(__file__).resolve().parent.parent / "src/android/app/src/main/res/drawable-nodpi"
ICON_SIZE = 432  # 108 dp at the largest density
BADGE_SHARE = 0.68  # a launcher's mask shows the middle 72 of 108 dp: the badge just past it, so a round mask leaves no gap
LOGO_SIZE = 512
SUPERSAMPLE = 4


def badge(source, size):
    """The badge at size, cut to its circle: what the source has outside it is leftovers."""
    picture = source.resize((size, size), Image.LANCZOS)
    big = size * SUPERSAMPLE
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, big - 1, big - 1), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    alpha = Image.composite(picture.getchannel("A"), Image.new("L", (size, size), 0), mask)
    picture.putalpha(alpha)
    return picture


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    source = Image.open(parser.parse_args().source).convert("RGBA")
    RES.mkdir(parents=True, exist_ok=True)

    side = round(ICON_SIZE * BADGE_SHARE)
    icon = Image.new("RGBA", (ICON_SIZE, ICON_SIZE), (0, 0, 0, 0))
    at = (ICON_SIZE - side) // 2
    icon.alpha_composite(badge(source, side), (at, at))
    icon.save(RES / "ic_launcher_foreground.png", optimize=True)
    badge(source, LOGO_SIZE).save(RES / "logo.png", optimize=True)


if __name__ == "__main__":
    main()
