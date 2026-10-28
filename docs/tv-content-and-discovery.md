# CinemaHall TV 0.7.2: episodes and content discovery

The TV receives credits, related titles and viewing order in its single `/api/v2/titles/:id/tv-meta` response. The server hydrates missing credits and season inventories using the configured KinoAPI key, persists them and coalesces concurrent requests. It creates numbered entries only from authoritative season episode counts. The first season’s episode names, dates and stills are warmed before the initial response. Other seasons are filled by one bounded background worker. Scheduled preparation drains that queue before exiting. When a still is unavailable, the response supplies the series backdrop (poster as a last fallback). Existing detailed entries are preserved. Oversized episode metadata drops optional per-episode descriptions, keeping thumbnails, while retaining all episode identities within the 2-MB/10000-entry response budget.

Episode selection remains visible for series in the detail page and the player. Empty inventories have an explicit reload state. Selecting an episode inside the player opens quality/voice selection; it no longer silently chooses a release. The shared picker displays release technology hints (HDR, Dolby Vision, HEVC, Atmos etc.). These are advertised release attributes, not a claim that the player's decoded tracks have been verified.

`006_discovery_people.sql` adds availability/rank columns and a people/cast index. It does not remove existing titles, accounts or history. `prepare-discovery.mjs --full` applies the idempotent migration and prepares the availability index before API cutover. It batches precise Kinopoisk matches and exact name/year/type matches, then checks live seeded torrent counts through facets. Failed upstream batches preserve previous observations. Unknown, unavailable and expired records are excluded from public v2 browse/search, related titles, viewing-order cards and actor filmographies. Old offline shelves are not used as an unverified fallback.

The scheduler refreshes up to 400 due inventory records and credits for up to 40 titles every 15 minutes. Available records are refreshed first. Positive availability expires after 6–12 hours, negative observations after 3–6 hours, with deterministic jitter to avoid an expiry cliff. Real peer connectivity is checked when playback opens the torrent; a catalogue source observation cannot guarantee every peer will remain online.

Home has ten short shelves. Genre navigator adds popular and recent genre shelves; “new” means the current year and the previous two years. Popularity comes from the source catalogue, with rating as a tie-break; high-score shelves use rating, and recent shelves use year. Minimum score and genre membership are explicit in `collections.mjs`. Search and actor filmography are not subject to home quality-score cutoffs.

Credits use stable provider-based person IDs and w185 portraits. Existing snapshot credits are indexed before cutover; missing or stale credits are loaded on detail access and by the background pass. Filmography lists matching entries from the local watchable catalogue through `/people/:id/filmography`, with cursor pagination. It grows as remaining credits are indexed; it is not a list of unavailable external films.

Detail shows actor credits, then a separate viewing-order block, then a separate related-films/series block. Related titles share genres and/or cast and are ranked on the server. Viewing orders use release order, including series in the same universe. Movie and series TMDB IDs are matched in separate namespaces. The initial registry now covers 32 cycles, including Game of Thrones / House of the Dragon, Despicable Me / Minions and major animation/action franchises. Existing Marvel, Star Wars, Wizarding World and Middle-earth orders were changed to release order. Missing or unavailable entries are omitted, while original positions and available/total counts remain visible. Extend `watch-orders.mjs` for additional cycles; do not infer membership from similar names. Editorially grouped subseries (for example, Villeneuve’s Dune films) are labelled explicitly.

Code-only deployment checks real poster bytes, episode selection, navigable actor credits, actor filmography and source discovery before and after switching; failure restores the previous service definition. The database migration is additive and compatible with the previous API.


## TV 0.7.3 navigator and fixes

The navigator queries `/api/v2/discover` directly, with no shelf restriction. Its search covers the entire available catalogue (both types by default). Independent type, genre, exact year, country and sort filters use keyset paging. `/api/v2/facets[?type=movie|series]` derives genre/country/year choices from the current released, public, watchable database; the TV does not maintain a hardcoded genre list. Filter or query changes reset paging. A separate optional shelf picker exposes all forty curated shelves; entering a search exits the shelf and searches the whole available database with the independently selected filters. Forty server shelves include genre, decade, duration and description-keyword themes; fourteen bounded shelves appear on home. All shelves require fresh positive source observations.

Actor pages are registered in both the main and detail-child navigation graphs, including nested detail and back-stack restoration. Related and cycle rows use 2:3 portrait cards instead of putting a portrait into a 16:9 rectangle. TV 0.7.3 uses versionCode 1075. Device-level D-pad navigation remains a release smoke check; unit/compile CI cannot confirm it.


## Title artwork and essential player controls

Preview and detail DTOs always provide a title image. Original provider logos are preserved and resized through the same-origin w500 proxy. Missing originals are fetched in a bounded background queue using the existing title-by-TMDB provider endpoint with strict type/ID checks. Russian logo alternatives are preferred, and unsuccessful empty lookups are checked again after seven days. Missing logos use `/api/v2/titles/:id/logo.svg`, a deterministic transparent typographic wordmark generated from the stored title, with escaped bounded text, ETag/HEAD support and one-day caching. This fallback is not original studio artwork. It requires no additional image library on the server; the TV already includes SVG decoding.

TV 0.7.4 keeps play/pause, 10-second seeking, the timeline, episode and unified quality/voice selection, and restores subtitles, next episode and a compact More menu (speed and aspect ratio). Separate embedded audio-track selection and its overlay were removed; remote/audio-selection events are gated. Technical HUD, engine/external-player selection, chapters, parties and reports remain absent. Remote INFO opens quality/voice. The separate pause-information overlay stays disabled. Automatic engine recovery, local progress and episode/source selection remain.


## Narrative connections

The viewing-order registry distinguishes a narrative cycle from a shared universe. Each part has explicit `cycleRoles` (`original`, `sequel`, `prequel`, `spin_off`, `alternate_sequel`, `reboot`, `interquel`, or `universe`). Directed `connections` identify which existing title a sequel/prequel/spin-off relates to. Sequel chains within spin-off/prequel branches are preserved rather than treating every new release as a continuation of the previous release. Release ordering remains independent of narrative role.

`GET /api/v2/titles/:id/relations` separates `cycles` and `universes`. `/watch-orders` and TV `cinemaWatchOrders` expose narrative cycles; the broader Infinity Saga is no longer mislabelled as an Avengers sequel chain. Ten individual Marvel cycles were added, including Avengers, Iron Man, Thor, Captain America, Guardians, Ant-Man, Doctor Strange, Black Panther, Captain Marvel and MCU Spider-Man. The initial curated registry now has 41 narrative groups and one wider universe group. It is editorially maintained; universal automatic coverage is not claimed, and no relationship is guessed from names, genres or release years.

TV 0.7.3 shows a short role label beside the year via `releaseInfo`, keeping part titles intact and requiring no APK change. API consumers also receive structured roles and directed connections. Narrative-cycle members are excluded from the generic related shelf before LIMIT; unrelated titles with no TMDB mapping remain eligible. Only public, released, freshly watchable entries are clickable, with no dangling connections to unavailable cards.

Verified examples: House of the Dragon is a prequel/spin-off of Game of Thrones (HBO/WBD); Monsters University is a prequel rather than a sequel (Disney D23); Minions are a prequel/spin-off branch of Despicable Me (Universal). Sources are retained on each group.


## TV 0.7.4 playback and season artwork

The source query includes both `S01E01` and `S1E1`. A release with a known episode map for a different season/episode is rejected, including generic season-pack fallback results. Only the requested file mapping travels to the TV. Discovery returns its first page, then continues in the background up to 600 releases, at most two upstream page requests concurrently. `source-candidates` exposes `discoveryComplete`, `availableReleases`, `nextOffset` and an `after` cursor, with no generic response cache. Partial TV results are polled up to twenty times and merged into the same quality/voice panel; the visible variant count can grow. Studio spelling aliases are normalized without inventing a studio or dubbing type, and multi-studio labels remain distinct. Videos are not stored on the API server.

Before a source is marked successful, the local engine must provide actual bytes via a bounded Range request. Up to four candidates share a 45-second preparation budget after engine startup. A native watchdog ends initial buffering after 60 seconds, or stalled rebuffering after 30 seconds, with a direct quality/voice action. This prevents indefinite waiting; it cannot make an unseeded torrent playable. Local preload is five percent of the bounded engine cache.

Selected-season endpoints hydrate and persist that season's episode stills on demand, coalescing duplicate work and caching a successful season lookup for six hours. Both detail and player request the selected season; other seasons continue server background hydration. Missing stills are no longer misrepresented as the same series backdrop. Upstream episodes without a still remain without a fabricated screenshot. Selected season tabs keep a tinted background and border when focus moves to episodes.

Logo selection explicitly prefers Russian, then English, then unknown-language artwork. English/unknown logos include `logoCaption` with the stored Russian title; TV renders it centered directly underneath at 9sp. Generated Russian wordmarks do not repeat the caption. Existing provider logo selections are refreshed once for this selection version, then every seven days. Original English artwork is preferred over a generated wordmark when available; provider coverage is not assumed universal.

TV release: 0.7.4-tv, versionCode 1076. Actual playback and D-pad behavior on the user's physical television remain device smoke checks.

## Verified TV 0.7.4 deployment

Code release: `f5dd057e56b30e37281808c4bb45ae4e3a17bc6a`. API CI, Android TV compile/selected regressions, provider contract and optimized APK build passed. APK: [CinemaHall-TV 0.7.4-tv](https://github.com/ivan-s-2001/cinema/actions/runs/40715195202/artifacts/12829466490). These checks do not replace a physical-TV playback/focus smoke check.

Exit an existing live journal view with Ctrl+C, then run as root on the CinemaHall host:

```bash
cd /opt/cinemahall-source
git -c safe.directory="$PWD" fetch origin work/cinemahome-api-requirements
git -c safe.directory="$PWD" show f5dd057e56b30e37281808c4bb45ae4e3a17bc6a:backend/ops/update-api.sh > /root/cinemahall-update.sh
bash -n /root/cinemahall-update.sh && systemd-run --unit=cinemahall-api-tv074 --collect /bin/bash /root/cinemahall-update.sh f5dd057e56b30e37281808c4bb45ae4e3a17bc6a
journalctl -u cinemahall-api-tv074 -f -n 40
```

Wait for `API_UPDATE_OK`, then install the APK. This updates the existing database/service through the established deployment flow. The later documentation commit does not change release code. [Zona/open-source research](zona-streaming-research.md) distinguishes the current implementation from the proposed persistent file/track index and combined playback choices.


## TV 0.7.5: choose before starting

Every explicit CinemaHall film/episode launch opens quality and voice selection before creating the player, including cached results, resume and retry. Direct autoplay preferences and remembered source groups cannot bypass this boundary. The picker focuses its first quality tab, renders the first discovered options without waiting for search completion, preserves the selected quality during later updates, and omits the automatic/recommended pseudo-quality. Choosing a voice starts playback with the existing resume position handling.

In-player automatic continuation uses the chosen quality/voice binge group. If that combination is missing for the next episode, it opens the picker instead of silently changing the studio or quality. Filename-only fallback follows the same rule. Manual episode selection in the player already opens quality/voice choices.

TV version: 0.7.5-tv, versionCode 1077. Server file/track indexing remains the separate architecture work described in the research document; this change establishes the requested launch behavior.
