package com.vkurylo.shifttracker.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The widget's receiver. Besides the system's widget updates (every 30 min, see the widget info) it
 * redraws on clock, time zone and locale changes, so dates and "Today" catch up.
 */
class ShiftTrackerWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShiftTrackerWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        updater(context).setPlaced(true)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        updater(context).setPlaced(false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in REFRESH_ACTIONS) {
            val pending = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                try {
                    glanceAppWidget.updateAll(context)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    private fun updater(context: Context) =
        EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java).widgetUpdater()

    private companion object {
        val REFRESH_ACTIONS = setOf(
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            // Not delivered to manifest receivers on Android 8+ (not an exempt broadcast); harmless.
            Intent.ACTION_DATE_CHANGED,
        )
    }
}
