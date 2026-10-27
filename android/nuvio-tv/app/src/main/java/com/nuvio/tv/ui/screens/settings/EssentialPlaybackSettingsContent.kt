package com.nuvio.tv.ui.screens.settings

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.SubtitleLanguageOption
import com.nuvio.tv.ui.components.P2pConsentDialog
import kotlinx.coroutines.launch

@Composable
fun EssentialPlaybackSettingsContent(
    initialFocusRequester: FocusRequester? = null,
    viewModel: PlaybackSettingsViewModel = hiltViewModel()
) {
    val playerSettings by viewModel.playerSettings.collectAsStateWithLifecycle(initialValue = null)
    val coroutineScope = rememberCoroutineScope()
    var showQualityDialog by remember { mutableStateOf(false) }
    var showSubtitleLanguageDialog by remember { mutableStateOf(false) }
    var showAudioLanguageDialog by remember { mutableStateOf(false) }
    var showDecoderPriorityDialog by remember { mutableStateOf(false) }
    val settings = playerSettings

    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            item(key = "header") {
                SettingsDetailHeader(
                    title = stringResource(R.string.essential_playback_header_title),
                    subtitle = stringResource(R.string.essential_playback_header_subtitle)
                )
            }
            item(key = "playback_basics") {
                SettingsGroupCard(modifier = Modifier.fillMaxWidth(), title = stringResource(R.string.essential_playback_basics)) {
                    SettingsActionRow(
                        title = "Предпочитаемое качество",
                        subtitle = "Если выбранное качество недоступно, будет выбрано ближайшее доступное",
                        value = settings?.cinemaPreferredQuality?.let { if (it == "BEST") "Наилучшее" else it },
                        onClick = { showQualityDialog = true },
                        enabled = settings != null,
                        modifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.essential_autoplay_next_episode),
                        subtitle = stringResource(R.string.essential_autoplay_next_episode_subtitle),
                        checked = settings?.streamAutoPlayNextEpisodeEnabled == true,
                        onToggle = {
                            val current = settings ?: return@SettingsToggleRow
                            coroutineScope.launch {
                                viewModel.setStreamAutoPlayNextEpisodeEnabled(!current.streamAutoPlayNextEpisodeEnabled)
                            }
                        },
                        enabled = settings != null
                    )

                }
            }
            item(key = "subtitles_audio") {
                SettingsGroupCard(modifier = Modifier.fillMaxWidth(), title = stringResource(R.string.essential_subtitles_and_audio)) {
                    SettingsActionRow(
                        title = stringResource(R.string.essential_subtitle_language),
                        subtitle = stringResource(R.string.essential_subtitle_language_subtitle),
                        value = settings?.let { subtitleLanguageLabel(it.subtitleStyle) },
                        onClick = { showSubtitleLanguageDialog = true },
                        enabled = settings != null
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.sub_use_forced_subtitles),
                        subtitle = stringResource(R.string.sub_use_forced_subtitles_desc),
                        checked = settings?.subtitleStyle?.useForcedSubtitles == true,
                        onToggle = {
                            val current = settings ?: return@SettingsToggleRow
                            coroutineScope.launch { viewModel.setUseForcedSubtitles(!current.subtitleStyle.useForcedSubtitles) }
                        },
                        enabled = settings != null
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.essential_audio_language),
                        subtitle = stringResource(R.string.essential_audio_language_subtitle),
                        value = settings?.let { audioLanguageLabel(it.preferredAudioLanguage) },
                        onClick = { showAudioLanguageDialog = true },
                        enabled = settings != null
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.audio_decoder_priority),
                        subtitle = stringResource(R.string.audio_decoder_controls),
                        value = settings?.let { decoderPriorityLabel(it.decoderPriority) },
                        onClick = { showDecoderPriorityDialog = true },
                        enabled = settings != null
                    )
                }
            }
        }
        SettingsVerticalScrollIndicators(state = listState)
    }

    if (showQualityDialog && settings != null) {
        val qualities = listOf("BEST", "4K", "2K", "1080p", "720p", "576p", "480p", "360p", "240p")
        SettingsSingleChoiceDialog(
            title = "Предпочитаемое качество",
            options = qualities.map { SettingsPickerOption(it, if (it == "BEST") "Наилучшее" else it, null) },
            selectedValue = settings.cinemaPreferredQuality,
            onOptionSelected = { quality ->
                coroutineScope.launch { viewModel.setCinemaPreferredQuality(quality) }
                showQualityDialog = false
            },
            onDismiss = { showQualityDialog = false }
        )
    }
    if (showSubtitleLanguageDialog && settings != null) {
        LanguageSelectionDialog(
            title = stringResource(R.string.essential_subtitle_language),
            selectedLanguage = when {
                settings.subtitleStyle.preferredLanguage == "none" -> null
                settings.subtitleStyle.isPreferredLanguageSystemDefault -> SubtitleLanguageOption.DEVICE
                else -> settings.subtitleStyle.preferredLanguage
            },
            showNoneOption = true,
            extraOptions = listOf(SubtitleLanguageOption.DEVICE to stringResource(R.string.appearance_language_system)),
            onLanguageSelected = { language ->
                coroutineScope.launch { viewModel.setSubtitlePreferredLanguage(language ?: "none") }
                showSubtitleLanguageDialog = false
            },
            onDismiss = { showSubtitleLanguageDialog = false }
        )
    }
    if (showAudioLanguageDialog && settings != null) {
        LanguageSelectionDialog(
            title = stringResource(R.string.essential_audio_language),
            selectedLanguage = settings.preferredAudioLanguage,
            showNoneOption = false,
            extraOptions = listOf(
                AudioLanguageOption.DEFAULT to stringResource(R.string.audio_lang_media_default),
                AudioLanguageOption.DEVICE to stringResource(R.string.audio_lang_device),
                AudioLanguageOption.ORIGINAL to stringResource(R.string.audio_lang_original)
            ),
            onLanguageSelected = { language ->
                if (language != null) {
                    coroutineScope.launch { viewModel.setPreferredAudioLanguage(language) }
                }
                showAudioLanguageDialog = false
            },
            onDismiss = { showAudioLanguageDialog = false }
        )
    }
    if (showDecoderPriorityDialog && settings != null) {
        DecoderPriorityDialog(
            selectedPriority = settings.decoderPriority,
            onPrioritySelected = { priority ->
                coroutineScope.launch { viewModel.setDecoderPriority(priority) }
                showDecoderPriorityDialog = false
            },
            onDismiss = { showDecoderPriorityDialog = false }
        )
    }
}
