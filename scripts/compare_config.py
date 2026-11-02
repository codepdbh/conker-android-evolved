#!/usr/bin/env python3
"""Check a default.xbe against src/conker/game.toml: do the config's addresses point at what the config says is there?

An XBE whose SHA-1 is not a supported release's may still be the same build under another header. This gathers the
evidence; it does not decide. Reads only. Run xbe_symbols first and pass its output with --symbols to compare the
library functions too.

  compare_config.py assets/default.xbe --config src/conker/game.toml --symbols out/compare/game.toml
"""
import argparse
import re
import struct
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import validate_game


class Image:
    def __init__(self, data):
        self.d = data
        self.info = validate_game.parse_xbe(data)

    def section(self, va):
        for s in self.info["sections"]:
            if s["va"] <= va < s["va"] + s["raw_size"]:
                return s
        return None

    def bytes(self, va, n):
        s = self.section(va)
        if not s:
            return None
        off = s["raw"] + va - s["va"]
        return self.d[off : off + n]

    def u32(self, va):
        b = self.bytes(va, 4)
        return struct.unpack("<I", b)[0] if b and len(b) == 4 else None


def symbols(path):
    out = {}
    in_symbols = False
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line.startswith("["):
                in_symbols = line == "[symbols]"
            elif in_symbols and "=" in line and not line.startswith("#"):
                name, value = line.split("=", 1)
                value = value.split("#")[0].strip()
                if re.fullmatch(r"0x[0-9A-Fa-f]+|0", value):
                    out[name.strip()] = int(value, 16)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("xbe")
    ap.add_argument("--config", default="src/conker/game.toml")
    ap.add_argument("--symbols")
    args = ap.parse_args()
    with open(args.xbe, "rb") as f:
        img = Image(f.read())
    text = open(args.config, encoding="utf-8").read()
    failures = 0

    def check(name, ok, detail=""):
        nonlocal failures
        failures += 0 if ok else 1
        print(f"{'ok  ' if ok else 'FAIL'} {name}{': ' + detail if detail else ''}")

    code = re.search(r"code_sections\s*=\s*\[(.*?)\]", text, re.S).group(1)
    names = re.findall(r'"([^"]+)"', code)
    present = [s["name"] for s in img.info["sections"]]
    check("code sections of the config exist", all(n in present for n in names), ", ".join(n for n in names if n not in present))
    entry = int(re.search(r"entry\s*=\s*(0x[0-9A-Fa-f]+)", text).group(1), 16)
    check("entry point", img.info["entry_retail"] == entry, f"{img.info['entry_retail']:#x}")

    # every address the config names lies in the image
    want = symbols(args.config)
    outside = [n for n, v in want.items() if v and not img.section(v)]
    check(f"{sum(1 for v in want.values() if v)} config symbols lie in a section", not outside, ", ".join(outside[:8]))

    if args.symbols:
        found = symbols(args.symbols)
        named = {n: v for n, v in found.items() if v}
        same = [n for n, v in named.items() if want.get(n) == v]
        differ = [f"{n} {want.get(n, 0):#x} vs {v:#x}" for n, v in named.items() if n in want and want[n] and want[n] != v]
        print(f"     xbe_symbols found {len(named)} functions by their bytes, {len(same)} where the config has them")
        check("no function the scan found is elsewhere", not differ, "; ".join(differ[:8]))
        by_hand = [n for n, v in want.items() if v and not found.get(n)]
        print(f"     {len(by_hand)} config symbols are by hand or unfound by the scan: checked below or at recompile")

    # the claims the config's comments make about specific bytes
    b = img.bytes(0x1E6590 - 1, 1)
    check("0x1E6590 follows int3 padding (config: 'after int3 padding')", b == b"\xcc", b.hex() if b else "")
    b = img.bytes(0x53A600, 0x200) or b""
    check("D3DDevice_DrawVerticesUP 0x53A600 writes NV2A 0x17FC and 0x1818",
          struct.pack("<H", 0x17FC) in b and struct.pack("<H", 0x1818) in b)
    b = img.bytes(0x53A7B0, 0x200) or b""
    check("D3DDevice_DrawIndexedVerticesUP 0x53A7B0 writes NV2A 0x1818", struct.pack("<H", 0x1818) in b)
    b = img.bytes(0x4C5A03, 5) or b""
    target = 0x4C5A03 + 5 + struct.unpack("<i", b[1:5])[0] if len(b) == 5 and b[0] == 0xE9 else None
    check("0x4C5A03 jmps to 0x4C5907 (config comment)", target == 0x4C5907, hex(target) if target else b.hex())

    # the first bytes of every hook address in the config (at = ...): each must be in a code section
    ats = [int(x, 16) for x in re.findall(r"^\s*at\s*=\s*(0x[0-9A-Fa-f]+)", text, re.M)]
    bad = [hex(a) for a in ats if not (img.section(a) and img.section(a)["name"] in names)]
    check(f"{len(ats)} patch sites (at = ...) lie in code", not bad, ", ".join(bad[:8]))

    print(f"\n{'nothing contradicts the config' if not failures else str(failures) + ' checks failed'}. "
          "This is evidence about layout, not proof of byte equality with a supported release.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
