package com.nuvio.tv.ui.screens.home

object HeroBackdropState {
    @Volatile
    var currentHeroBackdropUrl: String? = null
        private set

    /** Last backdrop URL that was actually displayed — survives navigation for seamless back transitions. */
    @Volatile
    var lastDisplayedUrl: String? = null

    private var detailTitleId: String? = null

    fun update(url: String?) {
        detailTitleId = null
        currentHeroBackdropUrl = url
        if (!url.isNullOrBlank()) {
            lastDisplayedUrl = url
        }
    }

    /** A detail transition belongs to the clicked title, independent of delayed hero focus updates. */
    fun prepareForDetail(titleId: String, backdrop: String?, poster: String? = null) {
        detailTitleId = titleId
        currentHeroBackdropUrl = backdrop?.takeIf { it.isNotBlank() && it != poster }
    }

    fun consumeAndClear(titleId: String? = null): String? {
        val url = currentHeroBackdropUrl.takeIf { titleId?.startsWith("ch:") != true || detailTitleId == titleId }
        detailTitleId = null
        currentHeroBackdropUrl = null
        return url
    }
}
