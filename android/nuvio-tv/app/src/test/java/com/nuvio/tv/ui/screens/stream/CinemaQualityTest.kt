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

    @Test fun `release technology survives mapping and distinguishes identical voices`() {
        val dto = com.nuvio.tv.data.remote.dto.StreamDto(name = "CinemaHall · 4K", title = "Дубляж", technologyHints = listOf("HDR10+", "Dolby Atmos", "invalid"))
        val mapped = dto.toDomain("CinemaHall", null)
        assertEquals(listOf("HDR10+", "Dolby Atmos"), mapped.technologyHints)
        val other = mapped.copy(technologyHints = listOf("Dolby Vision"))
        assertEquals(2, cinemaVoiceChoices(listOf(mapped, other)).size)
        assertEquals("HDR10+ · Dolby Atmos", cinemaTechnologyLabel(mapped))
    }
    @Test fun `play starts automatically and source choices are optional`() {
        assertFalse(cinemaRequiresInitialSelection(false))
        assertTrue(cinemaRequiresInitialSelection(true))
        assertTrue(cinemaShouldReuseBingeGroup("ch:snapshot:tv:tmdb:1622:5:3", false, false))
        assertFalse(cinemaShouldReuseBingeGroup("tt12345", false, true))
        assertTrue(cinemaShouldReuseBingeGroup("tt12345", true, true))
        assertEquals(listOf("CinemaHall · 1080p"), cinemaSelectableStreams(listOf(stream("Авто"), stream("1080p"))).map { it.name })
    }

    @Test fun `one click uses auto release while next episode keeps preferred voice`() {
        val auto = stream("Авто").copy(behaviorHints = com.nuvio.tv.domain.model.StreamBehaviorHints(
            notWebReady = null, bingeGroup = "cinemahall:auto", countryWhitelist = null, proxyHeaders = null))
        val preferred = stream("1080p").copy(behaviorHints = com.nuvio.tv.domain.model.StreamBehaviorHints(
            notWebReady = null, bingeGroup = "cinemahall:1080p:lostfilm", countryWhitelist = null, proxyHeaders = null))
        val streams = listOf(auto, preferred)
        assertEquals(auto, cinemaPreferredAutoPlayStream(streams, null))
        assertEquals(preferred, cinemaPreferredAutoPlayStream(streams, "cinemahall:1080p:lostfilm"))
        assertEquals(auto, cinemaPreferredAutoPlayStream(streams, "cinemahall:missing"))
        assertEquals(preferred, cinemaPreferredAutoPlayStream(listOf(preferred), null))
        assertNull(cinemaPreferredAutoPlayStream(listOf(stream("4K", addon = "Other")), null))
    }
    @Test fun `next episode keeps the exact chosen voice and quality or returns to selection`() {
        val picked = stream("1080p").copy(behaviorHints = com.nuvio.tv.domain.model.StreamBehaviorHints(notWebReady = null, bingeGroup = "cinemahall:1080p:lostfilm", countryWhitelist = null, proxyHeaders = null))
        assertEquals(picked, cinemaContinuationStream(listOf(stream("4K"), picked), "cinemahall:1080p:lostfilm"))
        assertNull(cinemaContinuationStream(listOf(stream("4K")), "cinemahall:1080p:lostfilm"))
        assertNull(cinemaContinuationStream(listOf(picked), null))
    }

    @Test fun `progressive results retain a voice identity through inserts and label updates`() {
        val lostFilm = stream("1080p").copy(title = "Lost Film", behaviorHints = com.nuvio.tv.domain.model.StreamBehaviorHints(
            notWebReady = null, bingeGroup = "cinemahall:1080p:lostfilm", countryWhitelist = null, proxyHeaders = null))
        val renamed = lostFilm.copy(title = "LostFilm, многоголосый")
        val other = stream("1080p").copy(title = "Другая студия")
        assertEquals(cinemaVoiceChoiceKey(lostFilm), cinemaVoiceChoiceKey(renamed))
        assertEquals(2, cinemaVoiceChoices(listOf(other, lostFilm, renamed)).size)
        assertEquals(2, listOf(other, lostFilm).map(::cinemaVoiceChoiceKey).toSet().size)
    }

}
