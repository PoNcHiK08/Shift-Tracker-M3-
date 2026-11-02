package com.vkurylo.shifttracker.domain.model

/** Colors a job can be tagged with. Chosen to stay distinguishable in light and dark themes. */
object JobPalette {
    val colors: List<Int> = listOf(
        0xFF1E88E5, // blue
        0xFF43A047, // green
        0xFFE53935, // red
        0xFFFB8C00, // orange
        0xFF8E24AA, // purple
        0xFF00ACC1, // cyan
        0xFFD81B60, // pink
        0xFFFDD835, // yellow
        0xFF6D4C41, // brown
        0xFF3949AB, // indigo
        0xFF00897B, // teal
        0xFF7CB342, // light green
    ).map { it.toInt() }

    val default: Int get() = colors.first()
}
