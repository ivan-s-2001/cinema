# Third-party components

## Streambert

The React frontend and provider definitions are adapted from Streambert.
Source: https://github.com/truelockmc/streambert
Revision: 1a4bf7e665a7fd54c6107d62e258346c24ccd66f
License: GPL-3.0. The original notices and source history are preserved in the submodule. Its Electron desktop process cannot run as an Android runtime, so the Android port needs its own bridge and native player.

## TorrServer

Unmodified Android executables, MatriX.147.1, GPL-3.0.
https://github.com/YouROK/TorrServer/releases/tag/MatriX.147.1

- ARM64 SHA256: 93250bb1850e69663c6758398be515c2c429595cb4692c6eccb367f2236868d5
- ARMv7 SHA256: e775001edf7bfb2adebfde0ba36780357c0951f12203adb367bd69b2b45522b6

Executables are packaged as libtorrserver.so for nativeLibraryDir extraction and run as subprocesses. They are not loaded through JNI.

## Metadata

- Zona: independently implemented catalogue requests; no proprietary application code is bundled.
- TMDB: upstream Streambert's catalogue/images require a free, user-supplied read token. https://developer.themoviedb.org/
- AniList: upstream Streambert anime metadata, https://anilist.gitbook.io/anilist-apiv2-docs/
- Cinemeta: public Stremio metadata add-on, https://v3-cinemeta.strem.io/manifest.json. Images remain subject to their respective owners.
- TVmaze: episode metadata, CC BY-SA 4.0, https://www.tvmaze.com/api. The source show URL and attribution accompany detailed episode metadata.

## CinemaHall

Adapters and packaging changes: GPL-3.0 (LICENSE). Existing CinemaHall notices are kept in the Android core assets. New native components will declare their own third-party dependencies before APK release.
