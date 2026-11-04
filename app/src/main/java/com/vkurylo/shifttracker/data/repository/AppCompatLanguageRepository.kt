package com.vkurylo.shifttracker.data.repository

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.vkurylo.shifttracker.domain.model.AppLanguage
import com.vkurylo.shifttracker.domain.repository.LanguageRepository
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import com.vkurylo.shifttracker.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppCompatLanguageRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
    @param:ApplicationScope private val scope: CoroutineScope,
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
        // Also kept for the widget (see appLocale); fire-and-forget, the platform setting is the truth.
        scope.launch { store(language.tag) }
    }

    override val changes: Flow<String?> = dataStore.data.map { it[DataStorePreferencesRepository.APP_LANGUAGE] }

    override suspend fun appLocale(): Locale? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The platform per-app locale, also right after a change in system Settings. Read from
            // LocaleManager directly: AppCompatDelegate needs a live activity to reach it.
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            return if (locales == null || locales.isEmpty) null else locales[0]
        }
        val stored = dataStore.data.first()[DataStorePreferencesRepository.APP_LANGUAGE]
        if (stored != null) return stored.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag)
        return AppCompatDelegate.getApplicationLocales()[0]
    }

    /** Keeps the stored tag in step with AppCompat's (e.g. a language set before the widget existed). */
    suspend fun syncStoredTag() {
        val locales = AppCompatDelegate.getApplicationLocales()
        store(if (locales.isEmpty) "" else locales[0]?.toLanguageTag().orEmpty())
    }

    private suspend fun store(tag: String) {
        dataStore.edit { if (it[DataStorePreferencesRepository.APP_LANGUAGE] != tag) it[DataStorePreferencesRepository.APP_LANGUAGE] = tag }
    }

    override suspend fun applyDefaultOnFirstLaunch() {
        val key = DataStorePreferencesRepository.LANGUAGE_INITIALIZED
        if (dataStore.data.first()[key] != true) {
            if (AppCompatDelegate.getApplicationLocales().isEmpty) set(AppLanguage.UKRAINIAN)
            dataStore.edit { it[key] = true }
        }
        syncStoredTag()
    }
}
