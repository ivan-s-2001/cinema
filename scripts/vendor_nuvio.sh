#!/usr/bin/env bash
# Import pinned source archives as ordinary tracked files (no gitlinks, no git submodules).
set -euo pipefail
cd "$(dirname "$0")/.."
root="$(pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Exact upstream revisions used by the existing CinemaHall integration.
entries=(
  "android/nuvio-tv|NuvioMedia/NuvioTV|c3f68d84a5e0ec8fea7a027443a9beca50d32c31"
  "android/nuvio-mobile|NuvioMedia/NuvioMobile|61658f366f985fc7758f0942a14c1754c69aca68"
  "android/nuvio-tv/libmpv-android|jarnedemeulemeester/libmpv-android|f48aa82b2677428f756a689a44c2477c3f37be51"
  "android/nuvio-mobile/MPVKit|NuvioMedia/MPVKit|bfeb6fcfa2bd5081a9666ae5bb42b50e6c1537e8"
  "android/nuvio-mobile/libass-android|YogiBear12/libass-android|c10b71ab8b4d90796d4a795f775d337c29198ad0"
  "android/nuvio-mobile/vendor/TorrServer|YouROK/TorrServer|b79dbaf99ddc31c2b7f62fed997813861347ad9a"
  "android/nuvio-mobile/vendor/quickjs-kt|dokar3/quickjs-kt|57ce096200ac36bceb4e1ee5b6ec411b12357eb8"
  "android/nuvio-mobile/libass-android/lib_ass/src/main/cpp/libass-cmake|peerless2012/libass-cmake|d3f00a43ca66e42a2c34de964b1a7dbbfa9dbc8b"
  "android/nuvio-mobile/vendor/quickjs-kt/quickjs/native/c-vector|eteran/c-vector|774773d4cb1e66dd736e90e7f482d4f22af464c6"
  "android/nuvio-mobile/vendor/quickjs-kt/quickjs/native/quickjs|bellard/quickjs|f1139494d18a2053630c5ed3384a42bb70db3c53"
  "android/nuvio-mobile/libass-android/lib_ass/src/main/cpp/libass-cmake/src/ass|MeetAgent/libass|bbb3c7f1570a4a021e52683f3fbdf74fe492ae84"
  "android/nuvio-mobile/libass-android/lib_ass/src/main/cpp/libass-cmake/src/expat|limes-datentechnik-gmbh/libexpat|f9a3eeb3e09fbea04b1c451ffc422ab2f1e45744"
  "android/nuvio-mobile/libass-android/lib_ass/src/main/cpp/libass-cmake/src/fontconfig|Awesome-YB/fontconfig|daa175d234b8a362eedd4c18c33537cc2d19cd98"
  "android/nuvio-mobile/libass-android/lib_ass/src/main/cpp/libass-cmake/src/freetype|Neusoft-Technology-Solutions/freetype|42608f77f20749dd6ddc9e0536788eaad70ea4b5"
  "android/nuvio-mobile/libass-android/lib_ass/src/main/cpp/libass-cmake/src/fribidi|jbkempf/fribidi|68162babff4f39c4e2dc164a5e825af93bda9983"
  "android/nuvio-mobile/libass-android/lib_ass/src/main/cpp/libass-cmake/src/harfbuzz|chromium-full-mirror/external_github.com_harfbuzz_harfbuzz|c3fcbffa651cea70400552f2a8bd695ad11023c1"
  "android/nuvio-mobile/libass-android/lib_ass/src/main/cpp/libass-cmake/src/unibreak|adah1972/libunibreak|304585d8e2d63187507368d612c3d5fff1486368"
  "android/nuvio-mobile/libass-android/lib_ass/src/main/cpp/libass-cmake/src/freetype/subprojects/dlg|nyorain/dlg|72dfcc858c040c54a6a0b88fcb7e70ee186d3167"
)

download_source() {
  local destination="$1" repository="$2" sha="$3" archive="$work/source.tar.gz"
  echo "::group::Import $repository@$sha into $destination"
  mkdir -p "$destination"
  curl --fail --location --silent --show-error --retry 4 --retry-all-errors \
    --connect-timeout 20 --max-time 300 \
    "https://api.github.com/repos/${repository}/tarball/${sha}" -o "$archive"
  tar -xzf "$archive" -C "$destination" --strip-components=1
  rm -f "$archive"
  test -n "$(find "$destination" -type f -print -quit)" || { echo "::error::Empty tree $destination"; exit 1; }
  echo "::endgroup::"
}

# Parent trees first, then recursively overlay the pinned nested source trees.
for entry in "${entries[@]}"; do
  IFS='|' read -r directory repository sha <<< "$entry"
  download_source "$directory" "$repository" "$sha"
done

# A tar archive omits gitlink contents. Every gitlink in the pinned parent
# and nested trees MUST have a declared source above, otherwise fail the import.
for entry in "${entries[@]}"; do
  IFS='|' read -r directory repository sha <<< "$entry"
  gitlinks="$(curl --fail --silent --show-error \
    "https://api.github.com/repos/${repository}/git/trees/${sha}?recursive=1" \
    | python3 -c 'import json,sys; data=json.load(sys.stdin); assert not data.get("truncated"); print(chr(10).join(x["path"] for x in data["tree"] if x["mode"]=="160000"))')"
  if [ -n "$gitlinks" ]; then
    while IFS= read -r nested; do
      found=0
      for candidate in "${entries[@]}"; do
        IFS='|' read -r nested_directory nested_repo nested_sha <<< "$candidate"
        if [ "$nested_directory" = "$directory/$nested" ]; then found=1; break; fi
      done
      if [ "$found" -ne 1 ]; then
        echo "::error::Unresolved gitlink $directory/$nested in $repository@$sha"
        exit 1
      fi
    done <<< "$gitlinks"
  fi
done

# Remove metadata that would still describe these normal directories as submodules.
find android/nuvio-tv android/nuvio-mobile -type f -name .gitmodules -delete
git rm --cached -f android/nuvio-tv android/nuvio-mobile
git rm -f .gitmodules
git add -f -A -- android/nuvio-tv android/nuvio-mobile
if git ls-files --stage -- android/nuvio-tv android/nuvio-mobile | grep -q '^160000 '; then
  echo "::error::A gitlink remained in the imported source tree"
  exit 1
fi
number="$(git ls-files -- android/nuvio-tv android/nuvio-mobile | wc -l)"
echo "Imported $number regular source files"
test "$number" -gt 3000 || { echo "::error::Source count unexpectedly small"; exit 1; }
git status --short | tail -30
