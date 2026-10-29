package com.nuvio.tv.ui.screens.player

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.debrid.DirectDebridPlayableResult
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.ui.screens.stream.cinemaContinuationStream
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.ui.components.SourceChipItem
import com.nuvio.tv.ui.components.SourceChipStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Hard ceiling for next-episode stream search to prevent hanging forever. */
private const val NEXT_EPISODE_HARD_TIMEOUT_MS = 120_000L
private const val CLOUD_LIBRARY_AUTO_NEXT_TIMEOUT_MS = 65_000L

/**
 * Schedules badge matching for episode streams in the background.
 */
internal fun PlayerRuntimeController.scheduleEpisodeBadgeApplication() {
    episodeBadgeJob?.cancel()
    episodeBadgeJob = scope.launch(kotlinx.coroutines.Dispatchers.Default) {
        val streams = _uiState.value.episodeAllStreams
        if (streams.isEmpty()) return@launch
        val group = com.nuvio.tv.domain.model.AddonStreams(addonName = "", addonLogo = null, streams = streams)
        val badged = streamBadgePresentation.apply(listOf(group))
        val badgedStreams = badged.flatMap { it.streams }
        if (badgedStreams == streams) return@launch
        _uiState.update { current ->
            val selectedAddon = current.episodeSelectedAddonFilter
            current.copy(
                episodeAllStreams = badgedStreams,
                episodeFilteredStreams = if (selectedAddon == null) badgedStreams else badgedStreams.filter { it.addonName == selectedAddon }
            )
        }
    }
}

internal fun PlayerRuntimeController.showEpisodesPanel() {
    _uiState.update {
        it.copy(
            showEpisodesPanel = true,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false
        )
    }

    val desiredSeason = currentSeason ?: _uiState.value.episodesSelectedSeason
    if (_uiState.value.episodesAll.isNotEmpty() && desiredSeason != null) {
        selectEpisodesSeason(desiredSeason)
    } else {
        loadEpisodesIfNeeded()
    }
}

private fun Stream.isReadyForDebridPreparation(): Boolean =
    getStreamUrl().isNullOrBlank() &&
        (isDirectDebrid() || (needsLocalDebridResolve() && debridCacheStatus?.state == StreamDebridCacheState.CACHED))

internal fun PlayerRuntimeController.showSourcesPanel() {
    _uiState.update {
        it.copy(
            showSourcesPanel = true,
            error = null,
            showLoadingOverlay = false,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            showEpisodesPanel = false,
            showEpisodeStreams = false
        )
    }
    loadSourceStreams(forceRefresh = false)
}

internal fun PlayerRuntimeController.buildSourceRequestKey(type: String, videoId: String, season: Int?, episode: Int?): String {
    return "$type|$videoId|${season ?: -1}|${episode ?: -1}"
}

private data class SourceStreamsRequest(
    val type: String,
    val videoId: String,
    val season: Int?,
    val episode: Int?
)

private fun PlayerRuntimeController.currentSourceStreamsRequest(): SourceStreamsRequest? =
    if (contentType in listOf("series", "tv") && currentSeason != null && currentEpisode != null) {
        SourceStreamsRequest(
            type = contentType ?: return null,
            videoId = currentVideoId ?: contentId ?: return null,
            season = currentSeason,
            episode = currentEpisode
        )
    } else {
        SourceStreamsRequest(
            type = contentType ?: "movie",
            videoId = contentId ?: return null,
            season = null,
            episode = null
        )
    }

internal fun PlayerRuntimeController.loadSourceStreams(forceRefresh: Boolean) {
    streamRepository.setLocalPluginSearchPaused(false)
    val request = currentSourceStreamsRequest() ?: return
    val type = request.type
    val vid = request.videoId
    val seasonArg = request.season
    val episodeArg = request.episode

    val requestKey = buildSourceRequestKey(type = type, videoId = vid, season = seasonArg, episode = episodeArg)
    val state = _uiState.value
    val hasCachedPayload = state.sourceAllStreams.isNotEmpty() || state.sourceStreamsError != null

    // Fully completed cache hit — nothing to do
    if (!forceRefresh && requestKey == sourceStreamsCacheRequestKey && hasCachedPayload && sourceStreamsFetchCompleted) {
        return
    }
    // Already loading the same request — don't restart
    if (!forceRefresh && state.isLoadingSourceStreams && requestKey == sourceStreamsCacheRequestKey) {
        return
    }

    val targetChanged = requestKey != sourceStreamsCacheRequestKey
    val isResume = !forceRefresh && !targetChanged && requestKey == sourceStreamsCacheRequestKey && hasCachedPayload && !sourceStreamsFetchCompleted
    sourceStreamsScope?.cancel()
    sourceStreamsJob = null
    val newScope = kotlinx.coroutines.CoroutineScope(scope.coroutineContext + kotlinx.coroutines.SupervisorJob())
    sourceStreamsScope = newScope
    sourceStreamsJob = newScope.launch {
        sourceStreamsCacheRequestKey = requestKey
        sourceStreamsFetchCompleted = false
        _uiState.update {
            it.copy(
                isLoadingSourceStreams = true,
                sourceStreamsError = null,
                sourceAllStreams = if (forceRefresh || targetChanged) emptyList() else it.sourceAllStreams,
            )
        }

        val installedAddons = streamAddonsFor(vid)
        val installedAddonOrder = installedAddons.map { it.displayName }
        val installedAddonNames = installedAddonOrder.toSet()
        val preferredServerNames = serverStreams.preferredSourceNames(type, vid)
        var debridPreparationLaunched = false

        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = vid,
            season = seasonArg,
            episode = episodeArg,
            forceRefresh = forceRefresh
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    val addonStreams = StreamAutoPlaySelector.orderAddonStreams(result.data, installedAddonOrder, preferredServerNames)
                    val allStreams = addonStreams.flatMap { it.streams }
                    _uiState.update {
                        // On resume, merge fresh results with any previously cached streams
                        val mergedAllStreams = if (isResume && it.sourceAllStreams.isNotEmpty()) {
                            mergeSourceStreams(it.sourceAllStreams, allStreams)
                        } else {
                            allStreams
                        }
                        it.copy(
                            isLoadingSourceStreams = false,
                            sourceAllStreams = mergedAllStreams,
                            sourceStreamsError = null
                        )
                    }
                    launchSourceDebridPreparationIfNeeded(
                        launched = debridPreparationLaunched,
                        streams = allStreams,
                        season = seasonArg,
                        episode = episodeArg,
                        installedAddonNames = installedAddonNames,
                    ) { debridPreparationLaunched = true }
                }

                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingSourceStreams = false,
                            sourceStreamsError = result.message
                        )
                    }
                }

                NetworkResult.Loading -> {
                    _uiState.update { it.copy(isLoadingSourceStreams = true) }
                }
            }
        }
        sourceStreamsFetchCompleted = true
    }
}

/**
 * Merge fresh stream results with previously cached streams.
 * Newer entries for the same stream (matched by addon + url/infoHash) replace older ones.
 */
private fun mergeSourceStreams(cached: List<Stream>, fresh: List<Stream>): List<Stream> {
    val merged = LinkedHashMap<String, Stream>()
    cached.forEach { stream -> merged[stream.mergeKey()] = stream }
    fresh.forEach { stream -> merged[stream.mergeKey()] = stream }
    return merged.values.toList()
}

private fun Stream.mergeKey(): String =
    infoHash?.lowercase()?.let { hash -> "$addonName|$hash:${fileIdx ?: ""}" }
        ?: "$addonName|${getStreamUrl() ?: externalUrl ?: ytId ?: "${name}:${title}"}"

private fun PlayerRuntimeController.launchSourceDebridPreparationIfNeeded(
    launched: Boolean,
    streams: List<Stream>,
    season: Int?,
    episode: Int?,
    installedAddonNames: Set<String>,
    markLaunched: () -> Unit
) {
    if (launched || streams.none { it.isReadyForDebridPreparation() }) {
        return
    }
    markLaunched()
    scope.launch {
        val playerSettings = playerSettingsDataStore.playerSettings.first()
        directDebridStreamPreparer.prepare(
            streams = streams,
            season = season,
            episode = episode,
            playerSettings = playerSettings,
            installedAddonNames = installedAddonNames
        ) { original, prepared ->
            replacePreparedSourceStream(original, prepared)
        }
    }
}

private fun PlayerRuntimeController.replacePreparedSourceStream(
    original: Stream,
    prepared: Stream
) {
    _uiState.update { state ->
        val updatedStreams = replacePreparedFlatStreams(
            streams = state.sourceAllStreams,
            original = original,
            prepared = prepared
        )
        if (updatedStreams == state.sourceAllStreams) {
            state
        } else {
            state.copy(
                sourceAllStreams = updatedStreams,
            )
        }
    }
}

internal fun PlayerRuntimeController.dismissSourcesPanel() {
    sourceStreamsScope?.cancel()
    sourceStreamsScope = null
    sourceStreamsJob = null
    streamRepository.setLocalPluginSearchPaused(true)
    _uiState.update {
        it.copy(
            showSourcesPanel = false,
            isLoadingSourceStreams = false
        )
    }
    scheduleHideControls()
}

private fun PlayerRuntimeController.mergeSourceChipStatuses(
    existing: List<SourceChipItem>,
    succeededNames: List<String>
): List<SourceChipItem> {
    if (succeededNames.isEmpty()) return existing
    if (existing.isEmpty()) {
        return succeededNames.distinct().map { SourceChipItem(it, SourceChipStatus.SUCCESS) }
    }

    val successSet = succeededNames.toSet()
    val updated = existing.map { chip ->
        if (chip.name in successSet) chip.copy(status = SourceChipStatus.SUCCESS) else chip
    }.toMutableList()

    val known = updated.map { it.name }.toSet()
    succeededNames.forEach { name ->
        if (name !in known) updated += SourceChipItem(name, SourceChipStatus.SUCCESS)
    }
    return updated
}

private suspend fun PlayerRuntimeController.updateEpisodeSourceChipsForFetchStart(
    type: String,
    videoId: String,
    installedAddons: List<com.nuvio.tv.domain.model.Addon>
) {
    val addonNames = installedAddons
        .filter { it.supportsStreamResourceForChip(type, videoId) }
        .map { it.displayName }

    val pluginNames = try {
        if (!serverStreams.isNativeRequest(videoId) && pluginManager.pluginsEnabled.first()) {
            val mediaType = when (type.lowercase()) {
                "series", "tv", "show" -> "tv"
                else -> type.lowercase()
            }
            val groupByRepository = pluginManager.groupStreamsByRepository.first()
            val scrapers = pluginManager.enabledScrapers.first()
                .filter { it.supportsType(mediaType) }
            if (groupByRepository) {
                val repositoriesById = pluginManager.repositories.first().associateBy { it.id }
                scrapers
                    .map { scraper ->
                        repositoriesById[scraper.repositoryId]?.name?.takeIf { it.isNotBlank() } ?: scraper.name
                    }
                    .distinct()
            } else {
                scrapers
                    .map { it.name }
                    .distinct()
            }
        } else {
            emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    val ordered = (serverSourceNames(type, videoId) + addonNames + pluginNames).distinct()
    _uiState.update {
        it.copy(
            episodeSourceChips = ordered.map { name -> SourceChipItem(name, SourceChipStatus.LOADING) }
        )
    }
}

private fun PlayerRuntimeController.markRemainingEpisodeSourceChipsAsError() {
    var markedAnyError = false
    _uiState.update { state ->
        if (!state.episodeSourceChips.any { it.status == SourceChipStatus.LOADING }) return@update state
        markedAnyError = true
        state.copy(
            episodeSourceChips = state.episodeSourceChips.map { chip ->
                if (chip.status == SourceChipStatus.LOADING) {
                    chip.copy(status = SourceChipStatus.ERROR)
                } else {
                    chip
                }
            }
        )
    }
    if (!markedAnyError) return

    scope.launch {
        delay(1600L)
        _uiState.update { state ->
            state.copy(
                episodeSourceChips = state.episodeSourceChips.filterNot { it.status == SourceChipStatus.ERROR }
            )
        }
    }
}

private fun com.nuvio.tv.domain.model.Addon.supportsStreamResourceForChip(type: String, videoId: String): Boolean {
    return resources.any { resource ->
        resource.name == "stream" &&
            (resource.types.isEmpty() || resource.types.any { it.equals(type, ignoreCase = true) }) &&
            run {
                val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
                    ?: idPrefixes.takeIf { it.isNotEmpty() }
                prefixes == null || prefixes.any { prefix -> videoId.startsWith(prefix) }
            }
    }
}

// The launch stream's filename belongs to that stream only, so a newly selected stream without a
// filename hint falls back to its own url rather than inheriting it.
internal fun streamFilenameFromUrl(url: String): String? =
    url.substringBefore('?').substringAfterLast('/', "")
        .takeIf { it.isNotBlank() && it.contains('.') }

private fun PlayerRuntimeController.applySelectedStreamState(
    stream: Stream,
    url: String,
    headers: Map<String, String>
) {
    val playbackRequest = PlayerMediaSourceFactory.normalizePlaybackRequest(url, headers)
    currentStreamUrl = playbackRequest.url
    currentHeaders = playbackRequest.headers
    currentFilename = stream.behaviorHints?.filename ?: streamFilenameFromUrl(playbackRequest.url)
    currentStreamResponseHeaders = stream.behaviorHints?.proxyHeaders?.response.orEmpty()
    currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
        url = playbackRequest.url,
        filename = currentFilename,
        responseHeaders = currentStreamResponseHeaders
    )
    // A source switch mints a new debrid link for the same file, and without a file index a
    // multi-file torrent could hand one file's bytes to another, so that case keys on the url.
    val fileIdx = stream.getEffectiveFileIdx()
    currentStreamCacheKey = stream.getEffectiveInfoHash()?.lowercase()?.takeIf { fileIdx != null }?.let { hash ->
        "$hash:$fileIdx"
    }
    parsingErrorProbeAttempted = false
    applyStreamMetadata(stream)
}

/**
 * Apply stream metadata that is common to both HTTP and torrent paths.
 * Ensures binge-group, addon info, and video hints are always set regardless
 * of stream type — critical for next-episode binge matching.
 */
private fun PlayerRuntimeController.applyStreamMetadata(stream: Stream) {
    currentStreamBingeGroup = stream.behaviorHints?.bingeGroup
    applyStreamAudioDelay(stream.behaviorHints?.audioDelayMs, stream.behaviorHints?.audioDelayLanguage)
    currentVideoHash = stream.behaviorHints?.videoHash
    currentVideoSize = stream.behaviorHints?.videoSize
    streamSubtitles = stream.subtitles
    currentAddonName = stream.addonName
    currentAddonLogo = stream.addonLogo
    currentStreamDescription = stream.description
    currentVideoCodec = null
    currentVideoWidth = null
    currentVideoHeight = null
    currentVideoBitrate = null
    persistBingeGroup(stream.behaviorHints?.bingeGroup, stream.behaviorHints?.filename, stream.infoHash)
}

// Persist binge group per content so subsequent episode plays
// (from CW, Details, or next-episode) can reuse the same source group.
private fun PlayerRuntimeController.persistBingeGroup(bingeGroup: String?, filename: String? = null, infoHash: String? = null) {
    val cid = contentId
    if (cid != null) {
        scope.launch(kotlinx.coroutines.NonCancellable) {
            bingeGroupCacheDataStore.replace(cid, bingeGroup, filename, infoHash)
        }
    }
}

/**
 * Moves the stream metadata onto the release an addon redirected playback to.
 *
 * Some addons fail over by redirecting the player to the next stream they listed, and the player
 * follows that silently, so without this the stream info, the filename hints and the saved link
 * keep describing the stream that never opened. The target is looked up
 * only among streams already in memory: a search started here would compete with playback.
 */
internal fun PlayerRuntimeController.handleStreamRedirect(requestedUrl: String, resolvedUrl: String) {
    scope.launch {
        if (requestedUrl != currentStreamUrl) return@launch
        val addonName = currentAddonName
        val target = findRedirectTargetStream(_uiState.value.sourceAllStreams, addonName, requestedUrl, resolvedUrl)
            ?: currentSourceStreamsRequest()?.let { request ->
                streamRepository.getCachedStreamsFromAllAddons(
                    type = request.type,
                    videoId = request.videoId,
                    season = request.season,
                    episode = request.episode
                )?.let { cached ->
                    findRedirectTargetStream(cached.flatMap { it.streams }, addonName, requestedUrl, resolvedUrl)
                }
            }
        if (target == null) {
            Log.d(PlayerRuntimeController.TAG, "Stream redirect matched no listed stream")
            return@launch
        }
        // The lookup suspends, so the user may have switched streams meanwhile.
        if (requestedUrl != currentStreamUrl) return@launch
        Log.i(
            PlayerRuntimeController.TAG,
            "Stream redirected to another listed release, now showing ${target.name ?: target.addonName}"
        )
        currentFilename = target.behaviorHints?.filename
        currentVideoHash = target.behaviorHints?.videoHash
        currentVideoSize = target.behaviorHints?.videoSize
        currentStreamBingeGroup = target.behaviorHints?.bingeGroup
        currentAddonName = target.addonName
        currentAddonLogo = target.addonLogo
        currentStreamDescription = target.description
        persistBingeGroup(currentStreamBingeGroup)
        _uiState.update {
            it.copy(
                currentStreamName = target.name ?: target.addonName,
                currentStreamAddonName = target.addonName
            )
        }
        // The saved link points at the release that played, so a resume goes straight to it.
        val targetRequest = PlayerMediaSourceFactory.normalizePlaybackRequest(
            target.getStreamUrl() ?: resolvedUrl,
            currentHeaders
        )
        persistSelectedStreamForReuse(stream = target, url = targetRequest.url, headers = targetRequest.headers)
    }
}

private fun PlayerRuntimeController.persistSelectedStreamForReuse(
    stream: Stream,
    url: String,
    headers: Map<String, String>
) {
    if (!streamReuseLastLinkEnabled || stream.serverTarget != null) return

    val key = streamCacheKey ?: return
    val streamName = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName)?.takeIf { it.isNotBlank() }
        ?: title

    scope.launch {
        streamLinkCacheDataStore.save(
            contentKey = key,
            url = url,
            streamName = streamName,
            headers = headers,
            filename = currentFilename,
            videoHash = currentVideoHash,
            videoSize = currentVideoSize,
            bingeGroup = stream.behaviorHints?.bingeGroup,
            contentLanguage = contentLanguage,
            year = year,
            audioDelayMs = stream.behaviorHints?.audioDelayMs,
            audioDelayLanguage = stream.behaviorHints?.audioDelayLanguage
        )
    }
}

private fun PlayerRuntimeController.persistTorrentStreamForReuse(stream: Stream) {
    if (!streamReuseLastLinkEnabled) return

    val key = streamCacheKey ?: return
    val infoHash = stream.getEffectiveInfoHash() ?: return
    val streamName = (stream.name?.takeIf { it.isNotBlank() } ?: stream.addonName)?.takeIf { it.isNotBlank() }
        ?: title

    scope.launch {
        streamLinkCacheDataStore.save(
            contentKey = key,
            url = "",
            streamName = streamName,
            headers = emptyMap(),
            filename = stream.behaviorHints?.filename,
            videoHash = stream.behaviorHints?.videoHash,
            videoSize = stream.behaviorHints?.videoSize,
            infoHash = infoHash,
            fileIdx = stream.getEffectiveFileIdx(),
            sources = stream.sources,
            bingeGroup = stream.behaviorHints?.bingeGroup,
            contentLanguage = contentLanguage,
            year = year,
            audioDelayMs = stream.behaviorHints?.audioDelayMs,
            audioDelayLanguage = stream.behaviorHints?.audioDelayLanguage
        )
    }
}

private fun PlayerRuntimeController.openExternalStreamInBrowser(
    stream: Stream,
    fromEpisodePanel: Boolean
): Boolean {
    if (!stream.isExternal()) return false

    val externalUrl = stream.getStreamUrl()
    if (externalUrl.isNullOrBlank()) {
        _uiState.update {
            if (fromEpisodePanel) {
                it.copy(episodeStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_external_url))
            } else {
                it.copy(sourceStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_external_url))
            }
        }
        return true
    }

    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(externalUrl))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    runCatching {
        context.startActivity(browserIntent)
    }.onSuccess {
        _uiState.update {
            if (fromEpisodePanel) {
                it.copy(
                    showEpisodesPanel = false,
                    showEpisodeStreams = false,
                    isLoadingEpisodeStreams = false,
                    episodeStreamsError = null
                )
            } else {
                it.copy(
                    showSourcesPanel = false,
                    isLoadingSourceStreams = false,
                    sourceStreamsError = null
                )
            }
        }
    }.onFailure { error ->
        _uiState.update {
            if (fromEpisodePanel) {
                it.copy(episodeStreamsError = error.message ?: context.getString(com.nuvio.tv.R.string.player_stream_error_open_external_link_failed))
            } else {
                it.copy(sourceStreamsError = error.message ?: context.getString(com.nuvio.tv.R.string.player_stream_error_open_external_link_failed))
            }
        }
    }

    return true
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.switchToSourceStream(
    stream: Stream
) {
    sourceStreamsScope?.cancel()
    sourceStreamsScope = null
    sourceStreamsJob = null
    streamRepository.setLocalPluginSearchPaused(true)
    if (openExternalStreamInBrowser(stream = stream, fromEpisodePanel = false)) {
        return
    }

    if (stream.serverTarget != null && stream.getStreamUrl().isNullOrBlank()) {
        debridResolveJob?.cancel()
        _uiState.update { it.copy(isLoadingSourceStreams = true, sourceStreamsError = null) }
        debridResolveJob = scope.launch {
            val prepared = prepareServerStream(stream)
            debridResolveJob = null
            if (prepared != null) {
                switchToSourceStream(prepared)
            } else {
                _uiState.update { it.copy(isLoadingSourceStreams = false) }
            }
        }
        return
    }

    if (stream.youTubeIdToResolve() != null) {
        debridResolveJob?.cancel()
        _uiState.update { it.copy(isLoadingSourceStreams = true, sourceStreamsError = null) }
        debridResolveJob = scope.launch {
            val resolved = resolveYouTubeStream(stream)
            debridResolveJob = null
            if (resolved != null) {
                switchToSourceStream(resolved)
            } else {
                _uiState.update {
                    it.copy(
                        isLoadingSourceStreams = false,
                        sourceStreamsError = context.getString(com.nuvio.tv.R.string.youtube_resolution_failed)
                    )
                }
            }
        }
        return
    }

    if (stream.isTorrent()) {
        debridResolveJob?.cancel()
        _uiState.update { it.copy(isLoadingSourceStreams = true, sourceStreamsError = null) }
        debridResolveJob = scope.launch {
            val resolved = resolveDirectDebridStreamIfNeeded(stream, currentSeason, currentEpisode)
            debridResolveJob = null
            if (resolved != null && !resolved.getStreamUrl().isNullOrBlank()) {
                switchToSourceStream(resolved)
            } else if (resolved != null) {
                switchToTorrentSourceStream(resolved)
            } else {
                _uiState.update {
                    it.copy(
                        isLoadingSourceStreams = false,
                        sourceStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_url)
                    )
                }
            }
        }
        return
    }

    val url = stream.getStreamUrl()
    if (url.isNullOrBlank()) {
        if (stream.isDirectDebrid()) {
            debridResolveJob?.cancel()
            _uiState.update { it.copy(isLoadingSourceStreams = true, sourceStreamsError = null) }
            debridResolveJob = scope.launch {
                val resolved = resolveDirectDebridStreamIfNeeded(stream, currentSeason, currentEpisode)
                if (resolved != null && !resolved.getStreamUrl().isNullOrBlank()) {
                    debridResolveJob = null
                    switchToSourceStream(resolved)
                } else {
                    debridResolveJob = null
                    _uiState.update {
                        it.copy(
                            isLoadingSourceStreams = false,
                            sourceStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_url)
                        )
                    }
                }
            }
            return
        }
        _uiState.update { it.copy(sourceStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_url)) }
        return
    }

    val sourcePosition = cinemaSourceSwitchPosition(currentPlaybackPositionMs(), hasRenderedFirstFrame,
        pendingSourceSwitchPositionMs ?: _uiState.value.pendingSeekPosition, playbackTimeline.value.currentPosition)

    // Stop any active torrent before switching to HTTP stream
    stopTorrentStream()

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null

    flushPlaybackSnapshotForSwitchOrExit()

    val newHeaders = PlayerMediaSourceFactory.sanitizeHeaders(
        stream.behaviorHints?.proxyHeaders?.request
    )

    resetLoadingOverlayForNewStream()
    releasePlayer(flushPlaybackState = false)
    pendingResumeProgress = null
    pendingSourceSwitchPositionMs = sourcePosition
    _uiState.update { it.copy(pendingSeekPosition = sourcePosition) }
    updatePlaybackTimeline(currentPosition = sourcePosition)


    applySelectedStreamState(
        stream = stream,
        url = url,
        headers = newHeaders
    )
    val playbackUrl = currentStreamUrl
    val playbackHeaders = currentHeaders
    persistSelectedStreamForReuse(stream = stream, url = playbackUrl, headers = playbackHeaders)

    // Reset stream-state error flags for the new stream.
    hasRetriedCurrentStreamAfter416 = false
    resetErrorRetryState()
    hasRetriedCurrentStreamAfterUnexpectedNpe = false
    hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = false
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    lastSavedPosition = 0L
    _exoPlayer?.stop()
    resetLoadingOverlayForNewStream()

    _uiState.update {
        it.clearFrameRateDetection().copy(
            isBuffering = true,
            error = null,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = playbackUrl,
            currentStreamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash,
            currentStreamFileIdx = stream.clientResolve?.fileIdx,
            currentStreamAddonName = stream.addonName,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            chapters = emptyList(),
            showChaptersPanel = false,
            showSourcesPanel = false,
            isLoadingSourceStreams = false,
            sourceStreamsError = null,
            isTorrentStream = false
        )
    }
    showStreamSourceIndicator(stream)
    resetPostPlayOverlayState(clearEpisode = false)

    _exoPlayer?.let { player ->
        scope.launch {
            try {
                val playerSettings = playerSettingsDataStore.playerSettings.first()
                runAfrPreflightIfEnabled(
                    url = playbackUrl,
                    headers = playbackHeaders,
                    frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                    resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled,
                    mimeType = currentStreamMimeType
                )
                player.setMediaSource(
                    mediaSourceFactory.createMediaSource(
                        context = context,
                        url = playbackUrl,
                        headers = playbackHeaders,
                        filename = currentFilename,
                        responseHeaders = currentStreamResponseHeaders,
                        mimeTypeOverride = currentStreamMimeType,
                        audioDelayUsProvider = audioDelayUs::get,
                        cacheKey = currentStreamCacheKey
                    )
                )
                player.playWhenReady = true
                player.prepare()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: context.getString(com.nuvio.tv.R.string.player_error_play_stream_failed)) }
            }
        }
    } ?: run {
        initializePlayer(playbackUrl, playbackHeaders)
    }

    // The live position above is newer than an asynchronous database or server resume snapshot.

}

internal fun PlayerRuntimeController.dismissEpisodesPanel() {
    episodeStreamsScope?.cancel()
    episodeStreamsScope = null
    episodeStreamsJob = null
    streamRepository.setLocalPluginSearchPaused(true)
    _uiState.update {
        it.copy(
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false
        )
    }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.selectEpisodesSeason(season: Int) {
    val all = _uiState.value.episodesAll
    if (all.isEmpty()) return

    val seasons = _uiState.value.episodesAvailableSeasons
    if (seasons.isNotEmpty() && season !in seasons) return

    val episodesForSeason = all
        .filter { (it.season ?: -1) == season }
        .sortedWith(compareBy<Video> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })

    _uiState.update {
        it.copy(
            episodesSelectedSeason = season,
            episodes = episodesForSeason
        )
    }
    val titleId = contentId?.takeIf { it.startsWith("ch:") }
        ?: currentVideoId?.replace(Regex(":\\d+:\\d+$"), "")?.takeIf { it.startsWith("ch:") }
    if (titleId != null && season > 0) scope.launch {
        try {
            val images = com.nuvio.tv.data.remote.CinemaSeasonArtwork.load(titleId, season)
            _uiState.update { state ->
                val merged = state.episodesAll.map { com.nuvio.tv.data.remote.CinemaSeasonArtwork.merge(it, images) }
                state.copy(episodesAll = merged, episodes = merged.filter { it.season == state.episodesSelectedSeason }.sortedBy { it.episode })
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (failure: Exception) { android.util.Log.w("CinemaHall", "Player season artwork unavailable: $season", failure) }
    }

}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerRuntimeController.switchToTorrentSourceStream(
    stream: Stream
) {
    val infoHash = stream.getEffectiveInfoHash() ?: return
    val sourcePosition = cinemaSourceSwitchPosition(currentPlaybackPositionMs(), hasRenderedFirstFrame,
        pendingSourceSwitchPositionMs ?: _uiState.value.pendingSeekPosition, playbackTimeline.value.currentPosition)

    sourceStreamsScope?.cancel()
    sourceStreamsScope = null
    sourceStreamsJob = null
    stopTorrentStream()
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    flushPlaybackSnapshotForSwitchOrExit()
    resetLoadingOverlayForNewStream()
    releasePlayer(flushPlaybackState = false)
    pendingResumeProgress = null
    pendingSourceSwitchPositionMs = sourcePosition
    _uiState.update { it.copy(pendingSeekPosition = sourcePosition) }
    updatePlaybackTimeline(currentPosition = sourcePosition)

    hasRetriedCurrentStreamAfter416 = false
    errorRetryCount = 0
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    lastSavedPosition = 0L
    _uiState.update {
        it.clearFrameRateDetection().copy(
            isBuffering = true,
            error = null,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = "",
            currentStreamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash,
            currentStreamFileIdx = stream.clientResolve?.fileIdx,
            currentStreamAddonName = stream.addonName,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            chapters = emptyList(),
            showChaptersPanel = false,
            showSourcesPanel = false,
            isLoadingSourceStreams = false,
            sourceStreamsError = null,
            isTorrentStream = true
        )
    }
    applyStreamMetadata(stream)
    currentFilename = stream.behaviorHints?.filename
    showStreamSourceIndicator(stream)
    resetPostPlayOverlayState(clearEpisode = false)
    launchTorrentSourceStream(stream, infoHash, loadSavedProgress = false)
    persistTorrentStreamForReuse(stream)
}

private fun PlayerRuntimeController.switchToTorrentEpisodeStream(
    stream: Stream,
    forcedTargetVideo: Video?,
    isAutoPlay: Boolean
) {
    val infoHash = stream.getEffectiveInfoHash() ?: return
    consecutiveAutoPlayCount = nextConsecutiveAutoPlayCount(
        currentCount = consecutiveAutoPlayCount,
        isAutoPlay = isAutoPlay
    )
    stopTorrentStream()
    switchToEpisodeStreamCommon(stream, forcedTargetVideo)
    launchTorrentSourceStream(stream, infoHash, loadSavedProgress = true)
    persistTorrentStreamForReuse(stream)
}

internal fun PlayerRuntimeController.loadEpisodesIfNeeded() {
    val type = contentType
    val id = contentId
    if (type.isNullOrBlank() || id.isNullOrBlank()) return
    if (type !in listOf("series", "tv")) return
    if (_uiState.value.episodesAll.isNotEmpty() || _uiState.value.isLoadingEpisodes) return

    scope.launch {
        _uiState.update { it.copy(isLoadingEpisodes = true, episodesError = null) }

        when (
            val result = metaRepository.getMetaFromAllAddons(type = type, id = id)
                .first { it !is NetworkResult.Loading }
        ) {
            is NetworkResult.Success -> {
                val allEpisodes = result.data.videos
                    .sortedWith(
                        compareBy<Video> { it.season ?: Int.MAX_VALUE }
                            .thenBy { it.episode ?: Int.MAX_VALUE }
                            .thenBy { it.title }
                    )

                applyMetaDetails(result.data)

                val seasons = allEpisodes
                    .mapNotNull { it.season }
                    .distinct()
                    .sorted()

                val preferredSeason = when {
                    currentSeason != null && seasons.contains(currentSeason) -> currentSeason
                    initialSeason != null && seasons.contains(initialSeason) -> initialSeason
                    else -> seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 1
                }

                val selectedSeason = preferredSeason ?: 1
                val episodesForSeason = allEpisodes
                    .filter { (it.season ?: -1) == selectedSeason }
                    .sortedWith(compareBy<Video> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })

                _uiState.update {
                    it.copy(
                        isLoadingEpisodes = false,
                        episodesAll = allEpisodes,
                        episodesAvailableSeasons = seasons,
                        episodesSelectedSeason = selectedSeason,
                        episodes = episodesForSeason,
                        episodesError = null
                    )
                }
                selectEpisodesSeason(selectedSeason)
            }

            is NetworkResult.Error -> {
                _uiState.update { it.copy(isLoadingEpisodes = false, episodesError = result.message) }
            }

            NetworkResult.Loading -> {
            }
        }
    }
}

internal fun PlayerRuntimeController.loadStreamsForEpisode(video: Video) {
    loadStreamsForEpisode(video = video, forceRefresh = false)
}

internal fun PlayerRuntimeController.buildEpisodeRequestKey(type: String, video: Video): String {
    return "$type|${video.id}|${video.season ?: -1}|${video.episode ?: -1}"
}

internal fun PlayerRuntimeController.loadStreamsForEpisode(video: Video, forceRefresh: Boolean) {
    streamRepository.setLocalPluginSearchPaused(false)
    val type = contentType
    if (type.isNullOrBlank()) {
        _uiState.update { it.copy(episodeStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_missing_content_type)) }
        return
    }

    val requestKey = buildEpisodeRequestKey(type = type, video = video)
    val state = _uiState.value
    val hasCachedPayload = state.episodeAllStreams.isNotEmpty() || state.episodeStreamsError != null
    if (!forceRefresh && requestKey == episodeStreamsCacheRequestKey && hasCachedPayload && !video.id.startsWith("ch:")) {
        _uiState.update {
            it.copy(
                showEpisodeStreams = true,
                isLoadingEpisodeStreams = false,
                episodeStreamsForVideoId = video.id,
                episodeStreamsSeason = video.season,
                episodeStreamsEpisode = video.episode,
                episodeStreamsTitle = video.title
            )
        }
        return
    }

    val targetChanged = requestKey != episodeStreamsCacheRequestKey
    episodeStreamsScope?.cancel()
    episodeStreamsScope = null
    episodeStreamsJob = null
    val newScope = kotlinx.coroutines.CoroutineScope(scope.coroutineContext + kotlinx.coroutines.SupervisorJob())
    episodeStreamsScope = newScope
    episodeStreamsJob = newScope.launch {
        episodeStreamsCacheRequestKey = requestKey
        val previousAddonFilter = _uiState.value.episodeSelectedAddonFilter
        _uiState.update {
            it.copy(
                showEpisodeStreams = true,
                isLoadingEpisodeStreams = true,
                episodeStreamsError = null,
                episodeAllStreams = if (forceRefresh || targetChanged) emptyList() else it.episodeAllStreams,
                episodeSelectedAddonFilter = if (forceRefresh || targetChanged) null else it.episodeSelectedAddonFilter,
                episodeFilteredStreams = if (forceRefresh || targetChanged) emptyList() else it.episodeFilteredStreams,
                episodeAvailableAddons = if (forceRefresh || targetChanged) emptyList() else it.episodeAvailableAddons,
                episodeSourceChips = if (forceRefresh || targetChanged) emptyList() else it.episodeSourceChips,
                episodeStreamsForVideoId = video.id,
                episodeStreamsSeason = video.season,
                episodeStreamsEpisode = video.episode,
                episodeStreamsTitle = video.title
            )
        }

        val installedAddons = streamAddonsFor(video.id)
        val installedAddonOrder = installedAddons.map { it.displayName }
        val installedAddonNames = installedAddonOrder.toSet()
        val preferredServerNames = serverStreams.preferredSourceNames(type, video.id)
        var debridPreparationLaunched = false

        // Initialize episode source chips with LOADING status
        updateEpisodeSourceChipsForFetchStart(type, video.id, installedAddons)

        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = video.id,
            season = video.season,
            episode = video.episode,
            forceRefresh = forceRefresh
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    val addonStreams = StreamAutoPlaySelector.orderAddonStreams(result.data, installedAddonOrder, preferredServerNames)
                    val allStreams = addonStreams.flatMap { it.streams }
                    val availableAddons = addonStreams.map { it.addonName }
                    val currentFilter = _uiState.value.episodeSelectedAddonFilter
                    val filteredStreams = if (currentFilter == null) {
                        allStreams
                    } else {
                        allStreams.filter { it.addonName == currentFilter }
                    }
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodeStreams = false,
                            episodeAllStreams = allStreams,
                            episodeFilteredStreams = filteredStreams,
                            episodeAvailableAddons = availableAddons,
                            episodeSourceChips = mergeSourceChipStatuses(
                                existing = it.episodeSourceChips,
                                succeededNames = addonStreams.map { group -> group.addonName }
                            ),
                            episodeStreamsError = null
                        )
                    }
                    launchEpisodeDebridPreparationIfNeeded(
                        launched = debridPreparationLaunched,
                        streams = allStreams,
                        season = video.season,
                        episode = video.episode,
                        installedAddonNames = installedAddonNames,
                    ) { debridPreparationLaunched = true }
                    scheduleEpisodeBadgeApplication()
                }

                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodeStreams = false,
                            episodeStreamsError = result.message
                        )
                    }
                }

                NetworkResult.Loading -> {
                    _uiState.update { it.copy(isLoadingEpisodeStreams = true) }
                }
            }
        }
        markRemainingEpisodeSourceChipsAsError()
    }
}

private fun PlayerRuntimeController.launchEpisodeDebridPreparationIfNeeded(
    launched: Boolean,
    streams: List<Stream>,
    season: Int?,
    episode: Int?,
    installedAddonNames: Set<String>,
    markLaunched: () -> Unit
) {
    if (launched || streams.none { it.isReadyForDebridPreparation() }) {
        return
    }
    markLaunched()
    scope.launch {
        val playerSettings = playerSettingsDataStore.playerSettings.first()
        directDebridStreamPreparer.prepare(
            streams = streams,
            season = season,
            episode = episode,
            playerSettings = playerSettings,
            installedAddonNames = installedAddonNames
        ) { original, prepared ->
            replacePreparedEpisodeStream(original, prepared)
        }
    }
}

private fun PlayerRuntimeController.replacePreparedEpisodeStream(
    original: Stream,
    prepared: Stream
) {
    _uiState.update { state ->
        val updatedStreams = replacePreparedFlatStreams(
            streams = state.episodeAllStreams,
            original = original,
            prepared = prepared
        )
        if (updatedStreams == state.episodeAllStreams) {
            state
        } else {
            val selectedAddon = state.episodeSelectedAddonFilter
            state.copy(
                episodeAllStreams = updatedStreams,
                episodeFilteredStreams = updatedStreams.filterByAddon(selectedAddon)
            )
        }
    }
}

private fun PlayerRuntimeController.replacePreparedFlatStreams(
    streams: List<Stream>,
    original: Stream,
    prepared: Stream
): List<Stream> {
    if (streams.isEmpty()) return streams
    return directDebridStreamPreparer.replacePreparedStream(
        groups = listOf(
            AddonStreams(
                addonName = "",
                addonLogo = null,
                streams = streams
            )
        ),
        original = original,
        prepared = prepared
    ).firstOrNull()?.streams ?: streams
}

private fun List<Stream>.filterByAddon(addonName: String?): List<Stream> =
    if (addonName == null) {
        this
    } else {
        filter { it.addonName == addonName }
    }

internal fun PlayerRuntimeController.reloadEpisodeStreams() {
    val state = _uiState.value
    val targetVideoId = state.episodeStreamsForVideoId
    val targetVideo = sequenceOf(
        state.episodes.firstOrNull { it.id == targetVideoId },
        state.episodesAll.firstOrNull { it.id == targetVideoId },
        state.episodes.firstOrNull {
            it.season == state.episodeStreamsSeason && it.episode == state.episodeStreamsEpisode
        },
        state.episodesAll.firstOrNull {
            it.season == state.episodeStreamsSeason && it.episode == state.episodeStreamsEpisode
        }
    ).firstOrNull { it != null }

    if (targetVideo != null) {
        loadStreamsForEpisode(video = targetVideo, forceRefresh = true)
    }
}

internal fun PlayerRuntimeController.switchToEpisodeStream(
    stream: Stream,
    forcedTargetVideo: Video? = null,
    isAutoPlay: Boolean = false
) {
    val selectedEpisodeId = forcedTargetVideo?.id ?: _uiState.value.episodeStreamsForVideoId
    if (selectedEpisodeId != null && selectedEpisodeId == currentVideoId) {
        episodeStreamsScope?.cancel()
        episodeStreamsScope = null
        episodeStreamsJob = null
        _uiState.update { it.copy(showEpisodesPanel = false, showEpisodeStreams = false, isLoadingEpisodeStreams = false) }
        switchToSourceStream(stream)
        return
    }
    if (pendingSourceSwitchPositionMs != null) clearPendingInitialResumePosition()
    if (openExternalStreamInBrowser(stream = stream, fromEpisodePanel = true)) {
        return
    }

    if (stream.serverTarget != null && stream.getStreamUrl().isNullOrBlank()) {
        debridResolveJob?.cancel()
        _uiState.update { it.copy(isLoadingEpisodeStreams = true, episodeStreamsError = null) }
        debridResolveJob = scope.launch {
            val prepared = prepareServerStream(stream)
            debridResolveJob = null
            if (prepared != null) {
                switchToEpisodeStream(prepared, forcedTargetVideo, isAutoPlay)
            } else {
                _uiState.update { it.copy(isLoadingEpisodeStreams = false) }
            }
        }
        return
    }

    if (stream.youTubeIdToResolve() != null) {
        debridResolveJob?.cancel()
        _uiState.update { it.copy(isLoadingEpisodeStreams = true, episodeStreamsError = null) }
        debridResolveJob = scope.launch {
            val resolved = resolveYouTubeStream(stream)
            debridResolveJob = null
            if (resolved != null) {
                switchToEpisodeStream(resolved, forcedTargetVideo, isAutoPlay)
            } else {
                _uiState.update {
                    it.copy(
                        isLoadingEpisodeStreams = false,
                        episodeStreamsError = context.getString(com.nuvio.tv.R.string.youtube_resolution_failed)
                    )
                }
            }
        }
        return
    }

    if (stream.isTorrent()) {
        val resolveSeason = forcedTargetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
        val resolveEpisode = forcedTargetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
        debridResolveJob?.cancel()
        _uiState.update { it.copy(isLoadingEpisodeStreams = true, episodeStreamsError = null) }
        debridResolveJob = scope.launch {
            val resolved = resolveDirectDebridStreamIfNeeded(stream, resolveSeason, resolveEpisode)
            debridResolveJob = null
            if (resolved != null && !resolved.getStreamUrl().isNullOrBlank()) {
                switchToEpisodeStream(resolved, forcedTargetVideo, isAutoPlay)
            } else if (resolved != null) {
                switchToTorrentEpisodeStream(resolved, forcedTargetVideo, isAutoPlay)
            } else {
                _uiState.update {
                    it.copy(
                        isLoadingEpisodeStreams = false,
                        episodeStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_url)
                    )
                }
            }
        }
        return
    }

    val url = stream.getStreamUrl()
    if (url.isNullOrBlank()) {
        if (stream.isDirectDebrid()) {
            val resolveSeason = forcedTargetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
            val resolveEpisode = forcedTargetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
            debridResolveJob?.cancel()
            _uiState.update { it.copy(isLoadingEpisodeStreams = true, episodeStreamsError = null) }
            debridResolveJob = scope.launch {
                val resolved = resolveDirectDebridStreamIfNeeded(stream, resolveSeason, resolveEpisode)
                if (resolved != null && !resolved.getStreamUrl().isNullOrBlank()) {
                    debridResolveJob = null
                    switchToEpisodeStream(resolved, forcedTargetVideo, isAutoPlay)
                } else {
                    debridResolveJob = null
                    _uiState.update {
                        it.copy(
                            isLoadingEpisodeStreams = false,
                            episodeStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_url)
                        )
                    }
                }
            }
            return
        }
        _uiState.update { it.copy(episodeStreamsError = context.getString(com.nuvio.tv.R.string.player_stream_error_invalid_url)) }
        return
    }

    consecutiveAutoPlayCount = nextConsecutiveAutoPlayCount(
        currentCount = consecutiveAutoPlayCount,
        isAutoPlay = isAutoPlay,
    )

    // Stop any active torrent before switching to HTTP stream
    stopTorrentStream()

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null

    flushPlaybackSnapshotForSwitchOrExit()

    // Pause current playback immediately so the old stream doesn't continue
    // playing audio/video in the background while the new episode is being prepared.
    _exoPlayer?.stop()
    cancelAfrPreflight()

    val newHeaders = PlayerMediaSourceFactory.sanitizeHeaders(
        stream.behaviorHints?.proxyHeaders?.request
    )
    val targetVideo = forcedTargetVideo
        ?: _uiState.value.episodes.firstOrNull { it.id == _uiState.value.episodeStreamsForVideoId }

    currentStreamBingeGroup = stream.behaviorHints?.bingeGroup
    currentVideoHash = stream.behaviorHints?.videoHash
    currentVideoSize = stream.behaviorHints?.videoSize
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    attachedAddonSubtitleKeys = emptySet()

    applySelectedStreamState(
        stream = stream,
        url = url,
        headers = newHeaders
    )
    val playbackUrl = currentStreamUrl
    val playbackHeaders = currentHeaders
    val targetVideoId = targetVideo?.id ?: _uiState.value.episodeStreamsForVideoId ?: currentVideoId
    // Do not persist an episode switch under the outgoing episode's reuse key.
    if (targetVideoId == currentVideoId) {
        persistSelectedStreamForReuse(stream = stream, url = playbackUrl, headers = playbackHeaders)
    }
    persistedTrackPreference = null
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    hasRetriedCurrentStreamAfter416 = false
    resetErrorRetryState()
    currentVideoId = targetVideoId
    currentSeason = targetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
    currentEpisode = targetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
    currentEpisodeTitle = targetVideo?.title ?: _uiState.value.episodeStreamsTitle ?: currentEpisodeTitle
    // Until the new file loads, MPV keeps reporting the old one, which is often at its end.
    hasRenderedFirstFrame = false
    mpvView?.markMediaRequested(playbackUrl)
    endDetectionArmed = false
    mpvEofSeenClear = false
    currentTraktEpisodeMapping = null
    currentTraktEpisodeMappingKey = null
    lastSavedPosition = 0L

    _uiState.update {
        it.clearFrameRateDetection().copy(
            isBuffering = true,
            error = null,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentVideoId = currentVideoId,
            currentEpisodeTitle = currentEpisodeTitle,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = playbackUrl,
            currentStreamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash,
            currentStreamFileIdx = stream.clientResolve?.fileIdx,
            currentStreamAddonName = stream.addonName,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            chapters = emptyList(),
            showChaptersPanel = false,
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false,
            episodeStreamsError = null,
            isTorrentStream = false,

            parentalWarnings = emptyList(),
            showParentalGuide = false,
            parentalGuideHasShown = false,

            activeSkipInterval = null,
            skipIntervalDismissed = false,
            postPlayMode = null,
            postPlayDismissedForCurrentEpisode = true,
            playbackEnded = false,
            isNextEpisodeMetadataResolved = false,
        )
    }
    showStreamSourceIndicator(stream)
    recomputeNextEpisode(resetVisibility = true)

    updateEpisodeDescription()

    playbackStartedForParentalGuide = false
    skipIntervals = emptyList()
    skipIntroFetchedKey = null
    lastActiveSkipType = null
    autoSkippedIntervalKeys.clear()

    fetchParentalGuide(contentId, contentType, currentSeason, currentEpisode)
    fetchSkipIntervals(contentId, currentSeason, currentEpisode)

    queuePlaybackRawEventLine(
        "LINK_SELECTED: source=in_player_source host=${playbackUrl.safeStreamTraceHost()} " +
            "streamName=${stream.name} addon=${stream.addonName} " +
            "contentId=${contentId ?: "n/a"} videoId=${currentVideoId ?: "n/a"} " +
            "S${currentSeason ?: "-"}E${currentEpisode ?: "-"} torrent=false"
    )
    preparePlaybackBeforeStart(
        url = playbackUrl,
        headers = playbackHeaders,
        loadSavedProgress = true
    )
}

private fun String.safeStreamTraceHost(): String {
    return runCatching {
        Uri.parse(this).host ?: substringBefore("://").takeIf { it.isNotBlank() } ?: "unknown"
    }.getOrDefault("unknown")
}

/**
 * Shared episode stream setup used by both torrent and HTTP episode switching.
 */
private fun PlayerRuntimeController.switchToEpisodeStreamCommon(
    stream: Stream,
    forcedTargetVideo: Video? = null
) {
    episodeStreamsScope?.cancel()
    episodeStreamsScope = null
    episodeStreamsJob = null
    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = null
    stillWatchingPromptJob?.cancel()
    stillWatchingPromptJob = null
    streamRepository.setLocalPluginSearchPaused(true)
    flushPlaybackSnapshotForSwitchOrExit()

    val targetVideo = forcedTargetVideo
        ?: _uiState.value.episodes.firstOrNull { it.id == _uiState.value.episodeStreamsForVideoId }

    resetLoadingOverlayForNewStream()
    releasePlayer(flushPlaybackState = false)

    applyStreamMetadata(stream)
    currentFilename = stream.behaviorHints?.filename

    persistedTrackPreference = null
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    // Reset stream-state error flags for the new stream.
    hasRetriedCurrentStreamAfter416 = false
    hasRetriedCurrentStreamAfterUnexpectedNpe = false
    hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = false

    currentVideoId = targetVideo?.id ?: _uiState.value.episodeStreamsForVideoId ?: currentVideoId
    currentSeason = targetVideo?.season ?: _uiState.value.episodeStreamsSeason ?: currentSeason
    currentEpisode = targetVideo?.episode ?: _uiState.value.episodeStreamsEpisode ?: currentEpisode
    currentEpisodeTitle = targetVideo?.title ?: _uiState.value.episodeStreamsTitle ?: currentEpisodeTitle
    refreshScrobbleItem()

    lastSavedPosition = 0L
    _exoPlayer?.stop()
    resetLoadingOverlayForNewStream()

    _uiState.update {
        it.clearFrameRateDetection().copy(
            isBuffering = true,
            error = null,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            currentEpisodeTitle = currentEpisodeTitle,
            currentStreamName = stream.name ?: stream.addonName,
            currentStreamUrl = "",
            currentStreamInfoHash = stream.infoHash ?: stream.clientResolve?.infoHash,
            currentStreamFileIdx = stream.clientResolve?.fileIdx,
            currentStreamAddonName = stream.addonName,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            selectedAudioTrackIndex = -1,
            selectedSubtitleTrackIndex = -1,
            chapters = emptyList(),
            showChaptersPanel = false,
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            isLoadingEpisodeStreams = false,
            episodeStreamsError = null,
            isTorrentStream = true,

            parentalWarnings = emptyList(),
            showParentalGuide = false,
            parentalGuideHasShown = false,

            activeSkipInterval = null,
            skipIntervalDismissed = false,
            postPlayMode = null,
            postPlayDismissedForCurrentEpisode = true,
            playbackEnded = false,
            isNextEpisodeMetadataResolved = false,
        )
    }
    showStreamSourceIndicator(stream)
    recomputeNextEpisode(resetVisibility = true)
    updateEpisodeDescription()
    refreshSubtitlesForCurrentEpisode()

    playbackStartedForParentalGuide = false
    skipIntervals = emptyList()
    skipIntroFetchedKey = null
    lastActiveSkipType = null
    autoSkippedIntervalKeys.clear()

    fetchParentalGuide(contentId, contentType, currentSeason, currentEpisode)
    fetchSkipIntervals(contentId, currentSeason, currentEpisode)
}

internal fun PlayerRuntimeController.showEpisodeStreamPicker(video: Video, forceRefresh: Boolean = true) {
    _uiState.update {
        it.copy(
            showEpisodesPanel = true,
            showEpisodeStreams = true,
            showSourcesPanel = false,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            episodesSelectedSeason = video.season ?: it.episodesSelectedSeason
        )
    }
    loadEpisodesIfNeeded()
    loadStreamsForEpisode(video = video, forceRefresh = forceRefresh)
}

internal suspend fun PlayerRuntimeController.resolveYouTubeStream(stream: Stream): Stream? {
    recordLoadingDiagnosticEvent(
        phase = "resolving_youtube",
        message = context.getString(com.nuvio.tv.R.string.youtube_resolving_stream),
        detail = stream.addonName
    )
    val resolved = youTubeStreamResolver.resolve(stream)
    recordLoadingDiagnosticEvent(
        phase = if (resolved != null) "resolving_youtube_done" else "resolving_youtube_failed",
        message = context.getString(com.nuvio.tv.R.string.youtube_resolving_stream),
        detail = stream.addonName
    )
    return resolved
}

internal suspend fun PlayerRuntimeController.resolveDirectDebridStreamIfNeeded(
    stream: Stream,
    season: Int?,
    episode: Int?
): Stream? {
    recordLoadingDiagnosticEvent(
        phase = "resolving_debrid",
        message = context.getString(com.nuvio.tv.R.string.player_loading_preparing),
        detail = stream.addonName
    )
    return when (val result = directDebridResolver.resolveToPlayableStream(stream, season, episode)) {
        is DirectDebridPlayableResult.Success -> {
            recordLoadingDiagnosticEvent(
                phase = "resolving_debrid_done",
                message = context.getString(com.nuvio.tv.R.string.player_loading_preparing),
                detail = stream.addonName
            )
            result.stream
        }
        DirectDebridPlayableResult.MissingApiKey,
        DirectDebridPlayableResult.NotCached,
        DirectDebridPlayableResult.Stale,
        DirectDebridPlayableResult.Error -> {
            recordLoadingDiagnosticEvent(
                phase = "resolving_debrid_failed",
                message = context.getString(com.nuvio.tv.R.string.player_loading_preparing),
                detail = result.javaClass.simpleName
            )
            null
        }
    }
}

/**
 * Starts fetching addon streams for the next episode in the background.
 * Results are stored in [StreamSearchSessionCache] so that the subsequent
 * [playNextEpisode] call hits the cache and plays instantly.
 */
internal fun PlayerRuntimeController.preloadNextEpisodeSources() {
    if (nextEpisodePreloadTriggered) return
    val nextVideo = nextEpisodeVideo ?: return
    val type = contentType ?: return
    val nextInfo = _uiState.value.nextEpisode ?: return
    if (!nextInfo.hasAired) return

    nextEpisodePreloadTriggered = true
    nextEpisodePreloadJob?.cancel()
    nextEpisodePreloadJob = scope.launch {
        Log.d(PlayerRuntimeController.TAG, "Preloading sources for next episode: S${nextVideo.season}E${nextVideo.episode}")
        streamRepository.getStreamsFromAllAddons(
            type = type,
            videoId = nextVideo.id,
            season = nextVideo.season,
            episode = nextVideo.episode
        ).collect { /* results cached by StreamSearchSessionCache */ }
    }
}

internal fun PlayerRuntimeController.cancelNextEpisodePreload() {
    nextEpisodePreloadJob?.cancel()
    nextEpisodePreloadJob = null
    nextEpisodePreloadTriggered = false
}

internal fun PlayerRuntimeController.playNextEpisode(userInitiated: Boolean = false) {
    val nextVideo = nextEpisodeVideo ?: return
    val type = contentType ?: return
    nextEpisodePreloadTriggered = false

    val state = _uiState.value
    val nextInfo = state.nextEpisode ?: return
    if (!nextInfo.hasAired) {
        return
    }
    val activeAutoPlay = state.postPlayMode as? PostPlayMode.AutoPlay
    if (activeAutoPlay != null &&
        (activeAutoPlay.searching || activeAutoPlay.countdownSec != null)
    ) {
        return
    }

    if (type.equals("cloud", ignoreCase = true)) {
        playNextCloudLibraryFile(nextVideo = nextVideo, userInitiated = userInitiated)
        return
    }

    val episodeForMode = state.nextEpisode ?: nextInfo
    _uiState.update {
        it.copy(
            postPlayMode = PostPlayMode.AutoPlay(
                nextEpisode = episodeForMode,
                searching = true,
            ),
            playbackEnded = false,
        )
    }

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = scope.launch {
        try {
            streamRepository.setLocalPluginSearchPaused(false)
            val playerSettings = playerSettingsDataStore.playerSettings.first()
            val cinemaEpisode = nextVideo.id.startsWith("ch:")
            val shouldAutoSelectInManualMode = cinemaEpisode ||
                playerSettings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL &&
                    (
                        playerSettings.streamAutoPlayNextEpisodeEnabled ||
                            playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode
                        )
            val bingeGroupOnlyManualMode =
                !cinemaEpisode && shouldAutoSelectInManualMode &&
                    (!playerSettings.streamAutoPlayNextEpisodeEnabled ||
                        !playerSettings.streamAutoPlayNextEpisodeFallbackEnabled) &&
                    playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode
            if (playerSettings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL && !shouldAutoSelectInManualMode) {
                _uiState.update {
                    it.copy(
                        postPlayMode = null,
                        postPlayDismissedForCurrentEpisode = true,
                    )
                }
                showEpisodeStreamPicker(video = nextVideo, forceRefresh = true)
                return@launch
            }

            val installedAddons = streamAddonsFor(nextVideo.id)
            val installedAddonOrder = installedAddons.map { it.displayName }
            val preferredServerNames = serverStreams.preferredSourceNames(type, nextVideo.id)
            val effectiveMode = if (shouldAutoSelectInManualMode) {
                StreamAutoPlayMode.FIRST_STREAM
            } else {
                playerSettings.streamAutoPlayMode
            }
            val effectiveSource = if (shouldAutoSelectInManualMode) {
                StreamAutoPlaySource.ALL_SOURCES
            } else {
                playerSettings.streamAutoPlaySource
            }
            val effectiveSelectedAddons = if (shouldAutoSelectInManualMode) {
                emptySet()
            } else {
                playerSettings.streamAutoPlaySelectedAddons
            }
            val effectiveSelectedPlugins = if (shouldAutoSelectInManualMode) {
                emptySet()
            } else {
                playerSettings.streamAutoPlaySelectedPlugins
            }
            val effectiveRegex = if (shouldAutoSelectInManualMode) {
                ""
            } else {
                playerSettings.streamAutoPlayRegex
            }
            var selectedStream: Stream? = null
            var lastSuccessData: List<AddonStreams>? = null
            var autoSelectTriggered = false
            var timeoutElapsed = false
            var lastError: NetworkResult.Error? = null
            // Completed as soon as a stream is selected or the addon search
            // finishes, so the waiting code below resumes without polling.
            val searchSettled = CompletableDeferred<Unit>()

            fun trySelectStream(data: List<AddonStreams>): Stream? {
                val orderedStreams = StreamAutoPlaySelector.orderAddonStreams(data, installedAddonOrder, preferredServerNames)
                val allStreams = orderedStreams.flatMap { it.streams }
                if (cinemaEpisode) return cinemaContinuationStream(allStreams, currentStreamBingeGroup)
                return StreamAutoPlaySelector.selectAutoPlayStream(
                    streams = allStreams,
                    mode = effectiveMode,
                    regexPattern = effectiveRegex,
                    source = effectiveSource,
                    installedAddonNames = installedAddonOrder.toSet(),
                    selectedAddons = effectiveSelectedAddons,
                    selectedPlugins = effectiveSelectedPlugins,
                    preferredBingeGroup = if (playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode) {
                        currentStreamBingeGroup
                    } else {
                        null
                    },
                    preferBingeGroupInSelection = playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode,
                    bingeGroupOnly = bingeGroupOnlyManualMode
                )
            }

            fun tryBingeGroupOnly(data: List<AddonStreams>): Stream? {
                if (cinemaEpisode) return trySelectStream(data)
                if (currentStreamBingeGroup == null || !playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode) return null
                val orderedStreams = StreamAutoPlaySelector.orderAddonStreams(data, installedAddonOrder, preferredServerNames)
                val allStreams = orderedStreams.flatMap { it.streams }
                // Early: binge group + filename match (most precise)
                val nextFilename = com.nuvio.tv.core.player.FilenameEpisodeMatcher.buildNextFilename(
                    filename = currentFilename.orEmpty(),
                    currentSeason = _uiState.value.currentSeason,
                    currentEpisode = _uiState.value.currentEpisode ?: 0,
                    nextSeason = nextVideo.season,
                    nextEpisode = nextVideo.episode ?: 0
                ) ?: currentFilename?.takeIf { it.isNotBlank() }
                if (nextFilename != null) {
                    val bingeAndFilename = allStreams.firstOrNull { stream ->
                        !stream.isExternal() &&
                            stream.behaviorHints?.bingeGroup == currentStreamBingeGroup &&
                            com.nuvio.tv.core.player.FilenameEpisodeMatcher.matches(
                                stream.behaviorHints?.filename, nextFilename
                            )
                    }
                    if (bingeAndFilename != null) return bingeAndFilename
                }
                // Early fallback: binge group alone
                return StreamAutoPlaySelector.selectAutoPlayStream(
                    streams = allStreams,
                    mode = effectiveMode,
                    regexPattern = effectiveRegex,
                    source = effectiveSource,
                    installedAddonNames = installedAddonOrder.toSet(),
                    selectedAddons = effectiveSelectedAddons,
                    selectedPlugins = effectiveSelectedPlugins,
                    preferredBingeGroup = currentStreamBingeGroup,
                    preferBingeGroupInSelection = true,
                    bingeGroupOnly = true
                )
            }

            fun tryFilenameOnly(data: List<AddonStreams>): Stream? {
                if (cinemaEpisode) return trySelectStream(data)
                val nextFilename = com.nuvio.tv.core.player.FilenameEpisodeMatcher.buildNextFilename(
                    filename = currentFilename.orEmpty(),
                    currentSeason = _uiState.value.currentSeason,
                    currentEpisode = _uiState.value.currentEpisode ?: 0,
                    nextSeason = nextVideo.season,
                    nextEpisode = nextVideo.episode ?: 0
                ) ?: currentFilename?.takeIf { it.isNotBlank() }
                    ?: return null
                val orderedStreams = StreamAutoPlaySelector.orderAddonStreams(data, installedAddonOrder, preferredServerNames)
                return orderedStreams.flatMap { it.streams }.firstOrNull { stream ->
                    !stream.isExternal() &&
                        com.nuvio.tv.core.player.FilenameEpisodeMatcher.matches(
                            stream.behaviorHints?.filename, nextFilename
                        )
                }
            }

            fun recordSelection(candidate: Stream) {
                autoSelectTriggered = true
                selectedStream = candidate
                searchSettled.complete(Unit)
            }

            val timeoutSeconds = playerSettings.streamAutoPlayTimeoutSeconds

            val innerJob = launch {
                streamRepository.getStreamsFromAllAddons(
                    type = type,
                    videoId = nextVideo.id,
                    season = nextVideo.season,
                    episode = nextVideo.episode
                ).collect { result ->
                    when (result) {
                        is NetworkResult.Success -> {
                            lastSuccessData = result.data
                            if (!autoSelectTriggered) {
                                val candidate = when {
                                    timeoutElapsed -> trySelectStream(result.data)
                                    playerSettings.streamAutoPlayPreferBingeGroupForNextEpisode ->
                                        tryBingeGroupOnly(result.data)
                                    else -> null
                                }
                                if (candidate != null) recordSelection(candidate)
                            }
                        }
                        is NetworkResult.Error -> lastError = result
                        NetworkResult.Loading -> Unit
                    }
                }
                // Every addon has responded: take whatever matched, then settle so
                // the waiting code below resumes even if nothing was selected.
                if (!autoSelectTriggered) {
                    lastSuccessData?.let { data -> trySelectStream(data)?.let { recordSelection(it) } }
                }
                searchSettled.complete(Unit)
            }

            val timeoutMs = timeoutSeconds * 1_000L
            if (PlayerSettings.isBoundedTimeout(timeoutSeconds)) {
                // Wait for the timeout, resuming as soon as a stream is settled.
                withTimeoutOrNull(timeoutMs) { searchSettled.await() }
                timeoutElapsed = true
                if (!autoSelectTriggered) {
                    // Filename match before full select — same release, any binge group.
                    lastSuccessData?.let { tryFilenameOnly(it)?.let { s -> recordSelection(s) } }
                }
                if (!autoSelectTriggered) {
                    val data = lastSuccessData
                    if (data != null) {
                        trySelectStream(data)?.let { recordSelection(it) }
                    } else {
                        withTimeoutOrNull(timeoutMs) { searchSettled.await() }
                        if (!autoSelectTriggered) {
                            lastSuccessData?.let { tryFilenameOnly(it)?.let { s -> recordSelection(s) } }
                        }
                        if (!autoSelectTriggered) {
                            lastSuccessData?.let { trySelectStream(it)?.let { s -> recordSelection(s) } }
                        }
                    }
                }
                innerJob.cancel()
            } else if (timeoutSeconds == 0) {
                timeoutElapsed = true
                withTimeoutOrNull(NEXT_EPISODE_HARD_TIMEOUT_MS) { searchSettled.await() }
                if (!autoSelectTriggered) {
                    lastSuccessData?.let { tryFilenameOnly(it)?.let { s -> recordSelection(s) } }
                }
                if (!autoSelectTriggered) {
                    lastSuccessData?.let { data -> trySelectStream(data)?.let { recordSelection(it) } }
                }
                innerJob.cancel()
            } else {
                withTimeoutOrNull(NEXT_EPISODE_HARD_TIMEOUT_MS) { searchSettled.await() }
                if (!autoSelectTriggered) {
                    lastSuccessData?.let { tryFilenameOnly(it)?.let { s -> recordSelection(s) } }
                }
                if (!autoSelectTriggered) {
                    lastSuccessData?.let { data -> trySelectStream(data)?.let { recordSelection(it) } }
                }
                innerJob.cancel()
            }

            val streamToPlay = selectedStream?.let {
                resolveDirectDebridStreamIfNeeded(it, nextVideo.season, nextVideo.episode)
            }
            if (streamToPlay != null) {
                val sourceName = (streamToPlay.name?.takeIf { it.isNotBlank() } ?: streamToPlay.addonName).trim()
                for (remaining in 3 downTo 1) {
                    _uiState.update { current ->
                        val episodeForMode = current.nextEpisode ?: nextInfo
                        current.copy(
                            postPlayMode = PostPlayMode.AutoPlay(
                                nextEpisode = episodeForMode,
                                searching = false,
                                sourceName = sourceName,
                                countdownSec = remaining,
                            ),
                        )
                    }
                    delay(1000)
                }
                _uiState.update {
                    it.copy(
                        postPlayMode = null,
                        postPlayDismissedForCurrentEpisode = true,
                        playbackEnded = false,
                    )
                }
                switchToEpisodeStream(
                    stream = streamToPlay,
                    forcedTargetVideo = nextVideo,
                    isAutoPlay = !userInitiated
                )
            } else {
                _uiState.update {
                    it.copy(
                        postPlayMode = null,
                        postPlayDismissedForCurrentEpisode = true,
                    )
                }
                showEpisodeStreamPicker(
                    video = nextVideo,
                    forceRefresh = lastError != null || selectedStream != null
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    postPlayMode = null,
                    postPlayDismissedForCurrentEpisode = true,
                )
            }
            showEpisodeStreamPicker(video = nextVideo, forceRefresh = false)
        }
    }
}

private fun PlayerRuntimeController.playNextCloudLibraryFile(
    nextVideo: Video,
    userInitiated: Boolean
) {
    val playbackContext = cloudPlaybackContext ?: return
    val nextFile = playbackContext.nextFile ?: return
    val nextInfo = _uiState.value.nextEpisode ?: return
    _uiState.update {
        it.copy(postPlayMode = PostPlayMode.AutoPlay(nextEpisode = nextInfo, searching = true))
    }

    nextEpisodeAutoPlayJob?.cancel()
    nextEpisodeAutoPlayJob = scope.launch {
        try {
            when (val result = withTimeoutOrNull(CLOUD_LIBRARY_AUTO_NEXT_TIMEOUT_MS) {
                cloudLibraryRepository.resolvePlayback(playbackContext.item, nextFile)
            }) {
                is com.nuvio.tv.core.cloud.CloudLibraryPlaybackResult.Success -> {
                    val filename = result.filename ?: nextFile.name
                    val stream = Stream(
                        name = playbackContext.item.providerName,
                        title = filename,
                        description = playbackContext.item.name,
                        url = result.url,
                        ytId = null,
                        infoHash = null,
                        fileIdx = null,
                        externalUrl = null,
                        behaviorHints = com.nuvio.tv.domain.model.StreamBehaviorHints(
                            notWebReady = null,
                            bingeGroup = null,
                            countryWhitelist = null,
                            proxyHeaders = null,
                            videoSize = result.videoSizeBytes ?: nextFile.sizeBytes,
                            filename = filename
                        ),
                        addonName = playbackContext.item.providerName,
                        addonLogo = null
                    )
                    val advancedContext = playbackContext.advanceTo(nextFile)
                    cloudSessionToken?.let { cloudPlaybackSessionStore.update(it, advancedContext) }
                    cloudPlaybackContext = advancedContext
                    _uiState.update { it.copy(title = filename) }
                    switchToEpisodeStream(
                        stream = stream,
                        forcedTargetVideo = nextVideo,
                        isAutoPlay = !userInitiated
                    )
                }
                else -> {
                    _uiState.update {
                        it.copy(
                            postPlayMode = null,
                            postPlayDismissedForCurrentEpisode = true,
                            error = context.getString(com.nuvio.tv.R.string.cloud_library_play_failed)
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _uiState.update {
                it.copy(
                    postPlayMode = null,
                    postPlayDismissedForCurrentEpisode = true,
                    error = context.getString(com.nuvio.tv.R.string.cloud_library_play_failed)
                )
            }
        }
    }
}
