package com.vkurylo.shifttracker.data.repository

import android.content.Context
import androidx.core.os.LocaleListCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.domain.model.AppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/**
 * The language on the very first launch: the phone's language when the app has it (Ukrainian, English, Polish),
 * otherwise English. A language chosen before (in the app or in Android's per-app settings) is never changed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FirstLaunchLanguageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File.createTempFile("first-launch", ".preferences_pb").apply { delete(); deleteOnExit() }
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }

    /** The app's language setting (AppCompat's, which in a test without an activity keeps nothing on Android 13+). */
    private val appLocales = object : AppCompatLanguageRepository.AppLocales {
        var locales: LocaleListCompat = LocaleListCompat.getEmptyLocaleList()
        override fun get() = locales
        override fun set(locales: LocaleListCompat) {
            this.locales = locales
        }
    }

    @After
    fun tearDown() = scope.cancel()

    private fun firstLaunchOn(phone: Locale): AppLanguage = runBlocking {
        val repository = AppCompatLanguageRepository(context, dataStore, scope, { phone }, appLocales)
        repository.applyDefaultOnFirstLaunch()
        repository.current()
    }

    @Test
    fun aPhoneInALanguageTheAppHasStartsInIt() {
        assertEquals(AppLanguage.POLISH, firstLaunchOn(Locale.forLanguageTag("pl-PL")))
        assertEquals("pl", appLocales.locales.toLanguageTags())
    }

    @Test
    fun ukrainianAndEnglishPhonesToo() {
        assertEquals(AppLanguage.UKRAINIAN, firstLaunchOn(Locale.forLanguageTag("uk-UA")))
        // A fresh install again.
        appLocales.set(LocaleListCompat.getEmptyLocaleList())
        runBlocking { dataStore.edit { it.clear() } }
        assertEquals(AppLanguage.ENGLISH, firstLaunchOn(Locale.forLanguageTag("en-GB")))
    }

    @Test
    fun anyOtherLanguageStartsInEnglish() {
        assertEquals(AppLanguage.ENGLISH, firstLaunchOn(Locale.forLanguageTag("de-DE")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.forFirstLaunch(Locale.forLanguageTag("ru-RU")))
        assertEquals(AppLanguage.ENGLISH, AppLanguage.forFirstLaunch(null))
    }

    @Test
    fun aLanguageChosenBeforeIsKept() {
        appLocales.set(LocaleListCompat.forLanguageTags("uk"))
        assertEquals(AppLanguage.UKRAINIAN, firstLaunchOn(Locale.forLanguageTag("pl-PL")))
    }

    @Test
    fun anEarlierFirstLaunchIsNeverRedone() = runBlocking {
        // An existing install: its first launch was long ago, and "System" was chosen since (no app language).
        dataStore.edit { it[DataStorePreferencesRepository.LANGUAGE_INITIALIZED] = true }
        assertEquals(AppLanguage.SYSTEM, firstLaunchOn(Locale.forLanguageTag("pl-PL")))
        assertTrue(dataStore.data.first()[DataStorePreferencesRepository.LANGUAGE_INITIALIZED] == true)
    }
}
