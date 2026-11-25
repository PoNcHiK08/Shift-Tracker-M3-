package com.vkurylo.shifttracker.domain.model

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val useDynamicColor: Boolean = true,
    val earningsMode: EarningsMode = EarningsMode.GROSS,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** App UI languages. [tag] is a BCP-47 tag; [SYSTEM] follows the phone language. */
enum class AppLanguage(val tag: String) {
    UKRAINIAN("uk"),
    ENGLISH("en"),
    POLISH("pl"),
    SYSTEM(""),
    ;

    companion object {
        /** The language of the very first launch: the phone's language when the app has it, otherwise English. */
        fun forFirstLaunch(phone: java.util.Locale?): AppLanguage =
            entries.firstOrNull { it != SYSTEM && it.tag == phone?.language } ?: ENGLISH
    }
}
