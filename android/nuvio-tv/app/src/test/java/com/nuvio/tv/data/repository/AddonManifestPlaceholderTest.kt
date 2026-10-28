package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.core.streams.supportsStreamResource
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.data.remote.dto.AddonManifestDto
import com.nuvio.tv.data.remote.dto.CatalogDescriptorDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AddonManifestPlaceholderTest {
    private fun manifest() = AddonManifestDto(
        id = "app.cinemahall.catalog", name = "CinemaHall", version = "0.7.1",
        types = listOf("movie", "series"), resources = listOf("catalog", "meta", "stream"),
        idPrefixes = listOf("ch:"),
        catalogs = listOf(CatalogDescriptorDto(type = "movie", id = "popular-movies", name = "Фильмы",
            extraSupported = listOf("search", "skip"), pageSize = 24))
    )

    @Test fun `first consumer waits for provider and subsequent screens share manifest`() = runTest {
        val api = mockk<AddonApi>()
        var calls = 0
        coEvery { api.getManifest(any()) } coAnswers {
            calls++
            if (calls == 1) throw IOException("provider starting")
            Response.success(manifest())
        }
        val repository = AddonRepositoryImpl(api, mockk<Context>(relaxed = true), StandardTestDispatcher(testScheduler))
        val playback = async { repository.getInstalledAddons().first() }
        val search = async { repository.getInstalledAddons().first() }
        runCurrent()
        assertFalse("No empty placeholder may escape while the service starts", playback.isCompleted)
        advanceTimeBy(500)
        runCurrent()
        val addon = playback.await().single()
        assertEquals(addon, search.await().single())
        assertTrue(addon.supportsStreamResource("movie", "ch:snapshot:movie:tmdb:550"))
        assertTrue(addon.supportsStreamResource("series", "ch:snapshot:tv:tmdb:1668:1:1"))
        assertTrue(addon.catalogs.isNotEmpty())
        assertEquals(addon, repository.getInstalledAddons().first().single())
        coVerify(exactly = 2) { api.getManifest("http://127.0.0.1:18991/manifest.json") }
    }

    @Test fun `old external settings cannot remove or disable CinemaHall`() = runTest {
        val api = mockk<AddonApi>()
        coEvery { api.getManifest(any()) } returns Response.success(manifest())
        val repository = AddonRepositoryImpl(api, mockk<Context>(relaxed = true), StandardTestDispatcher(testScheduler))
        repository.removeAddon("http://127.0.0.1:18991")
        repository.setAddonEnabled("http://127.0.0.1:18991", false)
        assertTrue(repository.getInstalledAddons().first().single().enabled)
        repository.fetchAddon("https://external.invalid")
        coVerify(exactly = 1) { api.getManifest(any()) }
    }
}
