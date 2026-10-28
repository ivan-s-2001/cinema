package com.nuvio.tv.ui.screens.search

import androidx.compose.runtime.Composable

/** The TV only renders pages and filters prepared by our server. */
@Composable
fun DiscoverScreen(
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (String, String, String) -> Unit
) {
    CinemaDiscoverScreen(onNavigateToDetail = onNavigateToDetail)
}
