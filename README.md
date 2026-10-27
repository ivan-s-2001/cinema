# CinemaHall — Nuvio TV + Mobile

CinemaHall uses the **actual Nuvio applications**, with their original native interfaces and players:

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
git clone --branch rewrite/streambert-android --single-branch https://github.com/ivan-s-2001/cinema.git cinema-pr3
cd cinema-pr3
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\install-tv.ps1 -TvAddress "192.168.8.118:5555"
```

For an existing checkout, run `git pull --ff-only` first. The script initializes the pinned submodule, applies only the integration patch, downloads verified JDK 21 / command-line tools 23 / SDK / TorrServer packages, builds the full application, installs via ADB, and resolves its actual launcher. It never uninstalls existing apps. If a submodule contains incompatible local modifications, it stops without resetting them. After an integration patch changes, use a fresh checkout if an older patch is still applied.

Other targets:

```powershell
# Mobile APK, without installing it on the TV
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\install-tv.ps1 -Target mobile -BuildOnly

# Build both applications
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\install-tv.ps1 -Target both -BuildOnly
```

TV output: `android/nuvio-tv/app/build/outputs/apk/full/debug/`.
Mobile output: `android/nuvio-mobile/androidApp/build/outputs/apk/full/debug/`.
Packages: `app.cinemahall.tv` and `app.cinemahall.mobile`. Both use the PC's local debug key, so subsequent builds on that PC can update without deleting data. An APK signed elsewhere cannot replace it without a matching key.

The first build is substantial: TV uses Gradle 8.13 / Android SDK 36; the pinned mobile rewrite uses Gradle 9.4.1 / SDK 37.1. The wrapper distribution hashes are pinned. The script accepts Android SDK licences. Features have not been removed to claim a small APK; device RAM/scroll performance must be measured.

## CI and local checks

`.github/workflows/android.yml` builds TV and mobile separately. If the existing `KINOAPI_KEY` secret is available, a preceding job generates one multi-API snapshot and bundles it into both APKs. API keys do not enter the JSON or APK. Without a key, both use the live fallback.

```bash
python scripts/prepare_nuvio.py --target both
python scripts/fetch_engine.py
python -m unittest discover -s tests -p 'test_catalog_builder.py'
node --test tests/test_catalog_client.mjs
```

The shared provider compiles against Android SDK 36 and has an offline `tests/ProviderCheck.java` contract check. Full APK validation must be read from the **new** Nuvio workflow; prior successful Preview builds do not validate this migration. This PR remains a draft until both applications build and the viewing flow is tested on hardware.
