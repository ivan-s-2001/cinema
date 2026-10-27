package com.nuvio.app.features.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.streams.LocalStreamSizeLabelFormat
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.streams.StreamCard
import com.nuvio.app.features.streams.StreamCardSkeleton
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.streams.isSelectableForPlayback
import com.nuvio.app.features.streams.rememberStreamSizeLabelFormat
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_player_no_streams_found
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerStreamList(
    streamsUiState: StreamsUiState,
    onStreamSelected: (StreamItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = 8.dp,
        top = 14.dp,
        end = 8.dp,
        bottom = 8.dp,
    ),
    currentStreamUrl: String? = null,
    currentStreamName: String? = null,
    currentLabel: String? = null,
) {
    val debridSettings by remember {
        DebridSettingsRepository.ensureLoaded()
        DebridSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    val streamBadgeSettings by remember {
        StreamBadgeSettingsRepository.ensureLoaded()
        StreamBadgeSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    // CinemaHall is the only playback catalogue; never offer third-party add-on releases.
    val streams = streamsUiState.allStreams.filter { it.addonName == "CinemaHall" }
    val visibleGroups = streamsUiState.filteredGroups
    val cinemaOnly = streams.isNotEmpty()

    when {
        streams.isEmpty() && streamsUiState.isAnyLoading -> {
            Column(
                modifier = modifier.padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(4) {
                    StreamCardSkeleton()
                }
            }
        }

        streams.isEmpty() -> {
            val error = visibleGroups.firstOrNull { it.error != null }?.error
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = error ?: stringResource(Res.string.compose_player_no_streams_found),
                    color = Color.White.copy(alpha = if (error == null) 0.7f else 0.85f),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        cinemaOnly -> {
            CinemaHallMobileQualityPicker(
                streams = streams,
                onStreamSelected = onStreamSelected,
                modifier = modifier,
                contentPadding = contentPadding,
            )
        }

        else -> {
            val streamKeys = remember(streams) { streams.stablePlayerKeys() }
            val formatStreamSize = rememberStreamSizeLabelFormat()
            CompositionLocalProvider(LocalStreamSizeLabelFormat provides formatStreamSize) {
            LazyColumn(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = contentPadding,
            ) {
                itemsIndexed(
                    items = streams,
                    key = { index, _ -> streamKeys[index] },
                ) { _, stream ->
                    StreamCard(
                        stream = stream,
                        enabled = stream.isSelectableForPlayback(debridSettings.canResolvePlayableLinks),
                        appendInstantServiceToDefaultName = debridSettings.canResolvePlayableLinks &&
                            !debridSettings.hasCustomStreamFormatting,
                        showFileSizeBadges = streamBadgeSettings.showFileSizeBadges,
                        showAddonLogo = streamBadgeSettings.showAddonLogo,
                        badgePlacement = streamBadgeSettings.badgePlacement,
                        isCurrent = stream.isCurrentPlayerStream(currentStreamUrl, currentStreamName),
                        currentLabel = currentLabel,
                        onClick = { onStreamSelected(stream) },
                    )
                }
                if (streamsUiState.isAnyLoading) {
                    item {
                        StreamCardSkeleton()
                    }
                }
            }
            }
        }
    }
}

/** Simple CinemaHall choices; original Nuvio stream cards remain for other providers. */
@Composable
private fun CinemaHallMobileQualityPicker(
    streams: List<StreamItem>,
    onStreamSelected: (StreamItem) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val ranks = remember { listOf("4K", "2K", "1080p", "720p", "576p", "480p", "360p", "240p") }
    val groups = remember(streams) {
        streams.groupBy { it.name.orEmpty().removePrefix("CinemaHall · ").trim().ifEmpty { "Не указано" } }
            .toList()
            .sortedBy { ranks.indexOf(it.first).let { index -> if (index < 0) 99 else index } }
    }
    var quality by remember(groups) { mutableStateOf(groups.firstOrNull()?.first.orEmpty()) }
    val voices = groups.firstOrNull { it.first == quality }?.second.orEmpty()
        .distinctBy { it.title.orEmpty().trim().lowercase() }
    Column(
        modifier = modifier.fillMaxWidth().padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Качество", style = MaterialTheme.typography.titleMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            groups.forEach { entry ->
                val active = quality == entry.first
                Box(
                    modifier = Modifier
                        .background(
                            if (active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable { quality = entry.first }
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text(entry.first, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Озвучка", style = MaterialTheme.typography.titleMedium)
        voices.forEach { stream ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .clickable { onStreamSelected(stream) }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Text(
                    stream.title?.takeIf { it.isNotBlank() } ?: "Озвучка не указана",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
