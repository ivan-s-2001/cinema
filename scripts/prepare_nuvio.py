"""Apply the checked-in integration to pinned upstreams; never reset user's local edits."""
from pathlib import Path
import argparse
import subprocess
ROOT = Path(__file__).resolve().parents[1]
TARGETS = {"tv": ("nuvio-tv", "c3f68d84a5e0ec8fea7a027443a9beca50d32c31"),
           "mobile": ("nuvio-mobile", "61658f366f985fc7758f0942a14c1754c69aca68")}

def prepare(target):
    name, expected = TARGETS[target]
    directory = ROOT / "android" / name
    if not (directory / ".git").exists():
        subprocess.run(["git", "-C", str(ROOT), "submodule", "update", "--init", "--depth", "1", "--", f"android/{name}"], check=True)
    revision = subprocess.check_output(["git", "-C", str(directory), "rev-parse", "HEAD"], text=True).strip()
    if revision != expected:
        raise RuntimeError(f"{name}: expected {expected}, got {revision}; update the submodule checkout first")
    patch = ROOT / "android" / f"{name}.patch"
    def can_apply(*options):
        return subprocess.run(["git", "-C", str(directory), "apply", *options, str(patch)], capture_output=True).returncode == 0
    if can_apply("--reverse", "--check"):
        print(f"{name}: integration already applied")
    elif can_apply("--check"):
        subprocess.run(["git", "-C", str(directory), "apply", str(patch)], check=True)
        print(f"{name}: integration applied")
    else:
        raise RuntimeError(f"{name}: patch does not match; local edits were preserved")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", choices=["tv", "mobile", "both"], default="both")
    args = parser.parse_args()
    for target in TARGETS if args.target == "both" else [args.target]:
        prepare(target)
