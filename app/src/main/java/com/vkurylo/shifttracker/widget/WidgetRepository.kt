package com.vkurylo.shifttracker.widget

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import com.vkurylo.shifttracker.domain.repository.BonusRepository
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.LanguageRepository
import com.vkurylo.shifttracker.domain.repository.PreferencesRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The widget plus the settings its look depends on. */
data class WidgetModel(val state: WidgetState, val dynamicColor: Boolean)

/**
 * Reads what the widget shows from the app's own repositories (same Room database and DataStore) and
 * builds it with [WidgetStateBuilder]. Used by the widget itself and by [WidgetUpdater].
 */
@Singleton
class WidgetRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val shifts: ShiftRepository,
    private val bonuses: BonusRepository,
    private val jobs: JobRepository,
    private val preferences: PreferencesRepository,
    private val language: LanguageRepository,
) {
    /**
     * The widget for "now", re-emitted on every change of shifts, bonuses, jobs, settings or the app
     * language. Its date window is fixed when collection starts (wide enough to cover a day change).
     */
    fun models(clock: () -> LocalDateTime = LocalDateTime::now): Flow<WidgetModel> {
        val today = clock().toLocalDate()
        val from = minOf(YearMonth.from(today).minusMonths(1).atDay(1), today.minusDays(1))
        val to = maxOf(YearMonth.from(today).plusMonths(1).atEndOfMonth(), today.plusDays(WidgetInput.LOOK_AHEAD_DAYS + 1))
        return combine(
            shifts.observeShifts(from, to),
            bonuses.observeBonuses(from, to),
            jobs.observeJobs(),
            preferences.preferences,
            language.changes,
        ) { shiftList, bonusList, jobList, prefs, _ ->
            val now = clock()
            val month = YearMonth.from(now)
            val locale = language.appLocale() ?: systemLocale()
            val input = WidgetInput(
                now = now,
                jobs = jobList,
                monthShifts = shiftList.filter { YearMonth.from(it.shift.date) == month },
                monthBonuses = bonusList.filter { YearMonth.from(it.bonus.date) == month },
                upcoming = shiftList.filter { !it.shift.date.isBefore(now.toLocalDate().minusDays(1)) },
                mode = prefs.earningsMode,
                hideAmounts = prefs.hideWidgetAmounts,
                locale = locale,
            )
            WidgetModel(WidgetStateBuilder.build(input, textFor(locale)), prefs.useDynamicColor)
        }
    }

    /** One fresh model (the widget's own refresh). */
    suspend fun current(): WidgetModel = models().first()

    /** Strings in the app's language, whatever the system language is (AppCompat only localizes activities). */
    private fun textFor(locale: Locale): WidgetText {
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        val resources = context.createConfigurationContext(config).resources
        return WidgetText { id, args -> resources.getString(id, *args) }
    }

    private fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0] ?: Locale.getDefault()
}
