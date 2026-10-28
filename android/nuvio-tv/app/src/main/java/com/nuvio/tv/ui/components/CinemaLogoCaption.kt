package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
fun CinemaLogoCaption(text: String?, modifier: Modifier = Modifier) {
    if (text.isNullOrBlank()) return
    Text(text = text, fontSize = 9.sp, lineHeight = 11.sp,
        color = NuvioTheme.colors.TextPrimary.copy(alpha = 0.8f),
        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().padding(top = 2.dp))
}
