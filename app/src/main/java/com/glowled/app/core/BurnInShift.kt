package com.glowled.app.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Calculates a small deterministic pixel shift (<= maxShiftPx) that updates every
 * [intervalMs] (e.g. 60 seconds) to protect AMOLED screens from pixel burn-in during static AOD.
 */
object BurnInShiftCalculator {
    const val DEFAULT_MAX_SHIFT_PX = 2f
    const val DEFAULT_INTERVAL_MS = 60_000L

    /**
     * Returns (dx, dy) pixel offset for the given [uptimeMs].
     * Traverses a smooth 8-point cycle around the original position.
     */
    fun calculateShift(
        uptimeMs: Long,
        maxShiftPx: Float = DEFAULT_MAX_SHIFT_PX,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
    ): Pair<Float, Float> {
        if (maxShiftPx <= 0f || intervalMs <= 0L) return 0f to 0f
        val step = (uptimeMs / intervalMs) % 8
        val angleRad = step * (Math.PI / 4.0)
        val dx = (maxShiftPx * cos(angleRad)).toFloat()
        val dy = (maxShiftPx * sin(angleRad)).toFloat()
        return dx to dy
    }

    fun isWithinBounds(dx: Float, dy: Float, maxShiftPx: Float): Boolean {
        val dist = sqrt(dx * dx + dy * dy)
        return dist <= maxShiftPx + 0.001f
    }
}
