package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.Stream

internal data class CinemaVoiceChoice(val index: Int, val key: String, val label: String, val selected: Boolean)

/** Stable identity preserves focus when progressive discovery inserts or renames a voice. */
internal fun cinemaVoiceChoiceKey(stream: Stream): String =
    (stream.behaviorHints?.bingeGroup?.takeIf { it.isNotBlank() } ?: stream.title.orEmpty().trim().lowercase()) +
        "\u0000" + stream.technologyHints.sorted().joinToString("\u0000")

internal fun cinemaVoiceChoices(streams: List<Stream>): List<Stream> = streams.distinctBy(::cinemaVoiceChoiceKey)
internal fun cinemaTechnologyLabel(stream: Stream): String? = stream.technologyHints.takeIf { it.isNotEmpty() }?.joinToString(" · ")


/** Playback starts automatically; quality and voice choices are an explicit secondary action. */
internal fun cinemaRequiresInitialSelection(requestedManual: Boolean): Boolean = requestedManual

/** Keep an earlier voice when available, otherwise let the provider choose the best release. */
internal fun cinemaPreferredAutoPlayStream(streams: List<Stream>, selectedGroup: String?): Stream? =
    cinemaContinuationStream(streams, selectedGroup)
        ?: streams.firstOrNull { it.addonName == "CinemaHall" && it.behaviorHints?.bingeGroup == "cinemahall:auto" }
        ?: cinemaSelectableStreams(streams).firstOrNull()

/** Continue the selected quality/studio, or ask again instead of silently changing the voice. */
internal fun cinemaContinuationStream(streams: List<Stream>, selectedGroup: String?): Stream? =
    selectedGroup?.takeIf { it.startsWith("cinemahall:") }?.let { group ->
        streams.firstOrNull { it.addonName == "CinemaHall" && it.behaviorHints?.bingeGroup == group }
    }

internal fun cinemaSelectableStreams(streams: List<Stream>): List<Stream> = streams.filter {
    it.addonName == "CinemaHall" && it.name?.removePrefix("CinemaHall · ")?.trim() != "Авто"
}
