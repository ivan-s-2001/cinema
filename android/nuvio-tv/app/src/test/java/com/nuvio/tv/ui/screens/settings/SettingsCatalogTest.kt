package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCatalogTest {
    @Test fun `only CinemaHall settings are visible for every profile`() {
        val expected = listOf(SettingsCategory.PROFILES, SettingsCategory.APPEARANCE, SettingsCategory.PLAYBACK, SettingsCategory.ABOUT)
        assertEquals(expected, visibleSettingsCategories())
    }
}
