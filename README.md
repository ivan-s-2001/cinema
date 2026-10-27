# CinemaHall — Streambert Android

CinemaHall is being rebuilt for **Android TV and Android phones** using one upstream visual/code base: [Streambert](https://github.com/truelockmc/streambert). The remaining native components are **TorrServer** and a small provider adapter for external catalogues. ARVIO and the old JetStream prototype are not part of this branch.

> **Development status:** this branch now builds a separate **CinemaHall Preview** Android APK (`app.cinemahall.next`) for TV and phones. It includes a minimal native catalogue/search, quality → voice selection, Torznab settings, embedded TorrServer and a Media3 player. This is a bring-up interface, **not the finished Streambert port**. Streambert's React/Electron renderer and the static multi-API snapshot are not connected to the Android UI yet. Playback, D-pad layout, HDR/Dolby Vision and device compatibility still need testing on hardware. The production app on `main` is unchanged.

## Windows: build and install PR #3

Run in PowerShell; Git is the only prerequisite (no Android Studio or Python):

```powershell
git clone --branch rewrite/streambert-android --single-branch https://github.com/ivan-s-2001/cinema.git cinema-pr3
cd cinema-pr3
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\install-tv.ps1 -TvAddress "192.168.8.118:5555"
```

For subsequent updates in this checkout: `git pull --ff-only`, then rerun the script. The TV and PC must share a network, network ADB must be enabled, and the TV must approve the connection. The first build downloads JDK 17, Android SDK 35 and Gradle 8.9 with checksum verification and accepts the Android SDK licences. Both ARM64 and ARMv7 engines are included. `-BuildOnly` skips device installation. The resulting file is `android/core/app/build/outputs/apk/debug/app-debug.apk`.

The Preview installs alongside the old CinemaHall, preserving the old installation and data. Subsequent local builds use your PC's debug signing key; an APK signed on another PC/CI will not update it. The script reports a signature mismatch without uninstalling anything. In GitHub Actions, **Android Preview APK** also builds and lints the APK.

See [the measured architecture audit](docs/ARCHITECTURE_AUDIT.md) for the API matrix, real catalogue statistics, Electron/Android blockers and the remaining rollout sequence.

## Sources and responsibilities

| Component | Responsibility |
| --- | --- |
| `web/streambert/` | Pinned React frontend: home, search, title details, seasons, library, source definitions |
| `web/catalogClient.mjs` | Small lazy-loading browser/WebView reader for home, RU/EN/original search and detail shards; not wired into upstream Streambert yet |
| `android/core/` | Buildable native Android Preview, Zona/search adapters, Media3 and TorrServer |
| `scripts/fetch_engine.py` | Fetches verified TorrServer MatriX.147.1 binaries for ARM64/ARMv7 |
| `scripts/build_catalog.py` | Builds a compact, deduplicated catalogue snapshot from KinoAPI and optional fallback providers |
| `.github/workflows/catalog.yml` | Tests aggregation on PRs; manually builds a downloadable snapshot using GitHub Actions secrets |
| `tests/LogicCheck.java` | Existing Java checks for episode identification, video quality and source ordering |

### API plan

Metadata sources are queried independently, then matched by stable identifiers instead of merging records solely on title. A failure in an optional metadata API must not block the catalogue:

- **KinoAPI** — Russian-language title data, Kinopoisk/TMDB/IMDb identifiers, original-resolution image candidates, ratings, episodes and descriptive voiceovers. Requires a user-supplied key configured on the device; the key must not be committed or included in the APK. KinoAPI returns **no playable video URLs**.
- **TMDB** — posters, wide backdrops, genres, overviews, credits; user-supplied free read token.
- **Zona API** — catalogue availability and source/quality information; unofficial, with no uptime guarantee.
- **Cinemeta** — fallback metadata and images, particularly IMDb-backed matching.
- **TVmaze** — seasons, episodes and stills with attribution.
- **AniList** — anime titles and metadata.
- **Streambert providers** — optional video-source candidates; an embedded player URL is *not* automatically a direct video stream. Each integration needs source-specific validation.

Quality/voice grouping must use **actual playable options**: Auto, 4K, 2K, 1080p, 720p, 576p, 480p, 360p and below if available. Within each resolution, identical dubbing tracks are deduplicated across sources. The user's selection on a title page and in the player must represent the same option. Failures should fall back to another eligible source without displaying torrents or technical URLs.

The reusable native `Sources.grouped()` now groups Zona candidates into quality tabs and normalized voice choices, keeping alternative sources for fallback. **Listed candidates are not yet playback-verified.** `Logic.voiceKey()` merges unambiguous spelling variants but does not collapse different dubbing studios. The Preview consumes these groups in quality/voice dialogs; descriptive KinoAPI voiceover names alone never make a tab playable.

### Additional torrent searches: Zona + Torznab + optional RuTor

The native source adapter uses Zona's exact Kinopoisk ID first. The expanded overload `Sources.load(kinopoiskId, episode, aliases, year)` additionally searches the **TorrServer MatriX.147.1 built-in Torznab endpoint**, combining indexers configured in TorrServer (Jackett, Prowlarr and similar). It tries at most two distinct Russian/original title variants and one broad fallback; it also supports season/episode hints. If names are absent it can fetch them from Zona's film metadata index. Hashes are deduplicated across providers; quality inferred from a tracker title and unknown audio are clearly treated as hints, never verified tracks.

To activate Torznab in Preview, configure a separately hosted Jackett/Prowlarr HTTPS Torznab URL including its key under **Настройки источников** (the embedded engine uses port `18990` on `127.0.0.1`). The app preserves `EnableTorznabSearch` and `TorznabUrls` rather than resetting them on every launch. Do not commit Torznab keys or point a TV-only `localhost` setting at Jackett running on a different machine; use that machine's reachable LAN address. The setting is stored only on the device. RuTor is not exposed as a Preview toggle.

TorrServer also offers native RuTor search, which the adapter will query **only if the user has enabled it**. Its local compressed dataset and in-memory index can consume substantial resources, so it remains **off by default** on Xiaomi TV. External indexers on a VPS/NAS keep that work off the television. Any individual indexer can fail without hiding already found Zona matches. Only magnet and HTTPS torrent links are accepted.

`android/core/.../KinoApi.java` enriches Zona titles through the precise Kinopoisk ID and only reads the key from the Android app-private setting `cinemahall_settings/kinoapi_key`. The future Settings screen needs to supply this value at runtime; no secret is currently stored in the repository. KinoAPI's voiceover labels **must not** be presented as playable tracks until a separate playback source confirms the track.

### Shared TV/mobile catalogue snapshot

The metadata builder uses **KinoAPI** for popular movies, TV, anime, latest releases, browse filters and the release calendar. It matches duplicates across TMDB/Kinopoisk/IMDb/anime IDs and preserves all known Russian/English/original aliases. Conflicting identifiers are recorded rather than overwritten, and alternate anime season IDs are preserved. It distinguishes a verified wide backdrop from an image with unknown proportions, and keeps studio/voiceover labels separate from verified playback options. Selected detail cards include KinoAPI actors, recommendations, similar titles, TV seasons, episode/anime mappings, upcoming TV episodes and available skip markers. Only a bounded number of missing-art entries are enriched through **Cinemeta**, **TVmaze** (series), **AniList** (anime) and optional **TMDB** (`append_to_response=images,external_ids,videos`, with full-resolution candidates). Provider failure never blocks the entire catalogue.

For up to 200 popular Kinopoisk IDs, the builder also asks the public Zona index for *reported* resolution and voiceover labels. Its `sourceAvailability.zona` is either `unchecked` or `listed`; it deliberately never claims a torrent is verified playable. The native player must validate a real stream with TorrServer before showing a selectable track. If Zona fails, metadata generation continues and source status stays `unchecked`.

During the migration, every push to `rewrite/streambert-android` builds a catalogue snapshot, and later **Actions → Catalogue index → Run workflow** can build one manually. It reads the existing `KINOAPI_KEY` GitHub Actions secret, optionally `TMDB_READ_TOKEN`, and uploads `cinemahall-catalog` as multiple JSON files for both Android clients:

- `index.json` — home shelves, browsing filters, shard manifest and quality statistics; small enough for fast startup.
- `search.json` — compact all-language search lookup, loaded only when searching.
- `shards/movie-*.json`, `shards/tv-*.json` — up to 250 full cards each, loaded by category/page.

The browser/Android-WebView reader `web/catalogClient.mjs` retrieves the home screen first, lazily loads search and at most four detail shards, and does not need an API key. The current submodule has not yet been patched to call the reader.

The secret does not enter the artifact or APK. This is currently an **offline index generator**, not a deployed endpoint; the native Preview currently uses Zona directly. GitHub artifacts expire; production will need a persistent, permission-compliant static host or authenticated gateway.

For local generation only, set `KINOAPI_KEY` in your shell environment and run `python3 scripts/build_catalog.py --pages 15 --enrich 30 --details 12`. Unit checks run without keys via `python3 -m unittest discover -s tests -p 'test_catalog_builder.py'`. Increase `--pages` in manageable batches (up to 50 per type); KinoAPI has a 6-requests-per-second key limit. The index is deliberately capped instead of attempting to mirror its million-title database into a GitHub artifact.

## Android migration constraints

The original Streambert uses Electron IPC, desktop windows, local downloads, and Chromium interception, none of which work unchanged on Android. The Android port will retain the React UI where practical, replace Electron with a minimal native bridge, use the native Media3 player, and run TorrServer only when preparing playback. Phone layout and TV D-pad focus must both be verified on hardware. The Android runtime must not include the Electron binary.

The original production Android application is still on `main` until this branch builds, its functions are reconnected and the replacement is tested. Previous versions remain recoverable from Git history.

License: GPL-3.0. See [THIRD_PARTY.md](THIRD_PARTY.md).
