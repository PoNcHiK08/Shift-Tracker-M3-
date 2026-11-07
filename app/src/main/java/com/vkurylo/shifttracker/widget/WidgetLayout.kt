package com.vkurylo.shifttracker.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * The widget's layout, chosen from BOTH its width and its height (tuned for One UI, where one row is
 * ~60–105 dp tall and two rows 120 dp+).
 */
enum class WidgetLayout {
    /** 1 row, narrow (2×1): earnings + hours, round "+". */
    COMPACT_ROW,

    /** 1 row, wide (3×1, 4×1): earnings | next shift | round "+", side by side, never stacked. */
    WIDE_ROW,

    /** 2+ rows, narrow (2×2, 2×3): earnings card on top, next shift and a full-width "+ Shift" below. */
    NARROW_STACK,

    /** 2+ rows, wide (3×2, 4×2, 4×3): earnings card | next shift card over "+ Shift". */
    BENTO;

    companion object {
        val TALL_FROM = 115.dp
        val WIDE_ROW_FROM = 240.dp
        val BENTO_FROM = 230.dp

        fun of(size: DpSize): WidgetLayout = when {
            size.height < TALL_FROM -> if (size.width < WIDE_ROW_FROM) COMPACT_ROW else WIDE_ROW
            else -> if (size.width < BENTO_FROM) NARROW_STACK else BENTO
        }

        /**
         * The smallest size of each layout: what the widget is drawn for when the launcher hasn't told
         * it its real sizes (it then picks the largest of these that fits).
         */
        val BREAKPOINTS = listOf(
            DpSize(110.dp, 48.dp),
            DpSize(WIDE_ROW_FROM, 48.dp),
            DpSize(110.dp, TALL_FROM),
            DpSize(BENTO_FROM, TALL_FROM),
        )
    }
}

/**
 * Picks the largest font size (in sp) at which every one of [texts] fits on one line in [width] and
 * [lineHeight], measured with the system font and font scale (the launcher draws with the same), so
 * an amount like "22 234,01 zł" shrinks instead of turning into "22 234,0…".
 */
class WidgetTextFit(context: Context) {
    private val density = context.resources.displayMetrics.density
    private val fontScale = context.resources.configuration.fontScale
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun sp(texts: List<String>, width: Dp, lineHeight: Dp, maxSp: Float, minSp: Float, bold: Boolean): Float {
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        val maxWidth = width.value * density * WIDTH_SAFETY
        val maxHeight = lineHeight.value * density
        var sp = maxSp
        while (sp > minSp) {
            paint.textSize = sp * fontScale * density
            val metrics = paint.fontMetrics
            if (metrics.bottom - metrics.top <= maxHeight && texts.all { paint.measureText(it) <= maxWidth }) return sp
            sp -= 0.5f
        }
        return minSp
    }

    /** Whether [text] at [sp] fits [width] on one line ([sp] may be a minimum that [sp] gave up at). */
    fun fits(text: String, width: Dp, sp: Float, bold: Boolean): Boolean {
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textSize = sp * fontScale * density
        return paint.measureText(text) <= width.value * density * WIDTH_SAFETY
    }

    /** The height a one-line text of [sp] takes (with the TextView's font padding). */
    fun lineHeight(sp: Float): Dp {
        paint.typeface = Typeface.DEFAULT
        paint.textSize = sp * fontScale * density
        val metrics = paint.fontMetrics
        return ((metrics.bottom - metrics.top) / density).dp
    }

    private companion object {
        /** Room for a launcher font that is a little wider than ours. */
        const val WIDTH_SAFETY = 0.92f
    }
}
