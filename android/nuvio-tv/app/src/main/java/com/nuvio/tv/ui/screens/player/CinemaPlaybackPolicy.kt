package com.nuvio.tv.ui.screens.player

internal object CinemaPlaybackPolicy {
    fun isProvider(url: String?): Boolean = try {
        val uri = java.net.URI(url.orEmpty())
        uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port in listOf(18991, 18992) && uri.path.startsWith("/play/")
    } catch (_: Exception) { false }
    fun timedOut(waitingMs: Long, stalledMs: Long, started: Boolean, paused: Boolean): Boolean =
        !paused && if (started) stalledMs >= 30_000 else waitingMs >= 60_000
}
