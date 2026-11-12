package com.vkurylo.shifttracker.domain.model

/**
 * The longest job name or note: [MAX_LENGTH] characters as Kotlin counts them (UTF-16 units, String.length).
 * firestore.rules allows exactly this much and counts the same way (an emoji is 2), so nothing the editors
 * let through can stop a sync. A test checks the two numbers match.
 */
object TextLimits {
    const val MAX_LENGTH = 20_000

    /** The editors show how long the text is from this length on. */
    const val SHOW_COUNT_FROM = MAX_LENGTH - 2_000

    /** [text] cut to at most [max] characters, never between the two halves of an emoji. */
    fun cut(text: String, max: Int = MAX_LENGTH): String {
        if (text.length <= max) return text
        val end = if (max > 0 && Character.isHighSurrogate(text[max - 1])) max - 1 else max
        return text.substring(0, end)
    }

    /**
     * What a field holding [old] keeps when the user changes it to [new]: anything up to [max]; a text already
     * over it (saved before the limit existed) may only get shorter; a paste that goes over is cut to fit.
     */
    fun limitInput(old: String, new: String, max: Int = MAX_LENGTH): String = when {
        new.length <= max -> new
        new.length <= old.length -> new
        old.length >= max -> old
        else -> cut(new, max)
    }
}
