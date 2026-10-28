package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.nuvio.tv.ui.theme.NuvioTheme

/** Keep the artwork slot stable while Coil loads; only use text after a missing/failed asset. */
@Composable
fun CinemaTitleArtwork(
    title: String,
    logo: String?,
    caption: String?,
    height: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val heightPx = with(density) { height.roundToPx() }
    var failed by remember(logo) { mutableStateOf(false) }
    val model = remember(context, logo, heightPx) {
        logo?.takeIf { it.isNotBlank() }?.let {
            ImageRequest.Builder(context).data(it).size(heightPx * 4, heightPx).build()
        }
    }
    Column(modifier = modifier) {
        if (model != null && !failed) {
            AsyncImage(
                model = model,
                contentDescription = title,
                modifier = Modifier.fillMaxWidth().height(height),
                contentScale = ContentScale.Fit,
                alignment = if (caption.isNullOrBlank()) Alignment.CenterStart else Alignment.Center,
                onError = { failed = true }
            )
            CinemaLogoCaption(caption)
        } else {
            Text(title, style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().height(height))
        }
    }
}
