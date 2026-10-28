package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test
class CinemaPlaybackPolicyTest {
    @Test fun studioSelectionRequiresOneMatchingContainerLabelAndNeverGuessesFromLanguage() {
        val tracks = listOf(
            TrackInfo(0, "Русский (AAC 2.0)", "rus"),
            TrackInfo(1, "Lost Film (EAC3 5.1)", "rus"),
            TrackInfo(2, "Jaskier (AAC 2.0)", "rus")
        )
        assertEquals("lostfilm", cinemaSelectedVoice("cinemahall:1080p:lostfilm:tech:Dolby Vision"))
        assertEquals(1, cinemaMatchingAudioIndex(tracks, "lostfilm"))
        assertEquals(2, cinemaMatchingAudioIndex(tracks, "jaskier"))
        assertNull(cinemaMatchingAudioIndex(tracks, "дубляж"))
        assertNull(cinemaMatchingAudioIndex(tracks, "rus"))
        assertNull(cinemaMatchingAudioIndex(tracks + tracks[1].copy(index = 3), "lostfilm"))
        assertNull(cinemaMatchingAudioIndex(tracks, "lostfilm jaskier"))
        assertEquals("cinemahall:1080p:jaskier:tech:HDR", cinemaContinuationGroupForAudio("cinemahall:1080p:lostfilm:tech:HDR", tracks[2]))
        assertNull(cinemaContinuationGroupForAudio("cinemahall:1080p:lostfilm", tracks[0]))
        assertNull(cinemaContinuationGroupForAudio("cinemahall:1080p:lostfilm", TrackInfo(3, "Аудиодорожка 2", "rus")))
        assertNull(cinemaContinuationGroupForAudio(null, tracks[1]))
    }
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
    @Test fun sourceChangesUseTheLiveTimelineRatherThanStaleSavedProgress() {
        assertEquals(1_234_567L, cinemaSourceSwitchPosition(1_234_567L, true, 600_000L, 1_234_000L))
        assertEquals(1_234_567L, cinemaSourceSwitchPosition(0L, false, 1_234_567L, 0L))
        assertEquals(500_000L, cinemaSourceSwitchPosition(null, false, null, 500_000L))
        assertEquals(0L, cinemaSourceSwitchPosition(0L, true, 600_000L, 600_000L))
        assertEquals(3_590_000L, cinemaSourceSwitchTarget(3_590_000L, 3_600_000L))
        assertEquals(899_999L, cinemaSourceSwitchTarget(1_000_000L, 900_000L))
        assertEquals(1_000_000L, cinemaSourceSwitchTarget(1_000_000L, -1L))
    }

}
