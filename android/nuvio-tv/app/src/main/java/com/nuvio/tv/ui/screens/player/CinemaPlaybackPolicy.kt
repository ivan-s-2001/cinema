package com.nuvio.tv.ui.screens.player

internal object CinemaPlaybackPolicy {
    fun isProvider(url: String?): Boolean = try {
        val uri = java.net.URI(url.orEmpty())
        uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in listOf(18991, 18992) && uri.path.startsWith("/play/")
    } catch (_: Exception) { false }
    fun timedOut(waitingMs: Long, stalledMs: Long, started: Boolean, paused: Boolean): Boolean =
        !paused && if (started) stalledMs >= 30_000 else waitingMs >= 60_000
}


/** A source change is an absolute timeline transfer, even in the credits or before the first frame. */
internal fun cinemaSourceSwitchPosition(live: Long?, rendered: Boolean, pending: Long?, timeline: Long): Long =
    (if (rendered && live != null && live >= 0L) live else pending ?: timeline).coerceAtLeast(0L)

internal fun cinemaSourceSwitchTarget(position: Long, duration: Long): Long =
    position.coerceAtLeast(0L).let { if (duration > 0L) it.coerceAtMost((duration - 1L).coerceAtLeast(0L)) else it }

internal fun cinemaSelectedVoice(group: String?): String? = group
    ?.takeIf { it.startsWith("cinemahall:") }
    ?.removePrefix("cinemahall:")?.substringAfter(':', "")?.substringBefore(":tech:")
    ?.takeIf { it.isNotBlank() && it != "auto" }

// Both engines append their codec description in parentheses. Studio matching
// uses the original label, never the language alone or the audio-list position.
internal fun cinemaAudioLabel(track: TrackInfo): String = track.name.substringBeforeLast(" (", track.name).trim()
internal fun cinemaAudioKey(track: TrackInfo): String = app.cinemahall.tv.Logic.voiceKey(cinemaAudioLabel(track))
internal fun cinemaMatchingAudioIndex(tracks: List<TrackInfo>, voice: String?): Int? {
    if (voice.isNullOrBlank() || voice in listOf("не указано", "ru", "rus", "русский", "russian")) return null
    return tracks.filter { cinemaAudioKey(it) == voice }.singleOrNull()?.index
}

internal fun cinemaContinuationGroupForAudio(group: String?, track: TrackInfo): String? {
    val selected = group?.takeIf { it.startsWith("cinemahall:") } ?: return null
    val quality = selected.removePrefix("cinemahall:").substringBefore(':')
        .takeIf { it.isNotBlank() && it != "auto" } ?: return null
    val voice = cinemaAudioKey(track)
    if (voice in listOf("не указано", "ru", "rus", "русский", "russian", "en", "eng", "английский", "english", "und") ||
        voice.matches(Regex("(?:аудио(?:дорожка)?|audio(?: track)?|озвучка) ?\\d*"))) return null
    return "cinemahall:$quality:$voice" + if (selected.contains(":tech:")) ":tech:" + selected.substringAfter(":tech:") else ""
}
