package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.local.displayName

@Composable
internal fun subtitleLanguageLabel(style: SubtitleStyleSettings): String = when {
    style.preferredLanguage == "none" -> stringResource(R.string.action_none)
    style.isPreferredLanguageSystemDefault -> stringResource(R.string.appearance_language_system)
    else -> AVAILABLE_SUBTITLE_LANGUAGES.find { it.code == style.preferredLanguage }?.displayName
        ?: stringResource(R.string.appearance_language_system)
}
