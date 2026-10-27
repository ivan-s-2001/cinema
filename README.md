# CinemaHall — Android TV

CinemaHall currently develops and ships **Android TV** on the Nuvio native interface. Mobile sources are retained for later work, but mobile builds and support are paused.

- `android/nuvio-tv` — Nuvio Android TV, pinned to `c3f68d84a5e0ec8fea7a027443a9beca50d32c31`.
- `android/nuvio-mobile` — Nuvio Mobile / Compose Multiplatform, pinned to `61658f366f985fc7758f0942a14c1754c69aca68`.
- `android/nuvio-tv.patch`, `android/nuvio-mobile.patch` — CinemaHall branding, separate package identities, our built-in provider, and build integration. Upstream screens, profiles, library, playback and extension support remain in the upstream projects.
- `android/provider` — **our** catalogue, metadata, search and playback API, shared by both apps.
- `android/core` — our Zona/KinoAPI/Cinemeta/TVmaze adapters, source grouping, matching and embedded TorrServer.
- `scripts/build_catalog.py` — our multi-API metadata aggregation pipeline (KinoAPI, optional TMDB, AniList, Cinemeta, TVmaze).

The Electron-based Streambert shell and the text-only Android Preview are no longer application entry points. GPL notices and upstream source history are preserved. See [THIRD_PARTY.md](THIRD_PARTY.md).

## Data belongs to the CinemaHall integration

The built-in provider is registered automatically, including for new local profiles. It supplies our home shelves, all-language search, title metadata, seasons/episodes and quality/voice choices through the clients' normal add-on contract. It is available on loopback only (`127.0.0.1:18991` for TV, `18992` for mobile); no server installation or manual manifest entry is required.

If `android/core/app/src/main/assets/catalog/` contains our generated snapshot, the clients read that multi-API catalogue and load detail shards on demand. The snapshot preserves stable source IDs and collision suffixes. Without a bundled snapshot, live Zona is the catalogue fallback; opened titles can be enriched by our existing metadata adapters. We do not pass Kinopoisk IDs off as IMDb/TMDB IDs. A metadata voice label never becomes a playable track on its own.

Playback uses our source resolver and embedded TorrServer MatriX.147.1, then passes the prepared HTTP video stream to the native Nuvio player. Source failures during preparation try up to four eligible alternatives of the selected quality/voice. Resolution/voice labels are provider claims until the actual media is inspected. HDR/Dolby Vision, 4K playback, focus behavior and memory usage still require device testing.

Nuvio's optional third-party account/integration screens are retained, but CinemaHall does not inherit upstream backend credentials or service access. Use local storage mode for the base scenario; cloud sync, external accounts and integrations require their own configuration. Core catalogue playback does not require a Nuvio account. Source setup is not implied to be included merely because a settings screen exists.

## Windows: build and install on TV

Git is required; Python and Android Studio are not required by the Windows script.

```powershell
git clone --branch feat/cinemahall-nuvio-quality-voice --single-branch https://github.com/ivan-s-2001/cinema.git cinemahall
cd cinemahall
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\install-tv.ps1 -TvAddress "192.168.8.118:5555"
```

For an existing checkout, run `git pull --ff-only` first. The current branch contains vendored Nuvio TV sources. The script prepares the Android SDK and TorrServer, builds the TV application, installs via ADB, and resolves its launcher. It never uninstalls an existing application.

Build the TV APK without installing:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\install-tv.ps1 -Target tv -BuildOnly
```

TV output: `android/nuvio-tv/app/build/outputs/apk/full/debug/`.
Package: `app.cinemahall.tv`. The local debug signing key lets subsequent builds on the same PC update without deleting data. APKs signed with a different key cannot replace it directly.

The first TV build is substantial: Gradle 8.13 / Android SDK 36. The wrapper distribution hashes are pinned. The script accepts Android SDK licences. Features have not been removed to claim a small APK; device RAM/scroll performance must be measured.

## CI and local checks

`.github/workflows/android.yml` compiles, tests and packages Android TV only. If the existing `KINOAPI_KEY` secret is available, a preceding job generates a multi-API snapshot and bundles it into the TV APK. API keys do not enter the JSON or APK. Without a key, both use the live fallback.

```bash
python scripts/prepare_nuvio.py --target both
python scripts/fetch_engine.py
python -m unittest discover -s tests -p 'test_catalog_builder.py'
node --test tests/test_catalog_client.mjs
```

The shared provider compiles against Android SDK 36 and has an offline `tests/ProviderCheck.java` contract check. Full APK validation must be read from the **new** Nuvio workflow; prior successful Preview builds do not validate this migration. This PR remains a draft until TV playback, D-pad navigation, HDR/Dolby Vision and performance are checked on real hardware. Mobile work is paused.
