package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.Stream

internal fun cinemaPreferredStream(streams: List<Stream>, preferredQuality: String): Stream? {
    val choices = streams.filter { it.addonName == "CinemaHall" }
    val qualities = listOf("4K", "2K", "1080p", "720p", "576p", "480p", "360p", "240p")
    val preferred = qualities.indexOf(preferredQuality).coerceAtLeast(0)
    val order = qualities.drop(preferred) + qualities.take(preferred).reversed()
    return order.firstNotNullOfOrNull { quality ->
        choices.firstOrNull { it.name?.removePrefix("CinemaHall · ")?.trim() == quality }
    } ?: choices.firstOrNull()
}
