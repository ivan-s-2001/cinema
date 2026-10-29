package com.nuvio.tv.ui.screens.player

internal object CinemaPlaybackPolicy {
    fun isProvider(url: String?): Boolean = try {
        val uri = java.net.URI(url.orEmpty())
        uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in listOf(18991, 18992) && uri.path.startsWith("/play/")
    } catch (_: Exception) { false }
    fun timedOut(waitingMs: Long, stalledMs: Long, started: Boolean, paused: Boolean): Boolean =
        !paused && if (started) (stalledMs >= 30_000 || waitingMs >= 90_000) else waitingMs >= 60_000
    fun recoveryUrl(url: String?, selectedGroup: String?): String? = try {
        val uri = java.net.URI(url.orEmpty())
        val parts = uri.rawPath.removePrefix("/").split('/')
        if (!isProvider(url) || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            parts.size !in listOf(10, 12) || parts[7] != "session" || parts.last() != "video.mkv" ||
            java.util.UUID.fromString(parts[8]).toString() != parts[8]) null
        else {
            fun decoded(index: Int) = java.net.URLDecoder.decode(parts[index], "UTF-8")
            val quality = decoded(4)
            val voice = decoded(5)
            val technology = decoded(6)
            val group = if (quality == "auto" && voice == "auto" && technology.isEmpty()) "cinemahall:auto"
                else "cinemahall:$quality:$voice:tech:$technology"
            val attempt = if (parts.size == 10) 0
                else if (parts[9] == "retry" && parts[10] in listOf("1", "2")) parts[10].toInt() else 2
            if (group != selectedGroup || attempt >= 2) null
            else "${uri.scheme}://${uri.rawAuthority}/${parts.take(9).joinToString("/")}/retry/${attempt + 1}/video.mkv"
        }
    } catch (_: Exception) { null }

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

/** Monotonic sampling; pause, seek and normal playback end a buffering interval. */
internal class CinemaBufferWatchdog {
    private var started: Long? = null
    private var advanced: Long = 0L
    private var buffered: Long = -1L
    fun timedOut(now: Long, waiting: Boolean, bufferedPosition: Long): Boolean {
        if (!waiting) { started = null; buffered = -1L; return false }
        if (started == null) { started = now; advanced = now }
        if (bufferedPosition != buffered) advanced = now
        buffered = bufferedPosition
        return CinemaPlaybackPolicy.timedOut(now - (started ?: now), now - advanced, true, false)
    }
}
