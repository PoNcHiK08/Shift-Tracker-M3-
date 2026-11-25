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
import android.content.res.Resources
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
class AppCompatLanguageRepository internal constructor(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    /** The phone's own language (Android's system setting, not the app's). */
    private val phoneLocale: () -> Locale?,
    /** The app's own language list: AppCompat's per-app language setting. */
    private val appLocales: AppLocales = AppCompatLocales,
) : LanguageRepository {

    @Inject constructor(
        @ApplicationContext context: Context,
        dataStore: DataStore<Preferences>,
        @ApplicationScope scope: CoroutineScope,
    ) : this(context, dataStore, scope, { Resources.getSystem().configuration.locales[0] })

    /** Where the app's language is kept; tests stand in for AppCompat, which needs a live activity on Android 13+. */
    interface AppLocales {
        fun get(): LocaleListCompat
        fun set(locales: LocaleListCompat)
    }

    private object AppCompatLocales : AppLocales {
        override fun get(): LocaleListCompat = AppCompatDelegate.getApplicationLocales()
        override fun set(locales: LocaleListCompat) = AppCompatDelegate.setApplicationLocales(locales)
    }

    override fun current(): AppLanguage {
        val locales = appLocales.get()
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
        appLocales.set(list)
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
        return appLocales.get()[0]
    }

    /** Keeps the stored tag in step with AppCompat's (e.g. a language set before the widget existed). */
    suspend fun syncStoredTag() {
        val locales = appLocales.get()
        store(if (locales.isEmpty) "" else locales[0]?.toLanguageTag().orEmpty())
    }

    private suspend fun store(tag: String) {
        dataStore.edit { if (it[DataStorePreferencesRepository.APP_LANGUAGE] != tag) it[DataStorePreferencesRepository.APP_LANGUAGE] = tag }
    }

    /**
     * The very first launch: the phone's language when the app has it (Ukrainian, English, Polish), otherwise
     * English. Only once per install, and never over a language already chosen (in the app or in Android's
     * per-app language settings).
     */
    override suspend fun applyDefaultOnFirstLaunch() {
        val key = DataStorePreferencesRepository.LANGUAGE_INITIALIZED
        if (dataStore.data.first()[key] != true) {
            if (appLocales.get().isEmpty) set(AppLanguage.forFirstLaunch(phoneLocale()))
            dataStore.edit { it[key] = true }
        }
        syncStoredTag()
    }
}
