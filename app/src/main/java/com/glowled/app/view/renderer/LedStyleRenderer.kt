package com.glowled.app.view.renderer

import android.graphics.Canvas
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

interface LedRenderContext {
    val cx: Float
    val cy: Float
    val isFilled: Boolean
    val colors: IntArray
    fun ring(c: Canvas, color: Int, a: Float, segmented: Boolean, rotation: Float)
    fun ripple(c: Canvas, color: Int, p: Float)
    fun spin(c: Canvas, p: Float, segmented: Boolean, color: Int, tail: Float, comets: Int, base: Float)
    fun rainbow(c: Canvas, p: Float)
    fun drawSegment(c: Canvas, from: Float, to: Float, color: Int, a: Float)
}

interface LedStyleRenderer {
    val basePeriodMs: Long
    fun render(canvas: Canvas, p: Float, color: Int, segmented: Boolean, rot: Float, ctx: LedRenderContext)
}

fun wave(p: Float) = (0.5f - 0.5f * cos(2f * PI.toFloat() * p))

fun heartbeat(p: Float): Float {
    fun bump(s: Float, e: Float) = if (p in s..e) sin(PI.toFloat() * (p - s) / (e - s)) else 0f
    return max(bump(0f, 0.14f), bump(0.22f, 0.38f) * 0.8f)
}

const val BLINK_ON = 0.5f

class StaticStyleRenderer : LedStyleRenderer {
    override val basePeriodMs: Long = 2500L
    override fun render(canvas: Canvas, p: Float, color: Int, segmented: Boolean, rot: Float, ctx: LedRenderContext) {
        ctx.ring(canvas, color, 1f, segmented, rot)
    }
}

class BreatheStyleRenderer : LedStyleRenderer {
    override val basePeriodMs: Long = 3200L
    override fun render(canvas: Canvas, p: Float, color: Int, segmented: Boolean, rot: Float, ctx: LedRenderContext) {
        ctx.ring(canvas, color, 0.12f + 0.88f * wave(p), segmented, rot)
    }
}

class EdgePulseStyleRenderer : LedStyleRenderer {
    override val basePeriodMs: Long = 3000L
    override fun render(canvas: Canvas, p: Float, color: Int, segmented: Boolean, rot: Float, ctx: LedRenderContext) {
        ctx.ring(canvas, color, 0.1f + 0.9f * wave(p), segmented, rot)
    }
}

class BlinkStyleRenderer : LedStyleRenderer {
    override val basePeriodMs: Long = 2400L
    override fun render(canvas: Canvas, p: Float, color: Int, segmented: Boolean, rot: Float, ctx: LedRenderContext) {
        if (p < BLINK_ON) ctx.ring(canvas, color, 1f, segmented, rot)
    }
}

class HeartbeatStyleRenderer : LedStyleRenderer {
    override val basePeriodMs: Long = 1700L
    override fun render(canvas: Canvas, p: Float, color: Int, segmented: Boolean, rot: Float, ctx: LedRenderContext) {
        ctx.ring(canvas, color, 0.08f + 0.92f * heartbeat(p), segmented, rot)
    }
}

class PulseStyleRenderer : LedStyleRenderer {
    override val basePeriodMs: Long = 2000L
    override fun render(canvas: Canvas, p: Float, color: Int, segmented: Boolean, rot: Float, ctx: LedRenderContext) {
        ctx.ring(canvas, color, 0.55f + 0.45f * (1f - wave(p)), segmented, rot)
        ctx.ripple(canvas, color, p)
    }
}

class SpinStyleRenderer : LedStyleRenderer {
    override val basePeriodMs: Long = 1500L
    override fun render(canvas: Canvas, p: Float, color: Int, segmented: Boolean, rot: Float, ctx: LedRenderContext) {
        ctx.spin(canvas, p, segmented, color, tail = 0.38f, comets = 1, base = 0.16f)
    }
}

class EdgeStyleRenderer : LedStyleRenderer {
    override val basePeriodMs: Long = 3200L
    override fun render(canvas: Canvas, p: Float, color: Int, segmented: Boolean, rot: Float, ctx: LedRenderContext) {
        ctx.spin(canvas, p, segmented, color, tail = 0.16f, comets = 2, base = 0.06f)
    }
}

class RainbowStyleRenderer : LedStyleRenderer {
    override val basePeriodMs: Long = 3000L
    override fun render(canvas: Canvas, p: Float, color: Int, segmented: Boolean, rot: Float, ctx: LedRenderContext) {
        ctx.rainbow(canvas, p)
    }
}

class OrbitStyleRenderer : LedStyleRenderer {
    override val basePeriodMs: Long = 2400L
    override fun render(canvas: Canvas, p: Float, color: Int, segmented: Boolean, rot: Float, ctx: LedRenderContext) {
        ctx.spin(canvas, p, segmented, color, tail = 0.22f, comets = 2, base = 0.08f)
    }
}

class SparkleStyleRenderer : LedStyleRenderer {
    override val basePeriodMs: Long = 3600L
    override fun render(canvas: Canvas, p: Float, color: Int, segmented: Boolean, rot: Float, ctx: LedRenderContext) {
        val n = ctx.colors.size
        for (i in 0 until 6) {
            val a = 0.12f + 0.88f * wave((p + i / 6f) % 1f).pow(4)
            val start = i / 6f + p * 0.1f
            ctx.drawSegment(canvas, start, start + 0.075f, ctx.colors[i % n], a)
        }
    }
}
