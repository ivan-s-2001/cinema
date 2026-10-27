# Third-party components

## TorrServer

Version: MatriX.147.1
Source: https://github.com/YouROK/TorrServer/tree/MatriX.147.1
License: GPL-3.0 (see LICENSE).
Official release: https://github.com/YouROK/TorrServer/releases/tag/MatriX.147.1

Unmodified Android executables are packaged as `libtorrserver.so` for extraction to Android nativeLibraryDir. They are launched as subprocesses, not loaded through JNI.

| ABI | Original asset | SHA256 |
|---|---|---|
| arm64-v8a | TorrServer-android-arm64 | 93250bb1850e69663c6758398be515c2c429595cb4692c6eccb367f2236868d5 |
| armeabi-v7a | TorrServer-android-arm7 | e775001edf7bfb2adebfde0ba36780357c0951f12203adb367bd69b2b45522b6 |

## AndroidX Media3

Version: 1.8.0. License: Apache-2.0. Source: https://github.com/androidx/media/tree/1.8.0. Dependencies are resolved by Gradle from the Google Maven repository.

## Application

CinemaHall source: GPL-3.0. Catalog protocol was independently implemented from observed JSON and public examples. No Zona branding or proprietary application code is bundled.

## JetStream Compose

The application shell adapts Google Android TV Samples / JetStream Compose (Apache-2.0):
https://github.com/android/tv-samples/tree/15ac8df7df91a07450751a5fe8c6fd17a6a4297f/JetStreamCompose

`MovieCard.kt` and `JetStreamFocusTheme.kt` retain upstream components with package/import and focus style changes. `MainActivity.kt` adapts the HomeScreen/MoviesRow/Dashboard structure for the Zona catalog and direct playback. Original copyright notices are preserved. Full Apache-2.0 license is in `licenses/JetStream-Apache-2.0.txt`. Demo movies, Google branding, server login, and sample playback logic are not used.

Coil 2.7.0: Apache-2.0, https://github.com/coil-kt/coil. Compose for TV 1.0.0 and AndroidX Activity Compose 1.10.1: Apache-2.0.

## External metadata services

Cinemeta: https://v3-cinemeta.strem.io/manifest.json — official Stremio metadata add-on. Access follows the public HTTP add-on protocol; no Stremio implementation code is bundled. Images and credits are supplied remotely, subject to their respective owners.

TVmaze: https://www.tvmaze.com/api — episode metadata is licensed CC BY-SA 4.0. Detail pages attribute TVmaze and show the source show URL. Shared episode metadata retains this license: https://creativecommons.org/licenses/by-sa/4.0/. Metadata is kept distinct from application source code.
