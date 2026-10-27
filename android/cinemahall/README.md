# CinemaHall Next (independent Android)

An original, minimal Java Android application for **Android TV and mobile**. This directory is not a Git submodule, has no Nuvio runtime and does not apply Nuvio patches. It reuses the CinemaHall-owned `../provider` and `../core` source adapters, bundled catalogue and local TorrServer.

First milestone:
- Native D-pad/touch shelves, title search and detail navigation.
- Separate `poster` (portrait card) and `background` (wide hero); no poster fallback in hero.
- Bounded concurrent thumbnails, sampled bitmap decoding, 16 MiB memory cache.
- Seasons/episodes, quality-then-voice picker from *actual provider stream choices*.
- Experimental Android `VideoView` player. It **does not guarantee MKV/Remux, HDR/Dolby Vision, multi-audio or 4K support**; Media3/native audio-track selection is later work.
- Side-by-side install identifiers: `app.cinemahall.next.tv` and `app.cinemahall.next.mobile` (does not replace the current app).
- Independent Gradle 8.13 + Android Gradle Plugin 8.11.1 build; API 36, min API 26.

Build (JDK 21, Android SDK 36, Gradle 8.13):

```bash
python3 scripts/fetch_engine.py
gradle -p android/cinemahall :app:assembleTvDebug :app:assembleMobileDebug
```

CI: `.github/workflows/native-android.yml` produces two debug APKs. When `KINOAPI_KEY` is configured in GitHub Actions, the existing catalogue generator bundles snapshot shards. Otherwise it falls back to live catalogue requests; no API key is hardcoded in the APK.

The original Nuvio submodules are retained elsewhere only as a temporary working fallback during migration. Do not delete them until player and feature parity are verified on actual devices. Third-party engine code retains its own licence.
