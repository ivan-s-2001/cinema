package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test

class CinemaPlayerActionsTest {
    @Test fun essentialPlaybackAndSelectionRemainAvailable() {
        listOf(PlayerEvent.OnPlayPause, PlayerEvent.OnSeekForward, PlayerEvent.OnSeekBackward,
            PlayerEvent.OnSeekTo(60000), PlayerEvent.OnShowEpisodesPanel, PlayerEvent.OnShowSourcesPanel,
            PlayerEvent.OnDismissEpisodesPanel, PlayerEvent.OnDismissSourcesPanel,
            PlayerEvent.OnPlayNextEpisode, PlayerEvent.OnShowMoreDialog, PlayerEvent.OnShowSpeedDialog,
            PlayerEvent.OnShowSubtitleOverlay, PlayerEvent.OnToggleAspectRatio,
            PlayerEvent.OnShowChaptersPanel, PlayerEvent.OnSelectChapter(0)).forEach { assertTrue(it.toString(), cinemaPlayerActionAllowed(it)) }
    }
    @Test fun remoteShortcutsCannotOpenRemovedMenus() {
        listOf(PlayerEvent.OnShowAudioOverlay, PlayerEvent.OnSelectAudioTrack(1), PlayerEvent.OnShowStreamInfo).forEach { assertFalse(it.toString(), cinemaPlayerActionAllowed(it)) }
    }
}
