# Third-party components

## Nuvio TV and Nuvio Mobile

The native interfaces and players are the original Nuvio applications, adapted through checked-in patches rather than recreated screens.

- https://github.com/NuvioMedia/NuvioTV — revision c3f68d84a5e0ec8fea7a027443a9beca50d32c31, GPL-3.0.
- https://github.com/NuvioMedia/NuvioMobile — revision 61658f366f985fc7758f0942a14c1754c69aca68, GPL-3.0.

Each upstream retains its licence, authorship and dependency notices in its submodule. Nuvio's optional cloud/API services are not supplied as CinemaHall services. Source and integration changes remain available under GPL-3.0.

## TorrServer

Unmodified Android executables, MatriX.147.1, GPL-3.0.
https://github.com/YouROK/TorrServer/releases/tag/MatriX.147.1

- ARM64 SHA256: 93250bb1850e69663c6758398be515c2c429595cb4692c6eccb367f2236868d5
- ARMv7 SHA256: e775001edf7bfb2adebfde0ba36780357c0951f12203adb367bd69b2b45522b6

Packaged as libtorrserver.so for nativeLibraryDir extraction; not loaded through JNI.

## Our metadata adapters

- KinoAPI: user-provided key used by our aggregation pipeline, not embedded in clients.
- Zona: independently implemented catalogue/source requests; no proprietary application code.
- TMDB: optional user-provided token in our aggregation pipeline.
- Cinemeta: public metadata fallback, https://v3-cinemeta.strem.io/manifest.json.
- TVmaze: episode metadata, CC BY-SA 4.0; attribution and show URL accompany detailed episode metadata.
- AniList: anime metadata in our aggregation pipeline, https://anilist.gitbook.io/anilist-apiv2-docs/.

Images remain subject to their respective owners. CinemaHall adapters and packaging changes are GPL-3.0 (LICENSE).
