package com.glowled.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CutoutGeometryTest {

    @Test
    fun classifiesPunchHoleCircle() {
        val screenW = 1080f
        // Standard punch hole camera (35x35)
        val shape = CutoutGeometry.classifyShape(35f, 35f, screenW)
        assertEquals(CutoutShape.CIRCLE, shape)
    }

    @Test
    fun classifiesPillCutout() {
        val screenW = 1080f
        // Dual camera or dynamic pill (80x35) -> aspect ratio 2.28 > 1.5
        val shape = CutoutGeometry.classifyShape(80f, 35f, screenW)
        assertEquals(CutoutShape.PILL, shape)
    }

    @Test
    fun classifiesNotchCutout() {
        val screenW = 1080f
        // Wide waterdrop or bar notch (> 20% of screen width = 216px)
        val shape = CutoutGeometry.classifyShape(250f, 60f, screenW)
        assertEquals(CutoutShape.NOTCH, shape)
    }

    @Test
    fun classifiesBorderlineAspectRatio() {
        val screenW = 1080f
        // Exactly 1.5 ratio: 60 / 40 = 1.5 -> CIRCLE (not strictly > 1.5)
        val shape1 = CutoutGeometry.classifyShape(60f, 40f, screenW)
        assertEquals(CutoutShape.CIRCLE, shape1)

        // 1.51 ratio: 61 / 40 > 1.5 -> PILL
        val shape2 = CutoutGeometry.classifyShape(61f, 40f, screenW)
        assertEquals(CutoutShape.PILL, shape2)
    }

    @Test
    fun zeroOrNegativeDimensionsFallbackSafely() {
        val screenW = 1080f
        val shape = CutoutGeometry.classifyShape(0f, 0f, screenW)
        assertEquals(CutoutShape.CIRCLE, shape)
    }
}
