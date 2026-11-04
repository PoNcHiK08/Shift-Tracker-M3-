package com.vkurylo.shifttracker.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.vkurylo.shifttracker.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps placed widgets current while the app runs: every write (editors, .ics import, JSON import,
 * undo) goes through Room and DataStore, whose flows emit here, so no write path needs to know about
 * the widget. The state is rebuilt (debounced) and the widgets are redrawn only when it changed.
 * Nothing is collected while no widget is placed. Outside the app process the widget refreshes itself
 * (every 30 min, and on time / time zone / locale changes).
 */
@Singleton
class WidgetUpdater @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: WidgetRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val placed = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start() {
        scope.launch {
            placed.value = hasWidgets()
            placed.flatMapLatest { on -> if (on) repository.models() else emptyFlow() }
                .debounce(DEBOUNCE_MS)
                .distinctUntilChanged()
                // The first state is what the widget drew itself when it was (re)placed or refreshed.
                .drop(1)
                .collect { ShiftTrackerWidget().updateAll(context) }
        }
    }

    /** Called when the first widget is placed / the last one removed. */
    fun setPlaced(on: Boolean) {
        placed.value = on
    }

    private suspend fun hasWidgets(): Boolean =
        runCatching { GlanceAppWidgetManager(context).getGlanceIds(ShiftTrackerWidget::class.java).isNotEmpty() }.getOrDefault(false)

    private companion object {
        const val DEBOUNCE_MS = 500L
    }
}
