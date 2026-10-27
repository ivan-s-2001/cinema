# Third-party components

## ARVIO

The complete Android application is used as the TV, phone and tablet foundation.
Source: https://github.com/ProdigyV21/ARVIO
Revision: 814638307236adb8eb572ed343f126de46b61b27
License: Apache-2.0. Original license, attribution and dependency notices remain in the pinned source. CinemaHall modifications are kept as a separate patch and overlay. Screens, player UI, icons and navigation must not be described as original CinemaHall designs.

## TorrServer

Unmodified Android executables, MatriX.147.1, GPL-3.0.
https://github.com/YouROK/TorrServer/releases/tag/MatriX.147.1

- ARM64 SHA256: 93250bb1850e69663c6758398be515c2c429595cb4692c6eccb367f2236868d5
- ARMv7 SHA256: e775001edf7bfb2adebfde0ba36780357c0951f12203adb367bd69b2b45522b6

Executables are packaged as libtorrserver.so for nativeLibraryDir extraction and run as subprocesses. They are not loaded through JNI.

## Metadata

- Zona: independently implemented catalogue requests; no proprietary application code is bundled.
- Cinemeta: public Stremio metadata add-on, https://v3-cinemeta.strem.io/manifest.json. Images remain subject to their respective owners.
- TVmaze: episode metadata, CC BY-SA 4.0, https://www.tvmaze.com/api. The source show URL and attribution accompany detailed episode metadata.

## CinemaHall

Adapters and packaging changes: GPL-3.0 (LICENSE). The upstream Apache-2.0 components keep their original notices and terms. GPL text and CinemaHall-NOTICE are included in APK assets. Other dependencies retain the versions and terms documented by the pinned upstream application.
