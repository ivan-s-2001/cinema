package com.nuvio.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Two source rotations per playback URL; a manually changed embedded voice opens the chooser. */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.recoverCinemaSource(manual: Boolean = false): Boolean {
    if (isInBackground || isReleasingPlayer || (!manual && userPausedManually)) return false
    if (cinemaSourceRecoveryJob?.isActive == true) return true
    val previous = currentStreamUrl
    val group = currentStreamBingeGroup
    val next = CinemaPlaybackPolicy.recoveryUrl(previous, group) ?: return false
    val position = cinemaSourceSwitchPosition(currentPlaybackPositionMs(), hasRenderedFirstFrame,
        pendingSourceSwitchPositionMs ?: _uiState.value.pendingSeekPosition, playbackTimeline.value.currentPosition)
    val replacement = _uiState.value.sourceAllStreams.firstOrNull { it.behaviorHints?.bingeGroup == group }
        ?.copy(url = next) ?: Stream(name = _uiState.value.currentStreamName, title = null,
            description = currentStreamDescription, url = next, ytId = null, infoHash = null, fileIdx = null,
            externalUrl = null, behaviorHints = StreamBehaviorHints(null, group, null, null),
            addonName = "CinemaHall", addonLogo = null)
    stallWatchdogJob?.cancel()
    firstFrameWatchdogJob?.cancel()
    cinemaSourceRecoveryJob = scope.launch {
        // The old engine's error callback must finish before releasing that engine.
        yield()
        if (currentStreamUrl != previous || currentStreamBingeGroup != group || isInBackground ||
            (!manual && userPausedManually)) return@launch
        if (manual) userPausedManually = false
        switchToSourceStream(replacement, recoveryPositionMs = position)
    }
    return true
}
