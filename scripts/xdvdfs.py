"""Read-only reader for XDVDFS, the original Xbox's disc file system (not ISO 9660).

An image may be the game partition alone (a "xiso", its volume descriptor at 0x10000) or a full disc rip with the
video partition in front of the game's. Nothing here writes to the image.
"""
import struct
from dataclasses import dataclass, field

SECTOR = 2048
MAGIC = b"MICROSOFT*XBOX*MEDIA"
# where the game partition starts in the disc layouts seen in the wild: xiso, XGD1 (the original Xbox's discs), XGD2, XGD3
PARTITION_OFFSETS = (0, 0x18300000, 0xFD90000, 0x2080000)
ATTR_DIRECTORY = 0x10


class NotXdvdfs(ValueError):
    pass


@dataclass
class Entry:
    name: str
    sector: int
    size: int
    is_dir: bool
    children: list = field(default_factory=list)


class Image:
    def __init__(self, stream):
        self.f = stream
        self.base = self._find_partition()
        self.f.seek(self.base + 0x10000 + len(MAGIC))
        root_sector, root_size = struct.unpack("<II", self.f.read(8))
        self.root = Entry("", root_sector, root_size, True)
        self._load(self.root, set())

    def _find_partition(self):
        for off in PARTITION_OFFSETS:
            self.f.seek(off + 0x10000)
            if self.f.read(len(MAGIC)) == MAGIC:
                return off
        raise NotXdvdfs("no XDVDFS volume descriptor: this is not an original Xbox disc image")

    def _load(self, directory, seen):
        if directory.size == 0:
            return
        if directory.sector in seen:
            raise NotXdvdfs(f"directory loop at sector {directory.sector}")
        seen.add(directory.sector)
        self.f.seek(self.base + directory.sector * SECTOR)
        table = self.f.read(directory.size)
        if len(table) != directory.size:
            raise NotXdvdfs("the image ends inside a directory table")
        self._walk(table, 0, directory, seen, set())
        directory.children.sort(key=lambda e: e.name.lower())

    def _walk(self, table, offset_dwords, directory, seen, visited):
        off = offset_dwords * 4
        if offset_dwords in visited or off + 14 > len(table):
            return
        visited.add(offset_dwords)
        left, right, sector, size, attrs, name_len = struct.unpack_from("<HHIIBB", table, off)
        if left == 0xFFFF and right == 0xFFFF:  # the padding of an empty table
            return
        name = table[off + 14 : off + 14 + name_len].decode("latin-1")
        if not name or "/" in name or "\\" in name or name in (".", ".."):
            raise NotXdvdfs(f"unsafe file name in the image: {name!r}")
        entry = Entry(name, sector, size, bool(attrs & ATTR_DIRECTORY))
        if left:
            self._walk(table, left, directory, seen, visited)
        directory.children.append(entry)
        if entry.is_dir:
            self._load(entry, seen)
        if right:
            self._walk(table, right, directory, seen, visited)

    def find(self, path):
        entry = self.root
        for part in path.replace("\\", "/").split("/"):
            match = [c for c in entry.children if c.name.lower() == part.lower()]
            if not match:
                return None
            entry = match[0]
        return entry

    def read(self, entry, chunk=4 << 20):
        """The file's bytes, a chunk at a time."""
        self.f.seek(self.base + entry.sector * SECTOR)
        left = entry.size
        while left:
            data = self.f.read(min(chunk, left))
            if not data:
                raise NotXdvdfs(f"the image ends inside {entry.name}")
            left -= len(data)
            yield data

    def walk(self, entry=None, prefix=""):
        """Every file and directory below entry, as (relative path, Entry)."""
        for child in (entry or self.root).children:
            path = f"{prefix}{child.name}"
            yield path, child
            if child.is_dir:
                yield from self.walk(child, path + "/")
