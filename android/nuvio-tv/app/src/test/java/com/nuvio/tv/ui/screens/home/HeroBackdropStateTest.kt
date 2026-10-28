package com.nuvio.tv.ui.screens.home

import org.junit.Assert.*
import org.junit.Test

class HeroBackdropStateTest {
    @Test fun fastClickUsesTheClickedTitleRatherThanThePreviouslyDisplayedHero() {
        HeroBackdropState.update("https://example.org/previous.jpg")
        HeroBackdropState.prepareForDetail("ch:clicked", "https://example.org/clicked.jpg", "https://example.org/poster.jpg")
        assertEquals("https://example.org/clicked.jpg", HeroBackdropState.consumeAndClear("ch:clicked"))
        assertNull(HeroBackdropState.consumeAndClear("ch:clicked"))
        assertEquals("https://example.org/previous.jpg", HeroBackdropState.lastDisplayedUrl)
    }
    @Test fun aTitleWithoutABackdropCannotBorrowAnotherTitlesImageOrStretchItsPoster() {
        for (backdrop in listOf(null, "", "https://example.org/poster.jpg")) {
            HeroBackdropState.update("https://example.org/previous.jpg")
            HeroBackdropState.prepareForDetail("ch:clicked", backdrop, "https://example.org/poster.jpg")
            assertNull(HeroBackdropState.consumeAndClear("ch:clicked"))
        }
    }
    @Test fun anUnrelatedOrUnpreparedTransitionCannotSupplyTheLoadingBackground() {
        HeroBackdropState.prepareForDetail("ch:previous", "https://example.org/previous.jpg")
        assertNull(HeroBackdropState.consumeAndClear("ch:clicked"))
        HeroBackdropState.update("https://example.org/home-hero.jpg")
        assertNull(HeroBackdropState.consumeAndClear("ch:clicked"))
    }

}
