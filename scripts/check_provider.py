"""Compile the real shared provider and run its offline contract check (JDK + Android SDK)."""
from pathlib import Path
import hashlib
import os
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
JSON_SHA = "3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796"

def main():
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    platforms = sorted((sdk / "platforms").glob("*/android.jar"))
    if not platforms:
        raise RuntimeError("Install an Android SDK platform first")
    java = Path(os.environ["JAVA_HOME"]) / "bin"
    suffix = ".exe" if os.name == "nt" else ""
    with tempfile.TemporaryDirectory(prefix="cinemahall-check-") as directory:
        work = Path(directory)
        jar = work / "json.jar"
        configured = os.environ.get("CINEMA_JSON_JAR")
        jar.write_bytes(Path(configured).read_bytes() if configured else urllib.request.urlopen(
            "https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar", timeout=30).read())
        if hashlib.sha256(jar.read_bytes()).hexdigest() != JSON_SHA:
            raise RuntimeError("JSON test library checksum mismatch")
        # Only the Android-generated resource identifier is stubbed; compile the real service and provider.
        resource = work / "R.java"
        resource.write_text("package app.cinemahall.tv; final class R { static final class drawable { static final int ch_engine_logo=1; } }")
        sources = list((ROOT / "android/core/app/src/main/java/app/cinemahall/tv").glob("*.java"))
        sources += list((ROOT / "android/provider/src/main/java/app/cinemahall/tv").glob("*.java"))
        sources += [resource, ROOT / "tests/ProviderCheck.java"]
        subprocess.run([str(java / ("javac" + suffix)), "-encoding", "UTF-8", "-cp", str(platforms[-1]), "-d", str(work), *map(str, sources)], check=True)
        subprocess.run([str(java / ("java" + suffix)), "-cp", os.pathsep.join(map(str, [jar, work, platforms[-1]])), "app.cinemahall.tv.ProviderCheck"], check=True)

if __name__ == "__main__":
    main()
