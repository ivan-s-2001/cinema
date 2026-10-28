# CinemaHall TV 0.7.1

The TV uses one embedded transport adapter for the CinemaHall API. It renders prepared cards, searches the server and plays the selected torrent locally. A third-party addon installation is never required.

## Changes

- The provider's shared manifest waits until it is available, then replays one successful result. It never emits an initial empty list. Playback, search and catalogue navigation use `first()` and previously captured that empty list permanently.
- Search sends one request per content type instead of repeating the same search through all eight home shelves.
- Catalogue pagination retains small server cursor strings independently of page JSON. It no longer stops at 240 items or downloads earlier pages again after those pages leave the cache.
- Movie and episode lookup preserves namespaced `ch:` identities.
- The full APK no longer includes QuickJS, CloudStream, dynamic extension loading, NiceHTTP, Conscrypt, extension Jackson/crypto dependencies, or their blanket R8 keep rules. Its existing inert plugin interface remains for common screen compatibility.
- Autotrailers, sidebar blur, card expansion and depth effects are unavailable, including for upgraded installations with old preferences. The layout settings category is removed. Catalogue is a separate navigation item. Existing home design, descriptions, genres, year/runtime/age, local favourites and playback remain.
- Launcher-channel, remote media-server state projection and periodic Nuvio startup sync no longer start with the application. Focused CinemaHall cards continue to use server previews. TMDB/MDBList enrichment is disabled in client settings; all available server ratings are rendered directly in both home hero variants.
- The provider no longer performs live Zona catalogue requests, client episode artwork enrichment, direct source searches, or a many-request fallback for older metadata servers. Bundled metadata remains an offline fallback. Source discovery uses only CinemaHall; the native torrent engine starts only after a source has been selected.
- A small source result cache (8 titles, 2 minutes) reuses discovered releases between list and play.

## Actor photos and server cache

Metadata accepts the snapshot builder's `image` field as well as `photo` and `profile_path`. Portraits use the same-origin w185 route. Verified JPEG/PNG/WebP bytes persist on the VPS under `/var/lib/cinemahall/image-cache/assets`, with a 128-MB disk budget, 7-day lifetime and atomic writes. Memory remains capped at 16 MB and cold downloads at two. Pictures are stored on first access; a missing upstream portrait is not fabricated. No database reset or migration is required.

## Validation boundaries

The unit regression covers delayed provider startup, movie/series stream support and manifest reuse. Backend checks cover snapshot portrait mapping, image validation, persistent reuse across API instances, expiration and the disk budget. CI compiles the actual embedded provider and Android app and builds an optimized release APK. Real torrent startup and remote-control frame times still require a physical TV check.
