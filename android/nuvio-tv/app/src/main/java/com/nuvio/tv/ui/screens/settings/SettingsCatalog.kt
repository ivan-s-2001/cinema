package com.nuvio.tv.ui.screens.settings

internal enum class SettingsRailGroup { YOU, LOOK, WATCH, SYSTEM }

internal enum class SettingsCategory(val group: SettingsRailGroup) {
    PROFILES(SettingsRailGroup.YOU),
    APPEARANCE(SettingsRailGroup.LOOK),
    PLAYBACK(SettingsRailGroup.WATCH),
    ABOUT(SettingsRailGroup.SYSTEM)
}

internal fun visibleSettingsCategories(): List<SettingsCategory> = SettingsCategory.entries

internal fun List<SettingsCategory>.startsNewGroup(index: Int): Boolean =
    index > 0 && this[index - 1].group != this[index].group
