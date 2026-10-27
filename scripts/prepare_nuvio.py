"""Apply the checked-in integration to pinned upstreams; never reset user's local edits."""
from pathlib import Path
import argparse
import os
import subprocess
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]
TARGETS = {"tv": ("nuvio-tv", "c3f68d84a5e0ec8fea7a027443a9beca50d32c31"),
           "mobile": ("nuvio-mobile", "61658f366f985fc7758f0942a14c1754c69aca68")}

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
            properties.write_text("\\n".join(lines + ["sdk.dir=" + value]) + "\\n", encoding="utf-8")
            print(f"Android SDK: {sdk}")
            return
    raise RuntimeError(f"Android SDK not found: check ANDROID_HOME or sdk.dir in {properties}")


def prepare(target):
    name, expected = TARGETS[target]
    directory = ROOT / "android" / name
    # Vendored source trees are already integrated; legacy patches target submodules only.
    if not (ROOT / ".gitmodules").exists():
        module = "app" if target == "tv" else "androidApp"
        if not (directory / "gradlew").is_file():
            raise RuntimeError(f"{name}: vendored sources are missing")
        ET.parse(directory / module / "src/main/AndroidManifest.xml")
        ensure_sdk_properties(directory)
        print(f"{name}: using vendored sources")
        return
    if not (directory / ".git").exists():
        subprocess.run(["git", "-C", str(ROOT), "submodule", "update", "--init", "--depth", "1", "--", f"android/{name}"], check=True)
    revision = subprocess.check_output(["git", "-C", str(directory), "rev-parse", "HEAD"], text=True).strip()
    if revision != expected:
        raise RuntimeError(f"{name}: expected {expected}, got {revision}; update the submodule checkout first")
    patches = [ROOT / "android" / f"{name}.patch"]
    local_patch = ROOT / "android" / f"{name}-local.patch"
    if local_patch.exists():
        patches.append(local_patch)
    build_patch = ROOT / "android" / f"{name}-build.patch"
    if build_patch.exists():
        patches.append(build_patch)
    artwork_patch = ROOT / "android" / f"{name}-artwork.patch"
    if artwork_patch.exists():
        patches.append(artwork_patch)
    for patch in patches:
        def can_apply(*options):
            return subprocess.run(["git", "-C", str(directory), "apply", *options, str(patch)], capture_output=True).returncode == 0
        if can_apply("--reverse", "--check"):
            print(f"{name}: integration already applied")
        elif can_apply("--check"):
            subprocess.run(["git", "-C", str(directory), "apply", str(patch)], check=True)
            print(f"{name}: integration applied")
        else:
            raise RuntimeError(f"{name}: patch does not match; local edits were preserved")
    # Mobile runtime config declares this as an input even when no service keys are configured.
    (directory / "local.properties").touch(exist_ok=True)
    module = "app" if target == "tv" else "androidApp"
    ET.parse(directory / module / "src/main/AndroidManifest.xml")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", choices=["tv", "mobile", "both"], default="both")
    args = parser.parse_args()
    for target in TARGETS if args.target == "both" else [args.target]:
        prepare(target)
