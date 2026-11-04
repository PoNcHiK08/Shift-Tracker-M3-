package com.vkurylo.shifttracker.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vkurylo.shifttracker.domain.model.EarningsMode
import com.vkurylo.shifttracker.domain.model.ThemeMode
import com.vkurylo.shifttracker.domain.model.UserPreferences
import com.vkurylo.shifttracker.domain.repository.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DataStorePreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : PreferencesRepository {

    override val preferences: Flow<UserPreferences> = dataStore.data.map { prefs ->
        UserPreferences(
            themeMode = prefs[THEME_MODE]
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: ThemeMode.SYSTEM,
            useDynamicColor = prefs[DYNAMIC_COLOR] ?: true,
            earningsMode = prefs[EARNINGS_MODE]
                ?.let { name -> EarningsMode.entries.firstOrNull { it.name == name } }
                ?: EarningsMode.GROSS,
            hideWidgetAmounts = prefs[HIDE_WIDGET_AMOUNTS] ?: false,
        )
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setEarningsMode(mode: EarningsMode) {
        dataStore.edit { it[EARNINGS_MODE] = mode.name }
    }

    override suspend fun setHideWidgetAmounts(hide: Boolean) {
        dataStore.edit { it[HIDE_WIDGET_AMOUNTS] = hide }
    }

    internal companion object {
        val HIDE_WIDGET_AMOUNTS = booleanPreferencesKey("hide_widget_amounts")
        /** The app language tag as last set from the app; read by the widget before Android 13. */
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val EARNINGS_MODE = stringPreferencesKey("earnings_mode")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LANGUAGE_INITIALIZED = booleanPreferencesKey("language_initialized")
    }
}
