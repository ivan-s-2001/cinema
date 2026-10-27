# Lightweight UI candidate: Streamflix Reborn

The **Streamflix Reborn** project is pinned as the `android/streamflix` Git submodule.
It ships a native Android TV Leanback UI and a separate touch-oriented mobile UI, with Media3 playback. This is a trial build intended to measure UI responsiveness on the real Xiaomi TV before replacing CinemaHall's current ARVIO application.

## Get the trial APK

1. Open **Actions → Streamflix Shell Trial → Run workflow** in this repository.
2. Download the `Streamflix-shell-trial` artifact once the build succeeds.
3. Install the APK alongside CinemaHall and compare focus movement, scrolling, poster loading and memory usage. The trial retains the upstream application ID with `.debug` suffix, so it does not replace your working CinemaHall installation.

This trial uses **the upstream application's catalog**, not CinemaHall's Zona adapter. Its purpose is to validate the ready-made shell on actual hardware. No promise about lower RAM consumption or FPS is made without measurements. The existing sources, quality/voice selector and TorrServer integration must be ported and verified before switching the production application to the new shell.

Licensing: Streamflix Reborn is Apache-2.0; preserve its original notices and attribution.
