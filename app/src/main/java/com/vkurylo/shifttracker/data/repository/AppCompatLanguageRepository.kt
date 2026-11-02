package com.vkurylo.shifttracker.data.repository

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.vkurylo.shifttracker.domain.model.AppLanguage
import com.vkurylo.shifttracker.domain.repository.LanguageRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppCompatLanguageRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : LanguageRepository {

    override fun current(): AppLanguage {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return AppLanguage.SYSTEM
        val language = locales[0]?.language
        return AppLanguage.entries.firstOrNull { it.tag.isNotEmpty() && it.tag == language } ?: AppLanguage.SYSTEM
    }

    override fun set(language: AppLanguage) {
        val list = if (language == AppLanguage.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.tag)
        }
        AppCompatDelegate.setApplicationLocales(list)
    }

    override suspend fun applyDefaultOnFirstLaunch() {
        val key = DataStorePreferencesRepository.LANGUAGE_INITIALIZED
        if (dataStore.data.first()[key] == true) return
        if (AppCompatDelegate.getApplicationLocales().isEmpty) set(AppLanguage.UKRAINIAN)
        dataStore.edit { it[key] = true }
    }
}
