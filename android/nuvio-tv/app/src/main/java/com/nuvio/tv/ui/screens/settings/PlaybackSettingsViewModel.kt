package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val playerSettingsDataStore: PlayerSettingsDataStore
) : ViewModel() {
    val playerSettings = playerSettingsDataStore.playerSettings

    suspend fun setCinemaPreferredQuality(value: String) =
        playerSettingsDataStore.setCinemaPreferredQuality(value)

    suspend fun setStreamAutoPlayNextEpisodeEnabled(enabled: Boolean) =
        playerSettingsDataStore.setStreamAutoPlayNextEpisodeEnabled(enabled)

    suspend fun setSubtitlePreferredLanguage(language: String) =
        playerSettingsDataStore.setSubtitlePreferredLanguage(language)

    suspend fun setUseForcedSubtitles(enabled: Boolean) =
        playerSettingsDataStore.setUseForcedSubtitles(enabled)

    suspend fun setPreferredAudioLanguage(language: String) =
        playerSettingsDataStore.setPreferredAudioLanguage(language)

    suspend fun setDecoderPriority(priority: Int) =
        playerSettingsDataStore.setDecoderPriority(priority)
}
