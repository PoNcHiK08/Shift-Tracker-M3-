package com.vkurylo.shifttracker.domain.model

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** App UI languages. [tag] is a BCP-47 tag; [SYSTEM] follows the phone language. */
enum class AppLanguage(val tag: String) {
    UKRAINIAN("uk"),
    ENGLISH("en"),
    SYSTEM(""),
}
