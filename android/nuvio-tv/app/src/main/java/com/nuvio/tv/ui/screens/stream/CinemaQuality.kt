package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.Stream

/** Stable identity preserves focus when progressive discovery inserts or renames a voice. */
internal fun cinemaVoiceChoiceKey(stream: Stream): String =
    (stream.behaviorHints?.bingeGroup?.takeIf { it.isNotBlank() } ?: stream.title.orEmpty().trim().lowercase()) +
        "\u0000" + stream.technologyHints.sorted().joinToString("\u0000")

internal fun cinemaVoiceChoices(streams: List<Stream>): List<Stream> = streams.distinctBy(::cinemaVoiceChoiceKey)
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
