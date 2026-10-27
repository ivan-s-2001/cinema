package com.nuvio.tv.core.build

object AppFeaturePolicy {
    val pluginsEnabled: Boolean = false
    val inAppUpdatesEnabled: Boolean = true
    val inAppTrailerPlaybackEnabled: Boolean = true
    val externalTrailerPlaybackEnabled: Boolean = true
    val supportNuvioEnabled: Boolean = false
    val trailerPlaybackMode: TrailerPlaybackMode = TrailerPlaybackMode.IN_APP
    val imdbRatingLogoEnabled: Boolean = true
}
