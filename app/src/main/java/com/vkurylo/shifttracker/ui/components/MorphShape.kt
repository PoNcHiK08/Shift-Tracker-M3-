package com.vkurylo.shifttracker.ui.components

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph

/**
 * A shape between two [androidx.graphics.shapes.RoundedPolygon]s: [progress] 0 is the start shape,
 * 1 the end shape. The outline is scaled to fill the layout bounds.
 */
class MorphShape(private val morph: Morph, private val progress: Float) : Shape {
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress.coerceIn(0f, 1f), Path())
        val bounds = path.getBounds()
        if (bounds.width <= 0f || bounds.height <= 0f) return Outline.Rectangle(size.toRect())
        path.translate(Offset(-bounds.left, -bounds.top))
        path.transform(Matrix().apply { scale(size.width / bounds.width, size.height / bounds.height) })
        return Outline.Generic(path)
    }

    override fun equals(other: Any?): Boolean =
        other is MorphShape && other.morph === morph && other.progress == progress

    override fun hashCode(): Int = 31 * morph.hashCode() + progress.hashCode()
}

private fun Size.toRect() = androidx.compose.ui.geometry.Rect(Offset.Zero, this)

/** M3 Expressive circle -> square ("squircle": the Material square has 30 % corner rounding). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val CircleToSquircle: Morph by lazy { Morph(MaterialShapes.Circle, MaterialShapes.Square) }
