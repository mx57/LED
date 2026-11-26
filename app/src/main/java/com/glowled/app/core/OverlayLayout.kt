package com.glowled.app.core

import kotlin.math.max
import kotlin.math.roundToInt

enum class PeekPosition(val key: String) {
    BELOW("below"),
    ABOVE("above"),
    LEFT("left"),
    RIGHT("right"),
    AUTO("auto");

    companion object {
        fun from(key: String?): PeekPosition = entries.firstOrNull { it.key == key } ?: BELOW
    }
}

/**
 * Pure geometry calculator for placing the app icon strip ([infoRect]) and notification peek
 * plate ([peekRect]) relative to the LED ring without ever overflowing screen edges.
 */
object OverlayLayout {

    data class RectI(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = (right - left).coerceAtLeast(0)
        val height: Int get() = (bottom - top).coerceAtLeast(0)
    }

    data class Input(
        val screenW: Int,
        val screenH: Int,
        val marginPx: Int,
        val safeTopPx: Int = marginPx,
        val ringCx: Float,
        val ringCy: Float,
        val ringExtentPx: Int,
        val indicatorBottom: Float,
        val infoW: Int,
        val infoH: Int,
        val showInfo: Boolean,
        val peekW: Int,
        val peekH: Int,
        val showPeek: Boolean,
        val position: PeekPosition,
        val offsetPx: Int,
        val customInfoCx: Float? = null,
        val customInfoCy: Float? = null,
    )

    data class Result(
        val resolvedPosition: PeekPosition,
        val infoRect: RectI,
        val peekRect: RectI,
        val windowBounds: RectI,
    )

    fun compute(input: Input): Result {
        val marginX = input.marginPx.coerceAtLeast(0)
        val topMargin = max(marginX, input.safeTopPx.coerceAtLeast(0))
        val bottomMargin = marginX

        val ringLeft = (input.ringCx - input.ringExtentPx).roundToInt()
        val ringRight = (input.ringCx + input.ringExtentPx).roundToInt()
        val ringTop = (input.ringCy - input.ringExtentPx).roundToInt()
        val ringBottom = max(input.indicatorBottom, input.ringCy + input.ringExtentPx).roundToInt()

        val infoGap = if (input.showInfo) input.infoH + input.offsetPx else 0
        val side = if (input.position == PeekPosition.AUTO) {
            resolveAutoSide(
                screenW = input.screenW,
                screenH = input.screenH,
                marginX = marginX,
                topMargin = topMargin,
                bottomMargin = bottomMargin,
                ringLeft = ringLeft,
                ringTop = ringTop,
                ringRight = ringRight,
                ringBottom = ringBottom,
                infoGap = infoGap,
                peekW = input.peekW,
                peekH = input.peekH,
                offsetPx = input.offsetPx,
            )
        } else {
            input.position
        }

        val rawInfoX: Float
        val rawInfoY: Float
        val rawPeekX: Float
        val rawPeekY: Float

        when (side) {
            PeekPosition.ABOVE -> {
                rawInfoX = input.ringCx - input.infoW / 2f
                rawInfoY = (ringTop - input.offsetPx - input.infoH).toFloat()
                rawPeekX = input.ringCx - input.peekW / 2f
                rawPeekY = if (input.showInfo) {
                    rawInfoY - input.offsetPx - input.peekH
                } else {
                    (ringTop - input.offsetPx - input.peekH).toFloat()
                }
            }
            PeekPosition.LEFT -> {
                rawPeekX = (ringLeft - input.offsetPx - input.peekW).toFloat()
                rawInfoX = if (input.showPeek) {
                    rawPeekX + (input.peekW - input.infoW) / 2f
                } else {
                    (ringLeft - input.offsetPx - input.infoW).toFloat()
                }
                val totalH = (if (input.showInfo) input.infoH else 0) +
                    (if (input.showInfo && input.showPeek) input.offsetPx else 0) +
                    (if (input.showPeek) input.peekH else 0)
                val blockTop = input.ringCy - totalH / 2f
                rawInfoY = blockTop
                rawPeekY = blockTop + (if (input.showInfo) input.infoH + input.offsetPx else 0)
            }
            PeekPosition.RIGHT -> {
                rawPeekX = (ringRight + input.offsetPx).toFloat()
                rawInfoX = if (input.showPeek) {
                    rawPeekX + (input.peekW - input.infoW) / 2f
                } else {
                    (ringRight + input.offsetPx).toFloat()
                }
                val totalH = (if (input.showInfo) input.infoH else 0) +
                    (if (input.showInfo && input.showPeek) input.offsetPx else 0) +
                    (if (input.showPeek) input.peekH else 0)
                val blockTop = input.ringCy - totalH / 2f
                rawInfoY = blockTop
                rawPeekY = blockTop + (if (input.showInfo) input.infoH + input.offsetPx else 0)
            }
            else -> { // BELOW
                rawInfoX = input.ringCx - input.infoW / 2f
                rawInfoY = (ringBottom + input.offsetPx).toFloat()
                rawPeekX = input.ringCx - input.peekW / 2f
                rawPeekY = if (input.showInfo) {
                    rawInfoY + input.infoH + input.offsetPx
                } else {
                    (ringBottom + input.offsetPx).toFloat()
                }
            }
        }

        val maxAvailW = (input.screenW - 2 * marginX).coerceAtLeast(1)
        val maxAvailH = (input.screenH - topMargin - bottomMargin).coerceAtLeast(1)

        val clampedInfoW = input.infoW.coerceIn(0, maxAvailW)
        val clampedInfoH = input.infoH.coerceIn(0, maxAvailH)
        val finalRawInfoX = if (input.customInfoCx != null) input.customInfoCx - clampedInfoW / 2f else rawInfoX
        val finalRawInfoY = if (input.customInfoCy != null) input.customInfoCy - clampedInfoH / 2f else rawInfoY
        val ix = finalRawInfoX.roundToInt().coerceIn(marginX, (input.screenW - marginX - clampedInfoW).coerceAtLeast(marginX))
        val iy = finalRawInfoY.roundToInt().coerceIn(topMargin, (input.screenH - bottomMargin - clampedInfoH).coerceAtLeast(topMargin))
        val infoRect = RectI(ix, iy, ix + clampedInfoW, iy + clampedInfoH)

        val clampedPeekW = input.peekW.coerceIn(0, maxAvailW)
        val clampedPeekH = input.peekH.coerceIn(0, maxAvailH)
        val px = rawPeekX.roundToInt().coerceIn(marginX, (input.screenW - marginX - clampedPeekW).coerceAtLeast(marginX))
        val py = rawPeekY.roundToInt().coerceIn(topMargin, (input.screenH - bottomMargin - clampedPeekH).coerceAtLeast(topMargin))
        val peekRect = RectI(px, py, px + clampedPeekW, py + clampedPeekH)

        var wLeft = ringLeft
        var wTop = ringTop
        var wRight = ringRight
        var wBottom = ringBottom
        if (input.showInfo) {
            wLeft = minOf(wLeft, infoRect.left)
            wTop = minOf(wTop, infoRect.top)
            wRight = maxOf(wRight, infoRect.right)
            wBottom = maxOf(wBottom, infoRect.bottom)
        }
        if (input.showPeek) {
            wLeft = minOf(wLeft, peekRect.left)
            wTop = minOf(wTop, peekRect.top)
            wRight = maxOf(wRight, peekRect.right)
            wBottom = maxOf(wBottom, peekRect.bottom)
        }
        val windowBounds = RectI(
            left = wLeft.coerceIn(0, input.screenW),
            top = wTop.coerceIn(0, input.screenH),
            right = wRight.coerceIn(0, input.screenW),
            bottom = wBottom.coerceIn(0, input.screenH),
        )

        return Result(
            resolvedPosition = side,
            infoRect = infoRect,
            peekRect = peekRect,
            windowBounds = windowBounds,
        )
    }

    private fun resolveAutoSide(
        screenW: Int,
        screenH: Int,
        marginX: Int,
        topMargin: Int,
        bottomMargin: Int,
        ringLeft: Int,
        ringTop: Int,
        ringRight: Int,
        ringBottom: Int,
        infoGap: Int,
        peekW: Int,
        peekH: Int,
        offsetPx: Int,
    ): PeekPosition {
        val spaceBelow = (screenH - bottomMargin) - (ringBottom + offsetPx + infoGap)
        val spaceAbove = (ringTop - offsetPx - infoGap) - topMargin
        val spaceRight = (screenW - marginX) - (ringRight + offsetPx)
        val spaceLeft = (ringLeft - offsetPx) - marginX

        return when {
            spaceBelow >= peekH -> PeekPosition.BELOW
            spaceAbove >= peekH -> PeekPosition.ABOVE
            spaceRight >= peekW -> PeekPosition.RIGHT
            spaceLeft >= peekW -> PeekPosition.LEFT
            else -> {
                val best = listOf(
                    PeekPosition.BELOW to spaceBelow.toFloat() / max(1, peekH),
                    PeekPosition.ABOVE to spaceAbove.toFloat() / max(1, peekH),
                    PeekPosition.RIGHT to spaceRight.toFloat() / max(1, peekW),
                    PeekPosition.LEFT to spaceLeft.toFloat() / max(1, peekW),
                ).maxBy { it.second }
                best.first
            }
        }
    }
}
