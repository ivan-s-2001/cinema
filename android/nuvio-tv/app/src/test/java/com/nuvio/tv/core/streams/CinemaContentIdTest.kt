package com.nuvio.tv.core.streams
import org.junit.Assert.assertEquals
import org.junit.Test
class CinemaContentIdTest {
    @Test fun `namespaced movie and series IDs survive playback metadata lookup`() {
        assertEquals("ch:snapshot:movie:tmdb:550", videoContentId("ch:snapshot:movie:tmdb:550"))
        assertEquals("ch:snapshot:tv:tmdb:1668", videoContentId("ch:snapshot:tv:tmdb:1668:1:2"))
        assertEquals("ch:snapshot:tv:tmdb:42:collision", videoContentId("ch:snapshot:tv:tmdb:42:collision:2:7"))
        assertEquals("tt1234567", videoContentId("tt1234567:1:2"))
    }
}
