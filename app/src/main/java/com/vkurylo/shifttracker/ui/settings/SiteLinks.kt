package com.vkurylo.shifttracker.ui.settings

/**
 * The pages on the app's public site ([com.vkurylo.shifttracker.BuildConfig.SITE_URL], Firebase Hosting), opened in
 * the app's language when the site has it (Ukrainian, English, Polish), else in English. Null without a site.
 */
internal object SiteLinks {
    private val LANGUAGES = setOf("uk", "en", "pl")

    fun privacy(site: String, language: String): String? = page(site, "privacy", language)

    fun deleteAccount(site: String, language: String): String? = page(site, "delete-account", language)

    private fun page(site: String, path: String, language: String): String? {
        if (site.isBlank()) return null
        val lang = language.lowercase().takeIf { it in LANGUAGES } ?: "en"
        return "${site.trimEnd('/')}/$path?lang=$lang"
    }
}
