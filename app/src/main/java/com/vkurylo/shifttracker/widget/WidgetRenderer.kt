package com.vkurylo.shifttracker.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.compose

/**
 * Draws the widget straight into AppWidgetManager: composes [ShiftTrackerWidget] into RemoteViews
 * (for every size the launcher reports) and hands them over. Glance normally draws in a WorkManager job; that
 * job goes through JobScheduler, which drops jobs it rejects without a trace (the widget then stays on
 * its loading layout). Drawing here doesn't depend on it, so the widget always shows its content.
 * Its taps are plain activity PendingIntents, so they don't need Glance's per-widget state.
 */
@OptIn(ExperimentalGlanceApi::class)
object WidgetRenderer {

    suspend fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, ShiftTrackerWidgetReceiver::class.java))
        ids.forEach { render(context, manager, it) }
    }

    suspend fun render(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
        val widget = ShiftTrackerWidget()
        val options = manager.getAppWidgetOptions(appWidgetId) ?: Bundle()
        val views = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // One layout per size the launcher draws the widget at (portrait, landscape, ...), each laid
            // out for that exact size; the launcher shows the one that matches.
            RemoteViews(
                sizesFor(options).associate { size ->
                    SizeF(size.width.value, size.height.value) to widget.compose(context, options = options, size = size)
                },
            )
        } else {
            widget.compose(context, options = options, size = portraitSize(options) ?: DEFAULT_SIZE)
        }
        manager.updateAppWidget(appWidgetId, views)
    }

    /**
     * The sizes to draw for on Android 12+: the ones the launcher reports, else its min/max bounds, else
     * (nothing reported yet) every layout's smallest size, so the launcher can pick the largest that fits.
     */
    internal fun sizesFor(options: Bundle): List<DpSize> {
        @Suppress("DEPRECATION")
        val reported = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            ?.filter { it.width > 0f && it.height > 0f }
            ?.map { DpSize(it.width.dp, it.height.dp) }
        if (!reported.isNullOrEmpty()) return reported.distinct().take(MAX_SIZES)
        val bounds = listOfNotNull(portraitSize(options), landscapeSize(options)).distinct()
        return bounds.ifEmpty { WidgetLayout.BREAKPOINTS }
    }

    /** Portrait: the widget's min width and max height. */
    private fun portraitSize(options: Bundle): DpSize? = size(
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0),
    )

    /** Landscape: the widget's max width and min height. */
    private fun landscapeSize(options: Bundle): DpSize? = size(
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0),
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
    )

    private fun size(width: Int, height: Int): DpSize? = if (width > 0 && height > 0) DpSize(width.dp, height.dp) else null

    /** Before anything is reported: the 4×2 the widget is placed at. */
    private val DEFAULT_SIZE = DpSize(250.dp, 120.dp)

    /** RemoteViews accepts at most 16 sized layouts. */
    private const val MAX_SIZES = 16
}
