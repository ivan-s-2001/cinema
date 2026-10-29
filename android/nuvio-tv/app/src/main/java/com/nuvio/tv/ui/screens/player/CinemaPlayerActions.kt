package com.nuvio.tv.ui.screens.player

/** Prevent remote shortcuts from reopening advanced menus removed from CinemaHall. */
internal fun cinemaPlayerActionAllowed(event: PlayerEvent): Boolean = when (event) {
    PlayerEvent.OnShowAudioOverlay,
    PlayerEvent.OnShowStreamInfo -> false
    else -> true
}
