package com.nuvio.tv.ui.screens.search

import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class CinemaDiscoveryTest {
    @Test fun searchUsesTheWholeDatabaseWithIndependentFiltersAndCursor() {
        val path = cinemaDiscoverPath("  Кейдж & кино  ", "", "драма", "2000", "США", "rating", "a+b=c")
        assertTrue(path.startsWith("/discover?"))
        val params = path.substringAfter('?').split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        assertEquals("Кейдж & кино", params["q"])
        assertEquals("драма", params["genre"])
        assertEquals("США", params["country"])
        assertEquals("2000", params["yearFrom"])
        assertEquals("2000", params["yearTo"])
        assertEquals("a+b=c", params["cursor"])
        assertFalse(params.containsKey("type"))
        assertFalse(params.containsKey("catalog"))
    }
    @Test fun searchLeavesTheSelectedShelfAndQueriesAllTitles() {
        val path = cinemaDiscoverPath("Матрица", "", "", "", "", "popular", null, "comedy")
        assertTrue(path.startsWith("/discover?"))
        assertFalse(path.contains("comedy"))
        assertTrue(cinemaDiscoverPath("", "", "", "", "", "popular", null, "comedy").startsWith("/rows/comedy/items?"))
    }
    @Test fun emptyFiltersDoNotRestrictTheCatalogue() {
        assertEquals("/discover?limit=30&sort=popular", cinemaDiscoverPath("", "", "", "", "", "popular", null))
    }
}
