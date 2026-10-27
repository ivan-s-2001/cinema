"""Fetch the pinned, unmodified TorrServer binaries and verify their SHA256."""
import hashlib
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1] / "android" / "arvio"
VERSION = "MatriX.147.1"
ASSETS = {
    "arm64-v8a": ("TorrServer-android-arm64", "93250bb1850e69663c6758398be515c2c429595cb4692c6eccb367f2236868d5"),
    "armeabi-v7a": ("TorrServer-android-arm7", "e775001edf7bfb2adebfde0ba36780357c0951f12203adb367bd69b2b45522b6"),
}

for abi, (name, expected) in ASSETS.items():
    destination = ROOT / "app/src/main/jniLibs" / abi / "libtorrserver.so"
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        with destination.open("rb") as existing:
            correct = hashlib.file_digest(existing, "sha256").hexdigest() == expected
        if correct:
            print(f"{abi}: checksum verified")
            continue
    temporary = destination.with_suffix(".download")
    try:
        request = urllib.request.Request(f"https://github.com/YouROK/TorrServer/releases/download/{VERSION}/{name}", headers={"User-Agent": "CinemaHall-build"})
        with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as output:
            digest = hashlib.sha256()
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
                digest.update(chunk)
        if digest.hexdigest() != expected:
            raise RuntimeError(f"SHA256 mismatch for {name}; refusing to package the binary")
        temporary.replace(destination)
        print(f"{abi}: downloaded and verified")
    finally:
        temporary.unlink(missing_ok=True)
