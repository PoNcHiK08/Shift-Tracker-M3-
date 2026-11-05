package com.vkurylo.shifttracker.widget

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.util.SizeF
import android.appwidget.AppWidgetManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import javax.inject.Inject

/**
 * The widget at every One UI grid size (Galaxy S23+ density, Ukrainian): laid out at that exact size,
 * the calendar (month, or this week in one row), the next shift and the add action are on screen,
 * unclipped and with non-zero bounds, no day number is ellipsized, no amount is shown anywhere, a
 * 1-row widget is one row (never blank, never stacked) and a 2×2 is a column.
 */
@OptIn(ExperimentalGlanceApi::class)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = HiltTestApplication::class, sdk = [35], qualifiers = "uk-rUA-xxhdpi")
class WidgetSizesTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)
    @Inject lateinit var jobs: JobRepository
    @Inject lateinit var shifts: ShiftRepository
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val density get() = context.resources.displayMetrics.density
    private var jobId = 0L
    private val today = LocalDate.now()
    private val firstDay = YearMonth.from(today).atDay(1)

    private val add1Row = "+"
    private val addTall = "+ Зміна"

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            jobId = jobs.upsert(Job(name = "Burger King", hourlyRateGrosze = 3400, color = 0xFFE53935.toInt()))
            shifts.upsert(shift(today.plusDays(1), 18, 0, 1, 30)) // the next shift
            shifts.upsert(shift(firstDay, 8, 0, 16, 0)) // a shift day in the calendar
        }
    }

    private fun shift(date: LocalDate, h1: Int, m1: Int, h2: Int, m2: Int) =
        Shift(jobId = jobId, date = date, start = LocalTime.of(h1, m1), end = LocalTime.of(h2, m2), hourlyRateGrosze = 3400)

    private data class Drawn(val root: View, val texts: List<TextView>) {
        fun find(text: String) = texts.firstOrNull { it.text.toString().normalized() == text }
    }

    /** Composes the widget for [size] and lays it out at exactly that size, as the launcher would. */
    private fun draw(size: DpSize): Drawn = runBlocking {
        val views = ShiftTrackerWidget().compose(context, size = size)
        val parent = FrameLayout(context)
        val root = views.apply(context, parent)
        parent.addView(root)
        val w = (size.width.value * density).toInt()
        val h = (size.height.value * density).toInt()
        parent.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        parent.layout(0, 0, w, h)
        val texts = mutableListOf<TextView>()
        fun collect(v: View) {
            if (v is TextView && v.visibility == View.VISIBLE) texts += v
            if (v is ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
        }
        collect(parent)
        Drawn(parent, texts)
    }

    /** [view]'s bounds in the widget, and the part of them its ancestors don't clip away. */
    private fun bounds(view: View, root: View): Pair<Rect, Rect> {
        val rect = Rect(0, 0, view.width, view.height)
        val visible = Rect(rect)
        var v: View = view
        while (v !== root) {
            val parent = v.parent as View
            rect.offset(v.left - parent.scrollX, v.top - parent.scrollY)
            visible.offset(v.left - parent.scrollX, v.top - parent.scrollY)
            if (!visible.intersect(0, 0, parent.width, parent.height)) visible.setEmpty()
            v = parent
        }
        return rect to visible
    }

    /** On screen, not clipped, with real size. */
    private fun assertShown(drawn: Drawn, text: String, where: String): TextView {
        val view = drawn.find(text)
        assertNotNull("$where: '$text' missing in ${drawn.texts.map { it.text }}", view)
        val (rect, visible) = bounds(view!!, drawn.root)
        assertTrue("$where: '$text' has no size: $rect", rect.width() > 0 && rect.height() > 0)
        assertEquals("$where: '$text' is clipped", rect, visible)
        return view
    }

    /** Laid out with a width the whole text fits in: no "1 477,00 ...". */
    private fun assertNotTruncated(view: TextView, where: String) {
        val layout = view.layout
        assertNotNull("$where: '${view.text}' not laid out", layout)
        assertEquals("$where: '${view.text}' is ellipsized", 0, layout.getEllipsisCount(0))
        val room = view.width - view.totalPaddingLeft - view.totalPaddingRight
        assertTrue("$where: '${view.text}' needs ${view.paint.measureText(view.text.toString())} px, has $room", view.paint.measureText(view.text.toString()) <= room + 0.5f)
    }

    private fun assertClickable(view: View, where: String) {
        val tappable = generateSequence(view) { it.parent as? View }.any { it.hasOnClickListeners() }
        assertTrue("$where: '${(view as? TextView)?.text}' isn't tappable", tappable)
    }

    private fun checkAdd(drawn: Drawn, add: String, where: String) = assertClickable(assertShown(drawn, add, where), where)

    /** No money anywhere: the widget is a calendar now. */
    private fun assertNoAmounts(drawn: Drawn, where: String) =
        assertTrue("$where shows an amount: ${drawn.texts.map { it.text }}", drawn.texts.none { "zł" in it.text })

    /** Every day of the month is drawn whole, and today is among them. */
    private fun assertMonth(drawn: Drawn, where: String) {
        assertShown(drawn, monthTitle, where)
        assertShown(drawn, "Пн", where)
        for (day in 1..YearMonth.from(today).lengthOfMonth()) assertNotTruncated(assertShown(drawn, day.toString(), where), "$where day $day")
    }

    private val monthTitle = YearMonth.from(today).month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, java.util.Locale.forLanguageTag("uk"))
        .replaceFirstChar { it.titlecase() }

    @Test
    fun x2x1CompactRow() {
        val drawn = draw(DpSize(150.dp, 70.dp))
        checkAdd(drawn, add1Row, "2x1")
        assertNotTruncated(assertShown(drawn, "Burger King", "2x1"), "2x1")
        // Too narrow for "Завтра · 18:00–01:30" on one line: two lines, both whole.
        assertNotTruncated(assertShown(drawn, "Завтра", "2x1"), "2x1")
        assertNotTruncated(assertShown(drawn, "18:00–01:30", "2x1"), "2x1")
        assertNoAmounts(drawn, "2x1")
    }

    @Test
    fun x4x1IsOneWideRowWithThisWeek() {
        val drawn = draw(DpSize(350.dp, 70.dp))
        checkAdd(drawn, add1Row, "4x1")
        val shift = assertShown(drawn, "Burger King", "4x1")
        assertNotTruncated(assertShown(drawn, "Завтра · 18:00–01:30", "4x1"), "4x1")
        assertNull("4x1 must not stack a '+ Зміна' pill", drawn.find(addTall))
        assertNoAmounts(drawn, "4x1")
        // This week, Monday to Sunday, each day whole.
        assertShown(drawn, "Пн", "4x1")
        assertShown(drawn, "Нд", "4x1")
        val week = WidgetStateBuilder.weekOf(today)
        week.forEach { assertNotTruncated(assertShown(drawn, it.dayOfMonth.toString(), "4x1"), "4x1 ${it}") }
        // One row: the week tile is left of the shift tile, which is left of "+".
        val sunday = bounds(drawn.find(week.last().dayOfMonth.toString())!!, drawn.root).first
        val name = bounds(shift, drawn.root).first
        val plus = bounds(drawn.find(add1Row)!!, drawn.root).first
        assertTrue("4x1 order: $sunday $name $plus", sunday.right <= name.left && name.right <= plus.left)
    }

    @Test
    fun x2x2StacksVertically() {
        val drawn = draw(DpSize(150.dp, 160.dp))
        checkAdd(drawn, addTall, "2x2")
        assertMonth(drawn, "2x2")
        assertNoAmounts(drawn, "2x2")
        val lastDay = bounds(drawn.find(YearMonth.from(today).lengthOfMonth().toString())!!, drawn.root).first
        val pill = bounds(pillOf(drawn.find(addTall)!!), drawn.root).first
        assertTrue("2x2: the month ($lastDay) sits above the pill ($pill)", lastDay.bottom <= pill.top)
        assertTrue("2x2: the pill is full width ($pill of ${drawn.root.width})", pill.width() >= drawn.root.width * 0.8)
    }

    @Test
    fun x2x3AlsoShowsTheNextShift() {
        val drawn = draw(DpSize(150.dp, 250.dp))
        assertMonth(drawn, "2x3")
        assertShown(drawn, "Burger King", "2x3")
        checkAdd(drawn, addTall, "2x3")
    }

    @Test
    fun x3x2MonthAndNextShift() {
        val drawn = draw(DpSize(250.dp, 160.dp))
        checkAdd(drawn, addTall, "3x2")
        assertMonth(drawn, "3x2")
        assertShown(drawn, "Burger King", "3x2")
        assertNoAmounts(drawn, "3x2")
    }

    @Test
    fun x4x2Bento() {
        val drawn = draw(DpSize(350.dp, 160.dp))
        checkAdd(drawn, addTall, "4x2")
        assertMonth(drawn, "4x2")
        assertShown(drawn, "Burger King", "4x2")
        assertShown(drawn, "18:00–01:30", "4x2")
        // The month is on the left, the shift card on the right.
        val month = bounds(drawn.find(monthTitle)!!, drawn.root).first
        val name = bounds(drawn.find("Burger King")!!, drawn.root).first
        assertTrue("4x2: $month | $name", month.right <= name.left)
    }

    @Test
    fun theLayoutFollowsWidthAndHeight() {
        assertEquals(WidgetLayout.COMPACT_ROW, WidgetLayout.of(DpSize(150.dp, 70.dp)))
        assertEquals(WidgetLayout.WIDE_ROW, WidgetLayout.of(DpSize(260.dp, 105.dp)))
        assertEquals(WidgetLayout.WIDE_ROW, WidgetLayout.of(DpSize(350.dp, 70.dp)))
        assertEquals(WidgetLayout.NARROW_STACK, WidgetLayout.of(DpSize(150.dp, 160.dp)))
        assertEquals(WidgetLayout.NARROW_STACK, WidgetLayout.of(DpSize(150.dp, 260.dp)))
        assertEquals(WidgetLayout.BENTO, WidgetLayout.of(DpSize(250.dp, 160.dp)))
        assertEquals(WidgetLayout.BENTO, WidgetLayout.of(DpSize(350.dp, 260.dp)))
    }

    @Test
    fun theRendererDrawsForTheSizesTheLauncherReports() {
        val reported = Bundle().apply {
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(350f, 70f), SizeF(410f, 60f)))
        }
        assertEquals(listOf(DpSize(350.dp, 70.dp), DpSize(410.dp, 60.dp)), WidgetRenderer.sizesFor(reported))
        val bounds = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 350)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 410)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 60)
        }
        assertEquals(listOf(DpSize(350.dp, 90.dp), DpSize(410.dp, 60.dp)), WidgetRenderer.sizesFor(bounds))
        assertEquals(WidgetLayout.BREAKPOINTS, WidgetRenderer.sizesFor(Bundle()))
    }

    /** The pill's own box (the text sits centred inside it). */
    private fun pillOf(text: View): View = text.parent as View

    private fun assertNull(message: String, value: Any?) = assertTrue(message, value == null)
}

private fun String.normalized() = replace(' ', ' ').replace(' ', ' ')
