#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ ! -f .gitmodules ]; then
  echo "No .gitmodules: sources might already be vendored"
  exit 1
fi
git submodule update --init --depth 1 -- android/nuvio-tv android/nuvio-mobile
for name in nuvio-tv nuvio-mobile; do
  target="android/$name"
  test -f "$target/LICENSE" || { echo "License missing in $target"; exit 1; }
  test -d "$target/app" -o -d "$target/androidApp" || { echo "Source missing in $target"; exit 1; }
  revision="$(git -C "$target" rev-parse HEAD)"
  echo "$name source revision: $revision"
  # Nested gitlinks need their own complete source trees; never silently vendor empty folders.
  nested="$(git -C "$target" ls-files --stage | awk '$1 == "160000" {print $4}' )"
  if [ -n "$nested" ]; then
    echo "::error::$name contains unvendored nested modules:"
    printf "%s\\n" "$nested"
    echo "Resolve each nested source at its pinned SHA before committing a purportedly complete import."
    exit 1
  fi
  # Unstage gitlink; keep checked-out upstream files on disk.
  git rm --cached -f "$target"
  # Git submodule worktrees have a .git *file*. Never vendor git internals.
  rm -f "$target/.git"
  # Preserve upstream ignore rules, but track all original source files.
  git add -f -A -- "$target"
  if git ls-files --stage -- "$target" | grep -q '^160000 '; then
    echo "Gitlink still present: $target"; exit 1
  fi
  test "$(git ls-files -- "$target" | wc -l)" -gt 50 || { echo "Incomplete source tree: $target"; exit 1; }
done
git rm -f .gitmodules
echo "Vendored tracked files:"
git ls-files -- android/nuvio-tv android/nuvio-mobile | wc -l
git status --short | tail -30
