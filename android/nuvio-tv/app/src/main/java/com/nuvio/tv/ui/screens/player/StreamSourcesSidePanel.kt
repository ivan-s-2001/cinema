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
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun StreamSourcesSidePanel(
    uiState: PlayerUiState,
    streamsFocusRequester: FocusRequester,
    onClose: () -> Unit,
    onReload: () -> Unit,
    onAddonFilterSelected: (String?) -> Unit,
    onStreamSelected: (Stream) -> Unit,
    onExpandStreams: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val streams = remember(uiState.sourceAllStreams) {
        uiState.sourceAllStreams.filter { it.addonName == "CinemaHall" }
    }
    LaunchedEffect(streams.isNotEmpty()) {
        if (streams.isNotEmpty()) {
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
                streams.isNotEmpty() -> CinemaHallQualityVoicePicker(streams, onStreamSelected, streamsFocusRequester, Modifier.weight(1f))
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
