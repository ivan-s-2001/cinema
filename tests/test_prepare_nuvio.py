"""Build preparation configures an SDK while preserving the integrated source tree."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("prepare_nuvio", Path(__file__).resolve().parents[1] / "scripts/prepare_nuvio.py")
prepare_nuvio = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare_nuvio)


class PrepareNuvioTest(unittest.TestCase):
    def test_prepare_preserves_application_sources_and_other_local_settings(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            project = root / "android/nuvio-tv"
            manifest = project / "app/src/main/AndroidManifest.xml"
            manifest.parent.mkdir(parents=True)
            manifest.write_text('<manifest package="app.cinemahall.tv"/>')
            (project / "gradlew").write_text("wrapper marker")
            source = project / "app/src/main/custom.kt"
            source.write_text("local user changes")
            (project / "local.properties").write_text("custom.setting=keep\nsdk.dir=/missing\n")
            sdk = root / "sdk"
            (sdk / "platform-tools").mkdir(parents=True)
            (sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")).touch()
            with patch.object(prepare_nuvio, "ROOT", root), patch.dict(os.environ, {"ANDROID_HOME": str(sdk)}):
                prepare_nuvio.prepare("tv")
            self.assertEqual("local user changes", source.read_text())
            self.assertEqual("wrapper marker", (project / "gradlew").read_text())
            self.assertEqual(f"custom.setting=keep\nsdk.dir={sdk.as_posix()}\n", (project / "local.properties").read_text())

    def test_missing_vendored_sources_fail_without_importing_or_resetting_sources(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(prepare_nuvio, "ROOT", Path(directory)):
            with self.assertRaisesRegex(RuntimeError, "vendored sources are missing"):
                prepare_nuvio.prepare("tv")
            self.assertEqual([], list(Path(directory).iterdir()))


if __name__ == "__main__":
    unittest.main()
