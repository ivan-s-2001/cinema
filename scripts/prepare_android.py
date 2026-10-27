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
def can_apply(args, content=None):
    return subprocess.run(
        ['git', '-C', str(APP), 'apply', *args],
        input=content, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    ).returncode == 0


if not can_apply(['--reverse', '--check', str(patch)]):
    if can_apply(['--check', str(patch)]):
        subprocess.run(['git', '-C', str(APP), 'apply', str(patch)], check=True)
    else:
        # A previous CinemaHall patch may still be applied after git pull.
        # Only reverse a known historical patch that cleanly matches; never reset the submodule.
        history = subprocess.check_output(
            ['git', '-C', str(ROOT), 'log', '--format=%H', '--', 'android/arvio.patch'],
            text=True,
        ).splitlines()
        upgraded = False
        for commit in history[1:10]:
            previous = subprocess.check_output(
                ['git', '-C', str(ROOT), 'show', f'{commit}:android/arvio.patch']
            )
            if not can_apply(['--reverse', '--check', '-'], previous):
                continue
            subprocess.run(['git', '-C', str(APP), 'apply', '--reverse', '-'], input=previous, check=True)
            try:
                subprocess.run(['git', '-C', str(APP), 'apply', '--check', str(patch)], check=True)
                subprocess.run(['git', '-C', str(APP), 'apply', str(patch)], check=True)
            except subprocess.CalledProcessError:
                subprocess.run(['git', '-C', str(APP), 'apply', '-'], input=previous, check=True)
                raise
            upgraded = True
            break
        if not upgraded:
            raise RuntimeError('ARVIO contains unrelated changes; cannot apply CinemaHall patch safely')
for source in (ROOT / 'android' / 'overlay').rglob('*'):
    if source.is_file():
        target = APP / source.relative_to(ROOT / 'android' / 'overlay')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
print(f'CinemaHall: ARVIO {PIN} with TV + mobile UI prepared')
