package com.nuvio.tv.ui.screens.addon

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Displays the local configuration URL without requiring QR scanning or account sign-in.
 * Legacy callers retain their signature until their settings flows are simplified.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun QrCodeOverlay(
    qrBitmap: Bitmap?,
    serverUrl: String?,
    instruction: String,
    onClose: () -> Unit,
    hasPendingChange: Boolean = false,
    qrSize: Dp = 220.dp,
    modifier: Modifier = Modifier
) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(hasPendingChange) {
        if (!hasPendingChange) closeFocus.requestFocus()
    }
    BackHandler(onBack = onClose)

    Box(
        modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = "Откройте адрес на телефоне, чтобы настроить приложение",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            if (!serverUrl.isNullOrBlank()) {
                Text(
                    text = serverUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }
            Button(onClick = onClose, modifier = Modifier.focusRequester(closeFocus)) {
                Text("Закрыть")
            }
        }
    }
}
