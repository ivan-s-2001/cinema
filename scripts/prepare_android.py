"""Prepare the pinned ARVIO application. Uses git and Python's standard library."""
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / 'android' / 'arvio'
PIN = '814638307236adb8eb572ed343f126de46b61b27'
subprocess.run(['git', '-C', str(ROOT), 'submodule', 'update', '--init', '--depth', '1', '--', 'android/arvio'], check=True)
actual = subprocess.check_output(['git', '-C', str(APP), 'rev-parse', 'HEAD'], text=True).strip()
if actual != PIN:
    raise RuntimeError(f'Unexpected ARVIO revision: {actual}')
patch = ROOT / 'android' / 'arvio.patch'
check = subprocess.run(['git', '-C', str(APP), 'apply', '--reverse', '--check', str(patch)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
if check.returncode:
    subprocess.run(['git', '-C', str(APP), 'apply', '--check', str(patch)], check=True)
    subprocess.run(['git', '-C', str(APP), 'apply', str(patch)], check=True)
for source in (ROOT / 'android' / 'overlay').rglob('*'):
    if source.is_file():
        target = APP / source.relative_to(ROOT / 'android' / 'overlay')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
print(f'CinemaHall: ARVIO {PIN} with TV + mobile UI prepared')
