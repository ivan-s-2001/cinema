package com.nuvio.tv.ui.screens.player

/** Prevent remote shortcuts from reopening advanced menus removed from CinemaHall. */
internal fun cinemaPlayerActionAllowed(event: PlayerEvent): Boolean = when (event) {
    PlayerEvent.OnShowMoreDialog,
    PlayerEvent.OnShowSpeedDialog,
    PlayerEvent.OnShowStreamInfo,
    PlayerEvent.OnShowChaptersPanel,
    PlayerEvent.OnShowPartyPanel,
    PlayerEvent.OnShowAudioOverlay,
    PlayerEvent.OnShowSubtitleOverlay,
    PlayerEvent.OnOpenSubtitleStylePanel,
    PlayerEvent.OnShowSubtitleTimingDialog,
    PlayerEvent.OnShowSubtitleDelayOverlay,
    PlayerEvent.OnToggleAspectRatio -> false
    else -> true
}
