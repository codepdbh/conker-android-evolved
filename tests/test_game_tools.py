#!/usr/bin/env python3
"""Tests of the game-ingest scripts on synthetic fixtures made here: no game data. Run: python tests/test_game_tools.py"""
import hashlib
import io
import os
import struct
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "scripts"))
import extract_game
import validate_game
import xdvdfs

SECTOR = 2048


def dirent(left, right, sector, size, name, is_dir=False):
    raw = struct.pack("<HHIIBB", left, right, sector, size, 0x10 if is_dir else 0x20, len(name)) + name.encode()
    return raw + b"\xff" * (-len(raw) % 4)


def make_iso(partition=0):
    """root: a.bin, sub/ (b.bin); the entries in a binary tree, as a real table has them."""
    a, b = b"A" * 3000, b"B" * 10
    img = bytearray(partition + 0x10000 + SECTOR * 40)
    desc = xdvdfs.MAGIC + struct.pack("<II", 33, SECTOR)
    img[partition + 0x10000 : partition + 0x10000 + len(desc)] = desc
    first = dirent(0, 5, 35, len(a), "a.bin")  # right child at dword 5
    assert len(first) == 20
    root = first + dirent(0, 0, 34, SECTOR, "sub", is_dir=True)
    img[partition + 33 * SECTOR : partition + 33 * SECTOR + len(root)] = root
    sub = dirent(0, 0, 37, len(b), "b.bin")
    img[partition + 34 * SECTOR : partition + 34 * SECTOR + len(sub)] = sub
    img[partition + 35 * SECTOR : partition + 35 * SECTOR + len(a)] = a
    img[partition + 37 * SECTOR : partition + 37 * SECTOR + len(b)] = b
    return bytes(img)


def make_xbe(title_id=validate_game.EXPECTED_TITLE_ID, entry=validate_game.EXPECTED_ENTRY, corrupt=False):
    base = 0x10000
    cert, sect, names, raw = 0x200, 0x400, 0x500, 0x1000
    body = b"synthetic section, not game data"
    d = bytearray(raw + len(body))
    d[0:4] = b"XBEH"
    for off, val in ((0x104, base), (0x114, 0x42000000), (0x118, base + cert), (0x11C, 1), (0x120, base + sect),
                     (0x124, 0), (0x128, entry ^ validate_game.XOR_ENTRY_RETAIL), (cert + 4, 0x42000000),
                     (cert + 8, title_id), (cert + 0xA0, 1), (cert + 0xAC, 1)):
        struct.pack_into("<I", d, off, val)
    title = "Fixture".encode("utf-16-le")
    d[cert + 12 : cert + 12 + len(title)] = title
    for off, val in ((4, 0x11000), (8, len(body)), (12, raw), (16, len(body)), (20, base + names)):
        struct.pack_into("<I", d, sect + off, val)
    d[names : names + 5] = b".text"
    digest = hashlib.sha1(struct.pack("<I", len(body)) + body).digest()
    d[sect + 0x24 : sect + 0x38] = digest
    d[raw : raw + len(body)] = body
    if corrupt:
        d[raw] ^= 0xFF
    return bytes(d)


class Xdvdfs(unittest.TestCase):
    def test_every_layout(self):
        for partition in (0, 0x2080000):
            image = xdvdfs.Image(io.BytesIO(make_iso(partition)))
            self.assertEqual(image.base, partition)
            self.assertEqual([p for p, _ in image.walk()], ["a.bin", "sub", "sub/b.bin"])
            self.assertEqual(b"".join(image.read(image.find("SUB/B.BIN"))), b"B" * 10)

    def test_not_an_image(self):
        with self.assertRaises(xdvdfs.NotXdvdfs):
            xdvdfs.Image(io.BytesIO(b"\0" * 0x20000))

    def test_refuses_path_escape(self):
        img = bytearray(make_iso())
        evil = dirent(0, 0, 35, 1, "..")
        img[33 * SECTOR : 33 * SECTOR + len(evil)] = evil
        with self.assertRaises(xdvdfs.NotXdvdfs):
            xdvdfs.Image(io.BytesIO(bytes(img)))

    def test_extract(self):
        with tempfile.TemporaryDirectory() as out:
            count, total = extract_game.extract(xdvdfs.Image(io.BytesIO(make_iso())), out)
            self.assertEqual((count, total), (2, 3010))
            with open(os.path.join(out, "sub", "b.bin"), "rb") as f:
                self.assertEqual(f.read(), b"B" * 10)


class Validate(unittest.TestCase):
    def verdict(self, data):
        return validate_game.judge(validate_game.parse_xbe(data), hashlib.sha1(data).hexdigest())[0]

    def test_variant_is_never_supported(self):
        self.assertEqual(self.verdict(make_xbe()), "unverified-variant")

    def test_other_game(self):
        self.assertEqual(self.verdict(make_xbe(title_id=0x12345678)), "wrong-game")

    def test_other_build(self):
        self.assertEqual(self.verdict(make_xbe(entry=0x401000)), "different-build")

    def test_altered_sections(self):
        self.assertEqual(self.verdict(make_xbe(corrupt=True)), "altered")

    def test_supported_needs_the_exact_sha(self):
        info = validate_game.parse_xbe(make_xbe())
        self.assertEqual(validate_game.judge(info, next(iter(validate_game.SUPPORTED)))[0], "supported")

    def test_garbage(self):
        with self.assertRaises(validate_game.BadXbe):
            validate_game.parse_xbe(b"nope" * 100)

    def test_truncated(self):
        with self.assertRaises(validate_game.BadXbe):
            validate_game.parse_xbe(make_xbe()[:0x210])


if __name__ == "__main__":
    unittest.main()
