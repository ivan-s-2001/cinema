"""Validate vendored sources and configure their SDK without changing application code."""
from pathlib import Path
import argparse
import os
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]
TARGETS = {"tv": "nuvio-tv", "mobile": "nuvio-mobile"}

def ensure_sdk_properties(directory):
    """Repair a missing or stale sdk.dir without disturbing other local settings."""
    properties = directory / "local.properties"
    current = properties.read_text(encoding="utf-8") if properties.exists() else ""
    sdk_line = next((line.partition("=")[2].strip() for line in current.splitlines()
                     if line.strip().startswith("sdk.dir=")), "")
    local = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    candidates = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        local / "CinemaHall" / "toolchain" / "android-sdk",
        local / "Android" / "Sdk",
        sdk_line.replace("\\\\", "\\") if sdk_line else None,
    ]
    for candidate in candidates:
        if not candidate:
            continue
        sdk = Path(candidate).expanduser()
        if (sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")).is_file():
            value = sdk.resolve().as_posix()
            lines = [line for line in current.splitlines()
                     if not line.strip().startswith("sdk.dir=")]
            properties.write_text("\n".join(lines + ["sdk.dir=" + value]) + "\n", encoding="utf-8")
            print(f"Android SDK: {sdk}")
            return
    raise RuntimeError(f"Android SDK not found: check ANDROID_HOME or sdk.dir in {properties}")


def prepare(target):
    name = TARGETS[target]
    directory = ROOT / "android" / name
    module = "app" if target == "tv" else "androidApp"
    if not (directory / "gradlew").is_file():
        raise RuntimeError(f"{name}: vendored sources are missing")
    ET.parse(directory / module / "src/main/AndroidManifest.xml")
    ensure_sdk_properties(directory)
    print(f"{name}: using vendored sources")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", choices=["tv", "mobile", "both"], default="both")
    args = parser.parse_args()
    for target in TARGETS if args.target == "both" else [args.target]:
        prepare(target)
