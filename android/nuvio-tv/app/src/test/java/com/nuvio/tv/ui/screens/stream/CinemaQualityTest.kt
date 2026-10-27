package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CinemaQualityTest {
    private fun stream(quality: String, addon: String = "CinemaHall") = Stream(
        name = "CinemaHall · $quality", title = "Дубляж", description = null,
        url = "http://127.0.0.1/video", ytId = null, infoHash = null, fileIdx = null,
        externalUrl = null, behaviorHints = null, addonName = addon, addonLogo = null
    )

    @Test fun `selects preferred quality with fallback and ignores third parties`() {
        val choices = listOf(stream("4K"), stream("720p"), stream("1080p", "Other"))
        assertEquals("CinemaHall · 4K", cinemaPreferredStream(choices, "BEST")?.name)
        assertEquals("CinemaHall · 720p", cinemaPreferredStream(choices, "1080p")?.name)
        assertEquals("CinemaHall · 720p", cinemaPreferredStream(choices, "480p")?.name)
        assertNull(cinemaPreferredStream(listOf(stream("4K", "Other")), "BEST"))
    }
}
