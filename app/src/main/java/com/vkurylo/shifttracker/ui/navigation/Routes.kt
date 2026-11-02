package com.vkurylo.shifttracker.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object CalendarRoute
@Serializable data object SummaryRoute
@Serializable data object SettingsRoute
@Serializable data object JobsRoute

/** [shiftId] = 0 creates a new shift on [dateEpochDay] (or today when [NO_DATE]). */
@Serializable
data class EditShiftRoute(
    val shiftId: Long = 0L,
    val dateEpochDay: Long = NO_DATE,
) {
    companion object {
        const val NO_DATE = Long.MIN_VALUE
    }
}

/** Preview + confirm screen for a picked .ics file. */
@Serializable
data class ImportIcsRoute(val uri: String)
