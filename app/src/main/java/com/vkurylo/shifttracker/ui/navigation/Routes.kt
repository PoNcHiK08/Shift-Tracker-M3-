package com.vkurylo.shifttracker.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object CalendarRoute
@Serializable data object SummaryRoute
@Serializable data object SettingsRoute
@Serializable data object JobsRoute

/**
 * [shiftId] = 0 creates a new shift on [dateEpochDay] (or today when [NO_DATE]).
 * [sharedKey] names the card/button the editor expands from (container transform); null means
 * the editor slides in like any other screen.
 */
@Serializable
data class EditShiftRoute(
    val shiftId: Long = 0L,
    val dateEpochDay: Long = NO_DATE,
    val sharedKey: String? = null,
) {
    companion object {
        const val NO_DATE = Long.MIN_VALUE
    }
}

/** Preview + confirm screen for a picked .ics file. */
@Serializable
data class ImportIcsRoute(val uri: String)

/**
 * [bonusId] = 0 creates a new bonus on [dateEpochDay] (or today when [EditShiftRoute.NO_DATE]). With [wholeMonth]
 * the new bonus is for the whole month of that date (the editor opens in its "whole month" mode).
 */
@Serializable
data class EditBonusRoute(
    val bonusId: Long = 0L,
    val dateEpochDay: Long = EditShiftRoute.NO_DATE,
    val sharedKey: String? = null,
    val wholeMonth: Boolean = false,
)

/** Keys that tie a card or button to the editor it expands into. */
object SharedKeys {
    fun shift(id: Long) = "shift-$id"
    fun bonus(id: Long) = "bonus-$id"
    const val NEW_SHIFT = "shift-new"
    const val NEW_BONUS = "bonus-new"
}
