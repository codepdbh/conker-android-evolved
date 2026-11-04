#!/usr/bin/env python3
"""What the public APK brings so the phone can compile the game itself: no game code, only tools. Needs the Android kit built
(the Game_android_kit and xbe_recomp_device targets of out/build/zig-android).

  out/build/zig-android/devkit/native/arm64-v8a/  libzig.so, libxbe_recomp.so: programs, where Android lets an app run them
  out/build/zig-android/devkit/assets/devkit.zip  the kit and Zig's library, unpacked on the phone

Zig for ARM64 Linux is downloaded once, checked against the SHA-256 ziglang.org publishes.
"""
import hashlib
import shutil
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUILD = ROOT / "out/build/zig-android"
KIT = BUILD / "package/Game-android/kit"
OUT = BUILD / "devkit"
ZIG_VERSION = "0.16.0"
ZIG_NAME = f"zig-aarch64-linux-{ZIG_VERSION}"
ZIG_URL = f"https://ziglang.org/download/{ZIG_VERSION}/{ZIG_NAME}.tar.xz"
ZIG_SHA256 = "ea4b09bfb22ec6f6c6ceac57ab63efb6b46e17ab08d21f69f3a48b38e1534f17"
ZIG_ROOT = ROOT / "toolchain" / ZIG_NAME
KIT_PARTS = ["android.toml", "kit.toml", "config", "xdk", "include", "lib", "sysroot"]
# the C libraries of other systems: Android's comes from the sysroot (ZIG_LIBC)
ZIG_LIB_LEFT_OUT = ["libc", "tsan", "docs", "init", "fuzzer"]


def fetch_zig():
    if (ZIG_ROOT / "zig").is_file():
        return
    ZIG_ROOT.parent.mkdir(parents=True, exist_ok=True)
    archive = ZIG_ROOT.parent / (ZIG_NAME + ".tar.xz")
    if not archive.is_file():
        print("downloading", ZIG_URL)
        urllib.request.urlretrieve(ZIG_URL, archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != ZIG_SHA256:
        archive.unlink()
        sys.exit(f"{ZIG_NAME}: the download doesn't match its SHA-256")
    with tarfile.open(archive) as t:
        t.extractall(ZIG_ROOT.parent, filter="data")


def main():
    recomp = BUILD / "xbe_recomp_device"
    for needed in [recomp, KIT / "android.toml"]:
        if not needed.exists():
            sys.exit(f"no {needed}: build the Game_android_kit and xbe_recomp_device targets first")
    fetch_zig()
    shutil.rmtree(OUT, ignore_errors=True)
    native = OUT / "native/arm64-v8a"
    native.mkdir(parents=True)
    shutil.copy(ZIG_ROOT / "zig", native / "libzig.so")
    shutil.copy(recomp, native / "libxbe_recomp.so")
    assets = OUT / "assets"
    assets.mkdir()
    with zipfile.ZipFile(assets / "devkit.zip", "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for part in KIT_PARTS:
            path = KIT / part
            for f in [path] if path.is_file() else sorted(p for p in path.rglob("*") if p.is_file()):
                z.write(f, f.relative_to(KIT).as_posix())
        z.write(ZIG_ROOT / "LICENSE", "zig-LICENSE")
        for f in sorted(p for p in (ZIG_ROOT / "lib").rglob("*") if p.is_file()):
            rel = f.relative_to(ZIG_ROOT / "lib")
            if rel.parts[0] not in ZIG_LIB_LEFT_OUT:
                z.write(f, ("zig-lib" / rel).as_posix())
    for f in [native / "libzig.so", native / "libxbe_recomp.so", assets / "devkit.zip"]:
        print(f"{f.name}: {f.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
