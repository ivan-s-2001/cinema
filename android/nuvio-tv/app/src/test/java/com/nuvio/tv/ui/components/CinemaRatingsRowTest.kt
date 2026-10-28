package com.nuvio.tv.ui.components
import com.nuvio.tv.domain.model.CinemaRating
import org.junit.Assert.assertEquals
import org.junit.Test
class CinemaRatingsRowTest {
    @Test fun `server score retains its source and original scale`() {
        assertEquals("TMDB 8.2/10", cinemaRatingLabel(CinemaRating("tmdb", "TMDB", 8.2, 10)))
        assertEquals("AniList 85.0/100", cinemaRatingLabel(CinemaRating("anilist", "AniList", 85.0, 100)))
    }
}
