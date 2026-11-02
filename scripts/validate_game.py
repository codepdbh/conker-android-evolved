#!/usr/bin/env python3
"""Identify a copy of the game: a disc image, or default.xbe, or a folder with it. Reads only, never changes the copy.

Exit code: 0 a release the upstream config was made from, 3 not one of them (the report says how it differs), 2 unusable.

  validate_game.py GAME.iso
  validate_game.py assets/
  validate_game.py assets/default.xbe --json
"""
import argparse
import hashlib
import json
import os
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import xdvdfs

# the releases src/conker/game.toml was made from and tested with (README.md, CMakeLists.txt)
SUPPORTED = {
    "2232a1b3874d67b4bb4cf53a940420d2dab59535": "USA disc",
    "68a37f0d03d73dabc86a848df5ec1820dd551254": "Microsoft Store (the disc's code and data, another header)",
}
EXPECTED_TITLE_ID = 0x4D530051
EXPECTED_ENTRY = 0x42F0E9
XOR_ENTRY_RETAIL = 0xA8FC57AB
XOR_ENTRY_DEBUG = 0x94859D4B
SECTION_HEADER = 56


class BadXbe(ValueError):
    pass


def parse_xbe(d):
    if d[:4] != b"XBEH" or len(d) < 0x180:
        raise BadXbe("not an XBE: no XBEH magic")

    def u32(o):
        if o < 0 or o + 4 > len(d):
            raise BadXbe(f"the XBE is cut short, an offset points at {o:#x}")
        return struct.unpack_from("<I", d, o)[0]

    base = u32(0x104)
    cert = u32(0x118) - base
    sections = u32(0x120) - base
    entry_xored = u32(0x128)
    info = {
        "base": base,
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime(u32(0x114))),
        "init_flags": u32(0x124),
        "entry_retail": entry_xored ^ XOR_ENTRY_RETAIL,
        "entry_debug": entry_xored ^ XOR_ENTRY_DEBUG,
        "cert_timestamp": time.strftime("%Y-%m-%d", time.gmtime(u32(cert + 4))),
        "title_id": u32(cert + 8),
        "title": d[cert + 12 : cert + 92].decode("utf-16-le", "replace").split("\0")[0],
        "allowed_media": u32(cert + 0x9C),
        "region": u32(cert + 0xA0),
        "ratings": u32(cert + 0xA4),
        "disc": u32(cert + 0xA8),
        "version": u32(cert + 0xAC),
        "sections": [],
    }
    for i in range(u32(0x11C)):
        o = sections + i * SECTION_HEADER
        raw, raw_size = u32(o + 12), u32(o + 16)
        name_at = u32(o + 20) - base
        digest = d[o + 0x24 : o + 0x38]
        body = d[raw : raw + raw_size]
        if len(body) != raw_size:
            raise BadXbe(f"section {i} runs past the end of the file")
        info["sections"].append({
            "name": d[name_at : name_at + 32].split(b"\0")[0].decode("latin-1"),
            "va": u32(o + 4),
            "virtual_size": u32(o + 8),
            "raw": raw,
            "raw_size": raw_size,
            # each section's own SHA-1 in the header: the size in front of the bytes
            "digest_ok": hashlib.sha1(struct.pack("<I", raw_size) + body).digest() == digest,
        })
    return info


def read_xbe(path):
    """The XBE's bytes and a description of where they came from."""
    if os.path.isdir(path):
        path = os.path.join(path, "default.xbe")
    with open(path, "rb") as f:
        if f.read(4) == b"XBEH":
            f.seek(0)
            return f.read(), "xbe"
        f.seek(0)
        image = xdvdfs.Image(f)
        entry = image.find("default.xbe")
        if entry is None:
            raise BadXbe("the disc image has no default.xbe")
        return b"".join(image.read(entry)), "xdvdfs image"


def judge(info, sha):
    """The verdict and each reason. Only an exact SHA-1 is 'supported': the rest is evidence, not an equivalence."""
    notes = []
    if info["title_id"] != EXPECTED_TITLE_ID:
        notes.append(f"title id {info['title_id']:#010x}, not Conker's {EXPECTED_TITLE_ID:#010x}: another game")
        return "wrong-game", notes
    if sha in SUPPORTED:
        return "supported", [SUPPORTED[sha]]
    notes.append("the SHA-1 is not a supported release's")
    if info["entry_retail"] == EXPECTED_ENTRY:
        notes.append(f"its entry point is the config's ({EXPECTED_ENTRY:#x})")
    else:
        notes.append(f"its entry point {info['entry_retail']:#x} is not the config's ({EXPECTED_ENTRY:#x}): another build")
        return "different-build", notes
    bad = [s["name"] for s in info["sections"] if not s["digest_ok"]]
    if bad:
        notes.append("sections that do not match the digests in the header (altered after signing): " + ", ".join(bad))
        return "altered", notes
    notes.append("every section matches its digest in the header")
    notes.append("run scripts/compare_config.py to check the config's addresses against this code")
    return "unverified-variant", notes


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("path")
    ap.add_argument("--json", action="store_true")
    args = ap.parse_args()
    try:
        data, source = read_xbe(args.path)
        info = parse_xbe(data)
    except (OSError, BadXbe, xdvdfs.NotXdvdfs) as e:
        print(f"unusable: {e}", file=sys.stderr)
        return 2
    sha = hashlib.sha1(data).hexdigest()
    verdict, notes = judge(info, sha)
    if args.json:
        print(json.dumps({"verdict": verdict, "sha1": sha, "size": len(data), "source": source, "notes": notes, **info}, indent=1))
    else:
        print(f"source     {source}, default.xbe {len(data)} bytes")
        print(f"sha1       {sha}")
        print(f"title      {info['title']} ({info['title_id']:#010x}), version {info['version']}, disc {info['disc']}")
        print(f"dates      linked {info['timestamp']}, certified {info['cert_timestamp']}")
        print(f"region     {info['region']:#x}, allowed media {info['allowed_media']:#x}, ratings {info['ratings']:#x}")
        print(f"entry      {info['entry_retail']:#x} (retail key), {len(info['sections'])} sections")
        print(f"verdict    {verdict}")
        for n in notes:
            print(f"           - {n}")
    return 0 if verdict == "supported" else 3


if __name__ == "__main__":
    sys.exit(main())
