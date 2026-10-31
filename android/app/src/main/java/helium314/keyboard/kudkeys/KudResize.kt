// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.kudkeys

/**
 * The "Select your size" screen's geometry, in pixels, on top of HeliBoard's three scales:
 * height (× the default keyboard height), side padding (× [sidePaddingFraction] of the width, both sides) and
 * bottom padding (× [bottomPaddingFraction] of the keyboard height, inside the keyboard below the keys).
 *
 * Handles move edges of the keys area: top changes the height, left/right the side padding, bottom the bottom
 * padding (the top edge stays), and the centre handle moves the keys up or down without changing their size.
 */
data class KudResize(
    val heightScale: Float,
    val sideScale: Float,
    val bottomScale: Float,
) {
    class Metrics(
        val width: Float,
        val defaultHeight: Float,
        val sidePaddingFraction: Float,
        val bottomPaddingFraction: Float,
    )

    fun keyboardHeight(m: Metrics) = m.defaultHeight * heightScale
    fun sidePadding(m: Metrics) = m.width * m.sidePaddingFraction * sideScale
    fun bottomPadding(m: Metrics) = keyboardHeight(m) * m.bottomPaddingFraction * bottomScale
    /** Height of the keys area (keyboard minus bottom padding). */
    fun keysHeight(m: Metrics) = keyboardHeight(m) - bottomPadding(m)

    /** Drag the top edge by [dy] px (negative = up = taller keys). The bottom padding keeps its pixels. */
    fun dragTop(dy: Float, m: Metrics): KudResize {
        val pad = bottomPadding(m)
        val newHeight = (keyboardHeight(m) - dy).coerceIn(HEIGHT.start * m.defaultHeight, HEIGHT.endInclusive * m.defaultHeight)
        return withHeightAndPad(newHeight, pad, m)
    }

    /** Drag the bottom edge of the keys by [dy] px (negative = up = shorter keys, more space below). */
    fun dragBottom(dy: Float, m: Metrics): KudResize {
        val height = keyboardHeight(m)
        return withHeightAndPad(height, bottomPadding(m) - dy, m)
    }

    /** Drag the left or right edge; [inward] px towards the centre (negative = outwards). Both sides move. */
    fun dragSide(inward: Float, m: Metrics): KudResize {
        if (m.sidePaddingFraction <= 0f) return this
        val newPad = (sidePadding(m) + inward).coerceAtLeast(0f)
        return copy(sideScale = (newPad / (m.width * m.sidePaddingFraction)).coerceIn(SIDE))
    }

    /** Move the keys by [dy] px (negative = up), keeping their size: the bottom padding takes up the difference. */
    fun move(dy: Float, m: Metrics): KudResize {
        val keys = keysHeight(m)
        val maxPad = BOTTOM.endInclusive * m.bottomPaddingFraction * HEIGHT.endInclusive * m.defaultHeight
        val newPad = (bottomPadding(m) - dy).coerceIn(0f, maxPad)
        val newHeight = (keys + newPad).coerceIn(HEIGHT.start * m.defaultHeight, HEIGHT.endInclusive * m.defaultHeight)
        return withHeightAndPad(newHeight, newHeight - keys, m)
    }

    private fun withHeightAndPad(height: Float, pad: Float, m: Metrics): KudResize {
        val h = (height / m.defaultHeight).coerceIn(HEIGHT)
        val b = if (m.bottomPaddingFraction <= 0f) bottomScale
            else (pad.coerceAtLeast(0f) / (height * m.bottomPaddingFraction)).coerceIn(BOTTOM)
        return copy(heightScale = h, bottomScale = b)
    }

    companion object {
        /** Same ranges as HeliBoard's Appearance sliders. */
        val HEIGHT = 0.3f..1.5f
        val SIDE = 0f..3f
        val BOTTOM = 0f..5f
        val DEFAULT = KudResize(1f, 0f, 1f)
    }
}
