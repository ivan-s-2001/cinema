package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCatalogTest {
    @Test fun `only CinemaHall settings are visible for every profile`() {
        val expected = listOf(SettingsCategory.PROFILES, SettingsCategory.APPEARANCE, SettingsCategory.LAYOUT, SettingsCategory.PLAYBACK, SettingsCategory.ABOUT)
        for (primary in listOf(true, false)) for (essential in listOf(true, false)) for (debug in listOf(true, false)) {
            assertEquals(expected, visibleSettingsCategories(primary, essential, debug, true))
        }
    }
}
