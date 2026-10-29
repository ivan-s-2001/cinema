@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.stream.CinemaHallQualityVoicePicker
import com.nuvio.tv.ui.screens.stream.CinemaVoiceChoice
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun StreamSourcesSidePanel(
    uiState: PlayerUiState,
    streamsFocusRequester: FocusRequester,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onStreamSelected: (Stream) -> Unit,
    onVoiceSelected: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val streams = remember(uiState.sourceAllStreams) {
        uiState.sourceAllStreams.filter { it.addonName == "CinemaHall" }
    }
    val voices = if (CinemaPlaybackPolicy.isProvider(uiState.currentStreamUrl)) uiState.audioTracks.map { track ->
        val raw = cinemaAudioLabel(track)
        val label = when (raw.lowercase()) {
            "ru", "rus", "russian" -> "Русский"
            "en", "eng", "english" -> "Английский"
            else -> raw.replace(Regex("(?i)^аудио(?:дорожка)?\\s*"), "Озвучка ")
        }.ifBlank { "Озвучка ${track.index + 1}" }
        val duplicate = uiState.audioTracks.count { cinemaAudioLabel(it) == raw } > 1
        CinemaVoiceChoice(track.index, cinemaAudioKey(track), label + if (duplicate) " · вариант ${track.index + 1}" else "", track.isSelected)
    } else emptyList()
    val playingQuality = uiState.currentStreamName?.removePrefix("CinemaHall · ")?.trim()?.takeIf { it.isNotBlank() && it != "Авто" }
    LaunchedEffect(streams.isNotEmpty() || voices.isNotEmpty()) {
        if (streams.isNotEmpty() || voices.isNotEmpty()) {
            withFrameNanos { }
            runCatching { streamsFocusRequester.requestFocus() }
        }
    }
    Box(modifier = modifier.fillMaxHeight().width(520.dp)
        .clip(RoundedCornerShape(topStart = NuvioTheme.spacing.lg, bottomStart = NuvioTheme.spacing.lg))
        .background(NuvioTheme.colors.BackgroundElevated)) {
        Column(modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.xl)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Качество и озвучка", style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary)
                DialogButton(text = "Закрыть", onClick = onClose, isPrimary = false)
            }
            Spacer(Modifier.height(NuvioTheme.spacing.lg))
            when {
                streams.isNotEmpty() || voices.isNotEmpty() -> CinemaHallQualityVoicePicker(streams, onStreamSelected, streamsFocusRequester, Modifier.weight(1f), playingQuality, voices, onVoiceSelected)
                uiState.isLoadingSourceStreams -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                else -> {
                    Text(uiState.sourceStreamsError ?: "Варианты воспроизведения не найдены",
                        color = NuvioTheme.colors.TextPrimary)
                    Spacer(Modifier.height(NuvioTheme.spacing.lg))
                    DialogButton(text = "Повторить", onClick = onReload, isPrimary = false)
                }
            }
        }
    }
}
