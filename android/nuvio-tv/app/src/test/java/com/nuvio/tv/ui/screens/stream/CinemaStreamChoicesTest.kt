package com.nuvio.tv.ui.screens.stream

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.torrent.TorrentSettings
import com.nuvio.tv.core.torrent.TorrentSettingsData
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.mediaserver.ServerStreams
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CinemaStreamChoicesTest {
    @Test fun progressiveResultsExposeEveryVoiceBeyondTheOldPageLimitWithoutAutoplay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val streams = (1..205).map { index -> Stream(
                name = "CinemaHall · ${if (index <= 100) "1080p" else "4K"}",
                title = "Студия $index", description = null,
                url = "http://127.0.0.1/video/$index", ytId = null, infoHash = null,
                fileIdx = null, externalUrl = null, behaviorHints = null,
                addonName = "CinemaHall", addonLogo = null
            ) }
            fun groups(items: List<Stream>) = listOf(AddonStreams("CinemaHall", null, items))
            val results = MutableStateFlow<NetworkResult<List<AddonStreams>>>(
                NetworkResult.Success(groups(streams.take(50)))
            )
            val viewModel = StreamScreenViewModel(
                context = mockk(relaxed = true),
                streamRepository = mockk<StreamRepository>(relaxed = true) {
                    every { getStreamsFromAllAddons(any(), any(), any(), any(), any()) } returns results
                },
                addonRepository = mockk(),
                metaRepository = mockk<MetaRepository> {
                    every { getMetaFromAllAddons(any(), any()) } returns flowOf(NetworkResult.Error("No metadata"))
                },
                playerSettingsDataStore = mockk<PlayerSettingsDataStore> {
                    every { playerSettings } returns flowOf(PlayerSettings())
                },
                streamLinkCacheDataStore = mockk(), bingeGroupCacheDataStore = mockk(),
                torrentSettings = mockk<TorrentSettings> { every { settings } returns flowOf(TorrentSettingsData()) },
                watchProgressRepository = mockk<WatchProgressRepository> {
                    every { getProgress(any(), any()) } returns flowOf(null)
                },
                trackingScrobbleCoordinator = mockk(), directDebridResolver = mockk(),
                directDebridStreamPreparer = mockk(), youTubeStreamResolver = mockk(),
                externalPlaybackTracker = mockk(relaxed = true), subtitleRepository = mockk(),
                subtitleFileCache = mockk(), torrentService = mockk(),
                serverStreams = mockk<ServerStreams> {
                    every { isNativeRequest(any()) } returns true
                    every { preferredSourceNames(any(), any()) } returns emptySet()
                },
                serverPlayback = mockk(), profileManager = mockk(),
                savedStateHandle = SavedStateHandle(mapOf(
                    "videoId" to "ch:choices", "contentType" to "movie", "profileId" to "1"
                ))
            )
            store.put("choices", viewModel)
            runCurrent()
            assertEquals(50, viewModel.uiState.value.allStreams.size)

            results.value = NetworkResult.Success(groups(streams))
            runCurrent()
            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertNull(state.error)
            assertEquals(streams, state.allStreams)
            assertEquals(205, cinemaVoiceChoices(cinemaSelectableStreams(state.allStreams)).size)
            assertEquals("Студия 205", cinemaSelectableStreams(state.allStreams).last().title)
            assertNull(state.autoPlayStream)
            assertNull(state.autoPlayPlaybackInfo)
            assertFalse(state.showDirectAutoPlayOverlay)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
