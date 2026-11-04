package com.vkurylo.shifttracker.domain.model

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val earningsMode: EarningsMode = EarningsMode.GROSS,
    /** The home-screen widget shows "•••" instead of money (it is visible on the home/lock screen). */
    val hideWidgetAmounts: Boolean = false,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** App UI languages. [tag] is a BCP-47 tag; [SYSTEM] follows the phone language. */
enum class AppLanguage(val tag: String) {
    UKRAINIAN("uk"),
    ENGLISH("en"),
    SYSTEM(""),
}
