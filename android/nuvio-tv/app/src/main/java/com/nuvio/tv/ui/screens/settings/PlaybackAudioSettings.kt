package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.displayName

@Composable
internal fun audioLanguageLabel(code: String): String = when (code) {
    AudioLanguageOption.DEFAULT -> stringResource(R.string.audio_lang_default)
    AudioLanguageOption.DEVICE -> stringResource(R.string.audio_lang_device)
    AudioLanguageOption.ORIGINAL -> stringResource(R.string.audio_lang_original)
    else -> AVAILABLE_SUBTITLE_LANGUAGES.find { it.code == code }?.displayName ?: code
}

@Composable
internal fun decoderPriorityLabel(priority: Int): String = when (priority) {
    0 -> stringResource(R.string.audio_decoder_device_only)
    2 -> stringResource(R.string.audio_decoder_prefer_app)
    else -> stringResource(R.string.audio_decoder_prefer_device)
}

@Composable
internal fun DecoderPriorityDialog(
    selectedPriority: Int,
    onPrioritySelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        SettingsPickerOption(0, stringResource(R.string.audio_decoder_device_only), stringResource(R.string.audio_decoder_device_only_desc)),
        SettingsPickerOption(1, stringResource(R.string.audio_decoder_prefer_device), stringResource(R.string.audio_decoder_prefer_device_desc)),
        SettingsPickerOption(2, stringResource(R.string.audio_decoder_prefer_app), stringResource(R.string.audio_decoder_prefer_app_desc))
    )

    SettingsSingleChoiceDialog(
        title = stringResource(R.string.audio_decoder_priority),
        subtitle = stringResource(R.string.audio_decoder_controls),
        options = options,
        selectedValue = selectedPriority,
        onOptionSelected = onPrioritySelected,
        onDismiss = onDismiss,
        width = 420.dp,
        maxHeight = 320.dp
    )
}
