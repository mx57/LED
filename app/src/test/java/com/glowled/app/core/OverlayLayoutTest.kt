package com.glowled.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayLayoutTest {

    private fun baseInput(
        cx: Float = 540f,
        cy: Float = 100f,
        pos: PeekPosition = PeekPosition.BELOW,
        peekW: Int = 800,
        peekH: Int = 500,
    ) = OverlayLayout.Input(
        screenW = 1080,
        screenH = 2400,
        marginPx = 24,
        safeTopPx = 24,
        ringCx = cx,
        ringCy = cy,
        ringExtentPx = 60,
        indicatorBottom = cy + 60f,
        infoW = 240,
        infoH = 100,
        showInfo = true,
        peekW = peekW,
        peekH = peekH,
        showPeek = true,
        position = pos,
        offsetPx = 20,
    )

    private fun assertWithinScreen(res: OverlayLayout.Result, screenW: Int = 1080, screenH: Int = 2400, margin: Int = 24) {
        assertTrue("info left ${res.infoRect.left}", res.infoRect.left >= margin)
        assertTrue("info right ${res.infoRect.right}", res.infoRect.right <= screenW - margin)
        assertTrue("info top ${res.infoRect.top}", res.infoRect.top >= margin)
        assertTrue("info bottom ${res.infoRect.bottom}", res.infoRect.bottom <= screenH - margin)

        assertTrue("peek left ${res.peekRect.left}", res.peekRect.left >= margin)
        assertTrue("peek right ${res.peekRect.right}", res.peekRect.right <= screenW - margin)
        assertTrue("peek top ${res.peekRect.top}", res.peekRect.top >= margin)
        assertTrue("peek bottom ${res.peekRect.bottom}", res.peekRect.bottom <= screenH - margin)

        assertTrue(res.windowBounds.left >= 0)
        assertTrue(res.windowBounds.top >= 0)
        assertTrue(res.windowBounds.right <= screenW)
        assertTrue(res.windowBounds.bottom <= screenH)
    }

    @Test
    fun placesBelowRingAndClampsToHorizontalEdges() {
        // Ring near right edge -> peek plate must clamp inside screen
        val res = OverlayLayout.compute(baseInput(cx = 1020f, cy = 200f, pos = PeekPosition.BELOW))
        assertEquals(PeekPosition.BELOW, res.resolvedPosition)
        assertTrue(res.peekRect.top > 260)
        assertWithinScreen(res)
    }

    @Test
    fun placesAboveRingWhenConfigured() {
        val res = OverlayLayout.compute(baseInput(cx = 540f, cy = 1800f, pos = PeekPosition.ABOVE))
        assertEquals(PeekPosition.ABOVE, res.resolvedPosition)
        assertTrue(res.peekRect.bottom < res.infoRect.top)
        assertTrue(res.infoRect.bottom < 1800 - 60)
        assertWithinScreen(res)
    }

    @Test
    fun placesLeftAndRightOfRingWithoutClipping() {
        val resLeft = OverlayLayout.compute(baseInput(cx = 980f, cy = 1200f, pos = PeekPosition.LEFT))
        assertEquals(PeekPosition.LEFT, resLeft.resolvedPosition)
        assertTrue(resLeft.peekRect.right <= 980 - 60)
        assertWithinScreen(resLeft)

        val resRight = OverlayLayout.compute(baseInput(cx = 100f, cy = 1200f, pos = PeekPosition.RIGHT))
        assertEquals(PeekPosition.RIGHT, resRight.resolvedPosition)
        assertTrue(resRight.peekRect.left >= 100 + 60)
        assertWithinScreen(resRight)
    }

    @Test
    fun autoSelectsAboveWhenRingIsNearBottom() {
        val res = OverlayLayout.compute(baseInput(cx = 540f, cy = 2250f, pos = PeekPosition.AUTO))
        assertEquals(PeekPosition.ABOVE, res.resolvedPosition)
        assertWithinScreen(res)
    }

    @Test
    fun clampsExtremeCornerRingAndOversizedPeek() {
        val res = OverlayLayout.compute(
            baseInput(cx = 20f, cy = 2380f, pos = PeekPosition.BELOW, peekW = 1500, peekH = 3000)
        )
        assertWithinScreen(res)
    }

    @Test
    fun customInfoPositionPlacesInfoAndClampsToScreen() {
        val input = baseInput(cx = 540f, cy = 100f, pos = PeekPosition.BELOW).copy(
            customInfoCx = 1060f,
            customInfoCy = 2380f,
        )
        val res = OverlayLayout.compute(input)
        assertWithinScreen(res)
        assertEquals(1080 - 24 - 240, res.infoRect.left)
        assertEquals(2400 - 24 - 100, res.infoRect.top)
    }
}
