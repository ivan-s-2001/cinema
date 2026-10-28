package com.nuvio.tv.core.streams

/** CinemaHall title IDs contain colons. Only the final season/episode pair is removable. */
internal fun videoContentId(videoId: String): String {
    if (!videoId.startsWith("ch:")) return videoId.substringBefore(":")
    if (videoId.startsWith("ch:snapshot:movie:")) return videoId
    return videoId.replace(Regex(":\\d+:\\d+$"), "")
}
