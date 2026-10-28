package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test
class CinemaPlaybackPolicyTest {
    @Test fun onlyOurLoopbackProviderUsesTheSourceTimeout() {
        assertTrue(CinemaPlaybackPolicy.isProvider("http://127.0.0.1:18991/play/token/title/ep/q/voice/video.mkv"))
        assertFalse(CinemaPlaybackPolicy.isProvider("https://example.org/play/file"))
        assertFalse(CinemaPlaybackPolicy.isProvider("http://127.0.0.1:18990/stream/video"))
    }
    @Test fun unknownDurationStartupAndLaterStallsHaveFiniteBudgetsButPausingDoesNotFail() {
        assertFalse(CinemaPlaybackPolicy.timedOut(59_999, 59_999, false, false))
        assertTrue(CinemaPlaybackPolicy.timedOut(60_000, 1, false, false))
        assertTrue(CinemaPlaybackPolicy.timedOut(40_000, 30_000, true, false))
        assertFalse(CinemaPlaybackPolicy.timedOut(80_000, 80_000, false, true))
        assertFalse(CinemaPlaybackPolicy.timedOut(80_000, 100, true, false))
    }
}
