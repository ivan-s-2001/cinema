package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.data.mapper.toDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
    @Test fun `release technology survives mapping and distinguishes identical voices`() {
        val dto = com.nuvio.tv.data.remote.dto.StreamDto(name = "CinemaHall · 4K", title = "Дубляж", technologyHints = listOf("HDR10+", "Dolby Atmos", "invalid"))
        val mapped = dto.toDomain("CinemaHall", null)
        assertEquals(listOf("HDR10+", "Dolby Atmos"), mapped.technologyHints)
        val other = mapped.copy(technologyHints = listOf("Dolby Vision"))
        assertEquals(2, cinemaVoiceChoices(listOf(mapped, other)).size)
        assertEquals("HDR10+ · Dolby Atmos", cinemaTechnologyLabel(mapped))
    }
    @Test fun `new film and episode launches require a choice before any autoplay`() {
        assertTrue(cinemaRequiresInitialSelection("ch:snapshot:movie:tmdb:550", false))
        assertTrue(cinemaRequiresInitialSelection("ch:snapshot:tv:tmdb:1622:5:3", false))
        assertTrue(cinemaRequiresInitialSelection("tt12345", true))
        assertFalse(cinemaRequiresInitialSelection("tt12345", false))
        assertEquals(listOf("CinemaHall · 1080p"), cinemaSelectableStreams(listOf(stream("Авто"), stream("1080p"))).map { it.name })
    }
    @Test fun `next episode keeps the exact chosen voice and quality or returns to selection`() {
        val picked = stream("1080p").copy(behaviorHints = com.nuvio.tv.domain.model.StreamBehaviorHints(notWebReady = null, bingeGroup = "cinemahall:1080p:lostfilm", countryWhitelist = null, proxyHeaders = null))
        assertEquals(picked, cinemaContinuationStream(listOf(stream("4K"), picked), "cinemahall:1080p:lostfilm"))
        assertNull(cinemaContinuationStream(listOf(stream("4K")), "cinemahall:1080p:lostfilm"))
        assertNull(cinemaContinuationStream(listOf(picked), null))
    }

}
