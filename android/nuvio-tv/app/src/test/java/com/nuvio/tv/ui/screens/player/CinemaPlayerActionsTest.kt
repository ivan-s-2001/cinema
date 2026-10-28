package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test

class CinemaPlayerActionsTest {
    @Test fun essentialPlaybackAndSelectionRemainAvailable() {
        listOf(PlayerEvent.OnPlayPause, PlayerEvent.OnSeekForward, PlayerEvent.OnSeekBackward,
            PlayerEvent.OnSeekTo(60000), PlayerEvent.OnShowEpisodesPanel, PlayerEvent.OnShowSourcesPanel,
            PlayerEvent.OnDismissEpisodesPanel, PlayerEvent.OnDismissSourcesPanel,
            PlayerEvent.OnPlayNextEpisode).forEach { assertTrue(it.toString(), cinemaPlayerActionAllowed(it)) }
    }
    @Test fun remoteShortcutsCannotOpenRemovedMenus() {
        listOf(PlayerEvent.OnShowMoreDialog, PlayerEvent.OnShowSpeedDialog, PlayerEvent.OnShowStreamInfo,
            PlayerEvent.OnShowAudioOverlay, PlayerEvent.OnShowSubtitleOverlay, PlayerEvent.OnShowChaptersPanel,
            PlayerEvent.OnShowPartyPanel, PlayerEvent.OnToggleAspectRatio).forEach { assertFalse(it.toString(), cinemaPlayerActionAllowed(it)) }
    }
}
