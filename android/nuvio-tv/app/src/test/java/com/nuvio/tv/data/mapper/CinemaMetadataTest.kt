package com.nuvio.tv.data.mapper
import com.nuvio.tv.data.remote.dto.*
import org.junit.Assert.*
import org.junit.Test
class CinemaMetadataTest {
    @Test fun `server episodes actors related films and viewing order survive the TV adapter`() {
        val personId = "person:" + "a".repeat(32)
        val item = MetaPreviewDto(id = "ch:movie:2", type = "movie", name = "Next film")
        val dto = MetaDto(id = "ch:series:1", type = "series", name = "Series",
            videos = listOf(VideoDto(id = "ch:series:1:1:1", title = "First", season = 1, episode = 1), VideoDto(id = "ch:series:1:2:1", title = "Second season", season = 2, episode = 1)),
            appExtras = AppExtrasDto(cast = listOf(AppExtrasCastMemberDto(name = "Actor", photo = "https://cinemahome.duckdns.org/p.jpg", personId = personId))),
            cinemaRelated = listOf(item), cinemaWatchOrders = listOf(CinemaWatchOrderDto("cycle", "Cycle", "Chronology", 3, 1, listOf(item))))
        val meta = dto.toDomain()
        assertEquals(listOf(1, 2), meta.videos.map { it.season })
        assertEquals(personId, meta.castMembers.single().cinemaPersonId)
        assertEquals("ch:movie:2", meta.cinemaRelated.single().id)
        assertEquals(3, meta.cinemaWatchOrders.single().totalParts)
        assertEquals(1, meta.cinemaWatchOrders.single().items.size)
    }
}
