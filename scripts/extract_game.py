#!/usr/bin/env python3
"""Extract an original Xbox disc image into a folder. The image is only read.

  extract_game.py GAME.iso --out assets                 every file
  extract_game.py GAME.iso --out assets --only default.xbe
  extract_game.py GAME.iso --list
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import xdvdfs


def extract(image, out, only=None):
    count = total = 0
    if only:
        entry = image.find(only)
        if entry is None or entry.is_dir:
            raise SystemExit(f"{only} is not a file in the image")
        items = [(only, entry)]
    else:
        items = list(image.walk())
    for rel, entry in items:
        dest = os.path.join(out, *rel.split("/"))
        if entry.is_dir:
            os.makedirs(dest, exist_ok=True)
            continue
        os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
        # written beside it first: a stopped run leaves no short file under the real name
        with open(dest + ".part", "wb") as o:
            for chunk in image.read(entry):
                o.write(chunk)
        os.replace(dest + ".part", dest)
        count += 1
        total += entry.size
    return count, total


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("iso")
    ap.add_argument("--out", help="folder to extract into")
    ap.add_argument("--only", help="one file, by its path in the image")
    ap.add_argument("--list", action="store_true", help="list the files instead")
    args = ap.parse_args()
    with open(args.iso, "rb") as f:
        try:
            image = xdvdfs.Image(f)
        except xdvdfs.NotXdvdfs as e:
            raise SystemExit(str(e))
        if args.list:
            for rel, entry in image.walk():
                print(f"{'<dir>' if entry.is_dir else entry.size:>12} {rel}")
            return
        if not args.out:
            ap.error("--out or --list")
        count, total = extract(image, args.out, args.only)
        print(f"{count} files, {total / (1 << 30):.2f} GiB -> {args.out}")


if __name__ == "__main__":
    main()
