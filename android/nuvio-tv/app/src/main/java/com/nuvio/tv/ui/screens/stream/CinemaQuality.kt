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

internal fun cinemaVoiceChoices(streams: List<Stream>): List<Stream> = streams.distinctBy {
    it.title.orEmpty().trim().lowercase() to it.technologyHints.sorted()
}
internal fun cinemaTechnologyLabel(stream: Stream): String? = stream.technologyHints.takeIf { it.isNotEmpty() }?.joinToString(" · ")


/** Explicit launches of our titles always show quality/voice choices, including cached searches. */
internal fun cinemaRequiresInitialSelection(videoId: String, requestedManual: Boolean): Boolean =
    requestedManual || videoId.startsWith("ch:")

/** Continue the selected quality/studio, or ask again instead of silently changing the voice. */
internal fun cinemaContinuationStream(streams: List<Stream>, selectedGroup: String?): Stream? =
    selectedGroup?.takeIf { it.startsWith("cinemahall:") }?.let { group ->
        streams.firstOrNull { it.addonName == "CinemaHall" && it.behaviorHints?.bingeGroup == group }
    }

internal fun cinemaSelectableStreams(streams: List<Stream>): List<Stream> = streams.filter {
    it.addonName == "CinemaHall" && it.name?.removePrefix("CinemaHall · ")?.trim() != "Авто"
}
