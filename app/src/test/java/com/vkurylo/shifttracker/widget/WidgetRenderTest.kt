package com.vkurylo.shifttracker.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.vkurylo.shifttracker.R
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vkurylo.shifttracker.domain.model.Job
import com.vkurylo.shifttracker.domain.model.Shift
import com.vkurylo.shifttracker.domain.repository.JobRepository
import com.vkurylo.shifttracker.domain.repository.ShiftRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

/** The widget really renders (data through the Hilt EntryPoint, Glance -> RemoteViews) in both sizes. */
@OptIn(ExperimentalGlanceApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(application = HiltTestApplication::class, sdk = [35], qualifiers = "en-rUS")
class WidgetRenderTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)
    @Inject lateinit var jobs: JobRepository
    @Inject lateinit var shifts: ShiftRepository
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            val job = jobs.upsert(Job(name = "Kawiarnia", hourlyRateGrosze = 3050, color = 0xFF43A047.toInt()))
            shifts.upsert(Shift(jobId = job, date = LocalDate.now().plusDays(1), start = LocalTime.of(8, 0), end = LocalTime.of(16, 0), hourlyRateGrosze = 3050))
        }
    }

    @Test
    fun rendersMediumAndCompact() = runBlocking {
        for (size in listOf(DpSize(300.dp, 150.dp), DpSize(130.dp, 60.dp))) {
            assertNotNull(ShiftTrackerWidget().compose(context, size = size))
        }
    }

    /**
     * The direct path a placed widget is drawn by (no WorkManager job): the RemoteViews reach
     * AppWidgetManager and inflate into the widget's content, not its loading layout.
     */
    @Test
    fun aPlacedWidgetIsDrawnWithoutABackgroundJob() = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        val shadow = shadowOf(manager)
        val id = shadow.createWidget(ShiftTrackerWidgetReceiver::class.java, R.layout.widget_loading)
        WidgetRenderer.render(context, manager, id)
        // No size reported yet: drawn for every layout's breakpoint; the smallest has the next shift and "+".
        val compact = texts(shadow.getViewFor(id))
        assertTrue(compact.toString(), compact.containsAll(listOf("Kawiarnia", "+")))

        // The 4×2 layout inflates with the next shift.
        val medium = ShiftTrackerWidget().compose(context, size = DpSize(250.dp, 120.dp))
        val texts = texts(medium.apply(context, FrameLayout(context)))
        assertTrue(texts.toString(), "Kawiarnia" in texts && "08:00–16:00" in texts && "+ Shift" in texts)
        // ... next to the month.
        val month = java.time.YearMonth.now()
        assertTrue(texts.toString(), month.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, java.util.Locale.US) in texts)
        assertTrue(texts.toString(), (1..month.lengthOfMonth()).all { it.toString() in texts })
    }

    /** The observer, the 30 min update and a resize firing together: all finish (no deadlock), and the widget is drawn. */
    @Test
    fun concurrentRendersAllFinish() = runBlocking {
        val manager = AppWidgetManager.getInstance(context)
        val shadow = shadowOf(manager)
        val id = shadow.createWidget(ShiftTrackerWidgetReceiver::class.java, R.layout.widget_loading)
        withTimeout(60_000) {
            (1..3).map {
                async(Dispatchers.Default) {
                    if (it % 2 == 0) WidgetRenderer.render(context, manager, id) else WidgetRenderer.renderAll(context)
                }
            }.forEach { it.await() }
        }
        assertTrue("Kawiarnia" in texts(shadow.getViewFor(id)))
    }

    private fun texts(view: View): List<String> {
        val out = mutableListOf<String>()
        fun collect(v: View) {
            if (v is TextView) out += v.text.toString()
            if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
        }
        collect(view)
        return out
    }
}
