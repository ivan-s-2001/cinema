package com.nuvio.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.CinemaRating
import com.nuvio.tv.ui.theme.NuvioTheme
import java.util.Locale

internal fun cinemaRatingLabel(rating: CinemaRating): String =
    "${rating.label} ${String.format(Locale.ROOT, "%.1f", rating.value)}/${rating.scale}"

/** Scores already included in the server card; no SDKs, logos or extra requests. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CinemaRatingsRow(ratings: List<CinemaRating>, modifier: Modifier = Modifier) {
    if (ratings.isEmpty()) return
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = 3) {
        ratings.take(6).forEach { rating ->
            Text(text = cinemaRatingLabel(rating), style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
