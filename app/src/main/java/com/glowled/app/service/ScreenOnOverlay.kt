package com.glowled.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import com.glowled.app.core.CutoutGeometry
import com.glowled.app.core.DebugLog
import com.glowled.app.core.Geometry
import com.glowled.app.core.LedController
import com.glowled.app.core.MeterIcon
import com.glowled.app.core.NotificationStore
import com.glowled.app.core.OverlayLayout
import com.glowled.app.ui.ReplyActivity
import com.glowled.app.view.AppInfoView
import com.glowled.app.view.LedView
import com.glowled.app.view.NotificationPeekView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Interactive notification overlay for screen-on (unlocked) use.
 *
 *  - collapsed: ring around the camera only; tap the ring to expand;
 *  - icons: one circle/icon per source app below the camera; tap an icon to read;
 *  - peek: message card for the selected app, with reply / mark-as-read / open.
 *
 * Actions mirror the screen-off message view. The window is sized to the visible UI so touches
 * outside it reach the apps below.
 */
@SuppressLint("ClickableViewAccessibility")
object ScreenOnOverlay {

    private enum class State { COLLAPSED, ICONS, PEEK }
    private enum class DragTarget { NONE, RING, INFO }

    private var state = State.COLLAPSED
    private var root: RootLayout? = null
    private var led: LedView? = null
    private var info: AppInfoView? = null
    private var peek: NotificationPeekView? = null
    private var wm: WindowManager? = null
    private var windowCtx: Context? = null
    private var usingA11y = false

    private val main = Handler(Looper.getMainLooper())
    private val hidePeekRunnable = Runnable { hidePeek(showIconsAfter = true) }
    private val autoCollapseIconsRunnable = Runnable { if (state == State.ICONS) collapseToRing() }

    private var peekPkg: String? = null
    private var newestTime = 0L

    private var dragTarget = DragTarget.NONE
    private var pendingDragTarget = DragTarget.NONE
    private val isDragging get() = dragTarget != DragTarget.NONE
    private var dragCx = 0f
    private var dragCy = 0f
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOrigCx = 0f
    private var dragOrigCy = 0f
    private val startDragRunnable = Runnable { beginDrag(pendingDragTarget) }

    private val listener: () -> Unit = { main.post { onStoreChanged() } }

    private open class RootLayout(context: Context) : FrameLayout(context) {
        var onLaidOut: (() -> Unit)? = null

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            onLaidOut?.invoke()
        }
    }

    fun isShowing(): Boolean = root != null

    /** True when the screen-on overlay should be the interactive (tappable / draggable) one. */
    fun shouldBeInteractive(): Boolean {
        val p = LedController.prefs
        val canTap = p.onTapMessages && !NotificationStore.isEmpty()
        return p.overlayEnabled && (canTap || p.dragRingEnabled) &&
            !LedController.isLocked() && !p.overlayLockOnly && !p.style.isEdge
    }

    fun show(context: Context) {
        val prefs = LedController.prefs
        if (!prefs.enabled) return hide()
        // Prefer accessibility overlay for interactive/touch input (better cutout touch behavior on some devices).
        val a11y = LedAccessibilityService.instance
        val wantA11y = a11y != null
        if (root != null && usingA11y == wantA11y) {
            refresh()
            return
        }
        hide()
        val ctx: Context
        val type: Int
        if (a11y != null) {
            ctx = a11y; type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Settings.canDrawOverlays(context)) {
            ctx = context.applicationContext; type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            DebugLog.add("Screen-on overlay: no permission/service")
            return
        }

        val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        val r = object : RootLayout(ctx) {
            private var downX = 0f; private var downY = 0f; private var downT = 0L
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX; downY = ev.rawY; downT = ev.eventTime
                        val loc = IntArray(2); getLocationOnScreen(loc)
                        DebugLog.add("Screen-on touch down ($state) at ${ev.rawX.toInt()},${ev.rawY.toInt()} win=${loc[0]},${loc[1]} ${width}x${height}")
                        val p = LedController.prefs
                        val infoV = info
                        pendingDragTarget = when {
                            state != State.COLLAPSED && infoV != null && infoV.visibility == View.VISIBLE &&
                                pointInView(infoV, ev.rawX, ev.rawY) && p.dragInfoEnabled -> DragTarget.INFO
                            state == State.COLLAPSED && p.dragRingEnabled && !p.style.isEdge -> DragTarget.RING
                            else -> DragTarget.NONE
                        }
                        if (pendingDragTarget != DragTarget.NONE) {
                            dragStartX = ev.rawX; dragStartY = ev.rawY
                            main.removeCallbacks(startDragRunnable)
                            main.postDelayed(startDragRunnable, longPressTimeout)
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isDragging) {
                            updateDrag(ev.rawX, ev.rawY)
                            return true
                        }
                        if (abs(ev.rawX - downX) > touchSlop || abs(ev.rawY - downY) > touchSlop) {
                            main.removeCallbacks(startDragRunnable)
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        main.removeCallbacks(startDragRunnable)
                        if (isDragging) {
                            finishDrag(save = ev.actionMasked == MotionEvent.ACTION_UP)
                            return true
                        }
                    }
                }
                val handled = super.dispatchTouchEvent(ev)
                if (ev.actionMasked == MotionEvent.ACTION_UP) {
                    val slop = 48 * resources.displayMetrics.density
                    val isTap = ev.eventTime - downT < 400 && abs(ev.rawX - downX) < slop && abs(ev.rawY - downY) < slop
                    if (isTap && !hitInteractive(ev)) {
                        DebugLog.add("Screen-on ring tapped ($state)")
                        onBackgroundTap()
                    }
                }
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) return true
                return handled || ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL
            }
            private fun hitInteractive(ev: MotionEvent): Boolean {
                val i = info
                if (i != null && i.visibility == View.VISIBLE && pointInView(i, ev.rawX, ev.rawY)) return true
                val p = peek
                if (p != null && p.isShowing() && pointInView(p, ev.rawX, ev.rawY)) return true
                return false
            }
            private fun pointInView(v: View, rawX: Float, rawY: Float): Boolean {
                val loc = IntArray(2); v.getLocationOnScreen(loc)
                return rawX >= loc[0] && rawX <= loc[0] + v.width && rawY >= loc[1] && rawY <= loc[1] + v.height
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            isForceDarkAllowed = false
            clipChildren = false
            clipToPadding = false
            isClickable = true
            isFocusable = false
        }

        val ledView = LedView(ctx).apply { useScreenCoords = true; intro = 1f; dropletRadiusDp = prefs.dropletSizeDp.toFloat() }
        val infoView = AppInfoView(ctx).apply {
            monochrome = prefs.aodIcons
            iconDp = prefs.appIconDp
            onAppTap = { pkg -> onAppTapped(pkg) }
        }
        val d = ctx.resources.displayMetrics.density
        val peekWidth = (prefs.peekWidthDp * d).toInt().coerceIn((180 * d).toInt(), (ctx.resources.displayMetrics.widthPixels - (24 * d).toInt()))
        val peekView = NotificationPeekView(ctx).apply {
            actionsEnabled = prefs.peekActions
            expandableEnabled = prefs.peekExpandable
            applyCustomization(prefs.peekFontSizeSp.toFloat(), prefs.peekLines, prefs.peekHeightDp)
            onOpen = { e -> openNotification(e) }
            onReply = { e -> replyTo(e) }
            onMarkRead = { e -> markRead(e) }
            onMarkAllRead = { list -> markAllRead(list) }
            onInteraction = { resetPeekTimeout() }
            onClose = { hidePeek(showIconsAfter = true) }
            onExpandedChanged = {
                resetPeekTimeout()
                val type = windowType()
                if (type != 0) applyWindow(type)
                root?.requestLayout()
                root?.post { positionChildren() }
            }
        }

        r.addView(ledView, FrameLayout.LayoutParams(-1, -1))
        r.addView(infoView, FrameLayout.LayoutParams(-2, -2))
        r.addView(peekView, FrameLayout.LayoutParams(peekWidth, -2))
        r.onLaidOut = { positionChildren() }

        root = r; led = ledView; info = infoView; peek = peekView
        windowCtx = ctx; usingA11y = wantA11y
        wm = ctx.getSystemService(WindowManager::class.java)
        state = State.COLLAPSED; peekPkg = null
        newestTime = NotificationStore.newestTime()

        applyWindow(type)
        if (root == null) return
        LedController.addListener(listener)
        DebugLog.add("Screen-on interactive overlay shown (${if (wantA11y) "a11y" else "app"})")
        refresh()
    }

    private fun geometry(ctx: Context): Geometry = CutoutGeometry.resolve(ctx, LedController.prefs)

    private fun extentPx(g: Geometry, prefs: com.glowled.app.core.Prefs, d: Float): Int {
        val thickness = prefs.thicknessDp * d
        val gap = prefs.gapDp * d
        val glowRadius = if (prefs.glow) thickness * 1.6f + 3 * d else 0f
        val half = max(g.w, g.h) / 2f + gap + thickness
        return (half + thickness * 7 + glowRadius * 2 + 4 * d).toInt()
    }

    /** Screen-space tap/response zone for the collapsed screen-on ring. */
    fun tapZoneRect(ctx: Context): android.graphics.Rect {
        val prefs = LedController.prefs
        val g = geometry(ctx)
        val d = ctx.resources.displayMetrics.density
        val e = extentPx(g, prefs, d)
        val ringW = maxOf(e * 2, (120 * d).toInt())
        val autoW = ringW
        val autoH = maxOf(e * 2 + (DROP_DP * d).toInt() * 2, (120 * d).toInt())
        val w = if (prefs.tapZoneWidthDp > 0) (prefs.tapZoneWidthDp * d).toInt().coerceAtLeast((40 * d).toInt()) else autoW
        val h = if (prefs.tapZoneHeightDp > 0) (prefs.tapZoneHeightDp * d).toInt().coerceAtLeast((40 * d).toInt()) else autoH
        val dm = ctx.resources.displayMetrics
        var x = (g.cx - w / 2f).toInt() + (prefs.tapZoneOffsetXDp * d).toInt()
        var y = (g.cy - h / 2f).toInt() + (prefs.tapZoneOffsetYDp * d).toInt()
        // Keep on screen.
        x = x.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
        y = y.coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
        return android.graphics.Rect(x, y, x + w, y + h)
    }

    private fun beginDrag(target: DragTarget) {
        if (target == DragTarget.NONE || root == null) return
        val ctx = windowCtx ?: return
        if (target == DragTarget.RING && state != State.COLLAPSED) return
        if (target == DragTarget.INFO && state == State.COLLAPSED) return
        dragTarget = target
        main.removeCallbacks(autoCollapseIconsRunnable)
        if (target == DragTarget.RING) {
            val g = geometry(ctx)
            dragOrigCx = g.cx
            dragOrigCy = g.cy
        } else {
            val infoView = info ?: run { dragTarget = DragTarget.NONE; return }
            val loc = IntArray(2)
            infoView.getLocationOnScreen(loc)
            dragOrigCx = loc[0] + infoView.width / 2f
            dragOrigCy = loc[1] + infoView.height / 2f
        }
        dragCx = dragOrigCx
        dragCy = dragOrigCy
        root?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val type = windowType()
        if (type != 0) applyWindow(type)
        DebugLog.add("Screen-on $dragTarget drag started at ${dragCx.toInt()},${dragCy.toInt()}")
    }

    private fun updateDrag(rawX: Float, rawY: Float) {
        val ctx = windowCtx ?: return
        val prefs = LedController.prefs
        val dm = ctx.resources.displayMetrics
        if (dragTarget == DragTarget.RING) {
            val ledView = led ?: return
            val g = geometry(ctx)
            val margin = (g.w / 2f + prefs.gapDp * dm.density + prefs.thicknessDp * dm.density).coerceAtLeast(16 * dm.density)
            dragCx = (dragOrigCx + (rawX - dragStartX)).coerceIn(margin, (dm.widthPixels - margin).coerceAtLeast(margin))
            dragCy = (dragOrigCy + (rawY - dragStartY)).coerceIn(margin, (dm.heightPixels - margin).coerceAtLeast(margin))
            ledView.configure(prefs, g.copy(cx = dragCx, cy = dragCy), NotificationStore.colors())
        } else if (dragTarget == DragTarget.INFO) {
            val infoView = info ?: return
            val halfW = (infoView.width / 2f).coerceAtLeast(16 * dm.density)
            val halfH = (infoView.height / 2f).coerceAtLeast(16 * dm.density)
            dragCx = (dragOrigCx + (rawX - dragStartX)).coerceIn(halfW, (dm.widthPixels - halfW).coerceAtLeast(halfW))
            dragCy = (dragOrigCy + (rawY - dragStartY)).coerceIn(halfH, (dm.heightPixels - halfH).coerceAtLeast(halfH))
            val rLoc = IntArray(2)
            root?.getLocationOnScreen(rLoc)
            place(infoView, dragCx - rLoc[0] - infoView.width / 2f, dragCy - rLoc[1] - infoView.height / 2f)
        }
    }

    private fun finishDrag(save: Boolean) {
        val finishedTarget = dragTarget
        dragTarget = DragTarget.NONE
        pendingDragTarget = DragTarget.NONE
        if (save) {
            val ctx = windowCtx ?: return
            val screenW = CutoutGeometry.naturalSize(ctx).x
            if (finishedTarget == DragTarget.RING) {
                val g = geometry(ctx)
                LedController.prefs.saveCutout(g.shape, dragCx, dragCy, g.w, g.h, screenW)
                DebugLog.add("Screen-on ring moved to ${dragCx.toInt()},${dragCy.toInt()}")
            } else if (finishedTarget == DragTarget.INFO) {
                LedController.prefs.saveInfoPos(dragCx, dragCy, screenW)
                DebugLog.add("Screen-on info strip moved to ${dragCx.toInt()},${dragCy.toInt()}")
            }
            root?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            LedController.refreshAll()
        } else {
            refresh()
        }
    }

    private fun currentParams(): WindowManager.LayoutParams? = root?.layoutParams as? WindowManager.LayoutParams
    private fun windowType(): Int = currentParams()?.type ?: 0

    private fun applyWindow(type: Int) {
        val r = root ?: return
        val ctx = windowCtx ?: return
        val manager = wm ?: return
        val p = buildParams(ctx, type)
        if (r.parent == null) {
            try { manager.addView(r, p) } catch (e: Exception) {
                Log.w("GlowLED", "interactive overlay add failed", e); cleanupRefs(); return
            }
        } else {
            try { manager.updateViewLayout(r, p) } catch (e: Exception) {
                Log.w("GlowLED", "interactive overlay update failed", e)
            }
        }
    }

    private fun buildParams(ctx: Context, type: Int): WindowManager.LayoutParams {
        val prefs = LedController.prefs
        val g = geometry(ctx)
        val ledView = led!!
        val d = ctx.resources.displayMetrics.density
        ledView.configure(prefs, g, NotificationStore.colors())
        ledView.notificationRingVisible = prefs.overlayEnabled && !NotificationStore.isEmpty() &&
            (!prefs.overlayLockOnly || LedController.isLocked())
        val m = LedController.overlayMeter
        ledView.setMeter(m?.fraction, m?.color ?: 0, m?.icon ?: MeterIcon.NONE, m?.indeterminate ?: false)
        ledView.setBatteryText(LedController.batteryStatusText())
        ledView.setCpuLoad(
            LedController.cpuStatusText(),
            prefs.cpuPos,
            prefs.cpuOffsetXDp,
            prefs.cpuOffsetYDp,
            prefs.cpuFontSizeSp,
            LedController.cpuUsage,
            prefs.cpuColorByLoad,
        )

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        val p: WindowManager.LayoutParams
        if (prefs.style.isEdge || isDragging) {
            p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                type, flags, PixelFormat.TRANSLUCENT,
            )
            p.gravity = Gravity.TOP or Gravity.START
        } else {
            val e = ledView.extentPx(g)
            val dm = ctx.resources.displayMetrics
            if (state == State.COLLAPSED) {
                val tz = tapZoneRect(ctx)
                val extraLeft = if (prefs.cpuIndicator) {
                    var ex = if (prefs.cpuPos == "left") (90 * d).toInt() else (40 * d).toInt()
                    if (prefs.cpuOffsetXDp < 0) ex += (-prefs.cpuOffsetXDp * d).toInt()
                    ex
                } else 0
                val extraRight = if (prefs.cpuIndicator) {
                    var ex = if (prefs.cpuPos == "right") (90 * d).toInt() else (40 * d).toInt()
                    if (prefs.cpuOffsetXDp > 0) ex += (prefs.cpuOffsetXDp * d).toInt()
                    ex
                } else 0
                val extraTop = if (prefs.cpuIndicator) {
                    var ey = if (prefs.cpuPos == "above") (45 * d).toInt() else (10 * d).toInt()
                    if (prefs.cpuOffsetYDp < 0) ey += (-prefs.cpuOffsetYDp * d).toInt()
                    ey
                } else 0
                val extraBottom = if (prefs.cpuIndicator) {
                    var ey = if (prefs.cpuPos == "below") (45 * d).toInt() else (10 * d).toInt()
                    if (prefs.cpuOffsetYDp > 0) ey += (prefs.cpuOffsetYDp * d).toInt()
                    ey
                } else 0

                val w = (tz.width() + extraLeft + extraRight).coerceAtMost(dm.widthPixels)
                val h = (tz.height() + extraTop + extraBottom).coerceAtMost(dm.heightPixels)
                p = WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT)
                p.gravity = Gravity.TOP or Gravity.START
                p.x = (tz.left - extraLeft).coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
                p.y = (tz.top - extraTop).coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
            } else {
                val layout = computeLayout(ctx, g, e)
                val wb = layout.windowBounds
                val pad = (16 * d).toInt()
                val wx = (wb.left - pad).coerceAtLeast(0)
                val wy = (wb.top - pad).coerceAtLeast(0)
                val wr = (wb.right + pad).coerceAtMost(dm.widthPixels)
                val wBottom = (wb.bottom + pad).coerceAtMost(dm.heightPixels)
                val w = maxOf(wr - wx, e * 2).coerceAtMost(dm.widthPixels)
                val h = maxOf(wBottom - wy, e * 2).coerceAtMost(dm.heightPixels)
                p = WindowManager.LayoutParams(w, h, type, flags, PixelFormat.TRANSLUCENT)
                p.gravity = Gravity.TOP or Gravity.START
                p.x = wx.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
                p.y = wy.coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
            }
        }
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) p.fitInsetsTypes = 0
        if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.alpha = 0.8f
        }
        p.title = "GlowLED"
        return p
    }

    fun refresh() {
        val r = root ?: return
        val ctx = windowCtx ?: return
        val prefs = LedController.prefs
        val ledView = led ?: return
        val g = geometry(ctx)
        ledView.configure(prefs, g, NotificationStore.colors())
        ledView.notificationRingVisible = prefs.overlayEnabled && !NotificationStore.isEmpty() &&
            (!prefs.overlayLockOnly || LedController.isLocked())
        val m = LedController.overlayMeter
        ledView.setMeter(m?.fraction, m?.color ?: 0, m?.icon ?: MeterIcon.NONE, m?.indeterminate ?: false)
        ledView.setBatteryText(LedController.batteryStatusText())
        ledView.setCpuLoad(
            LedController.cpuStatusText(),
            prefs.cpuPos,
            prefs.cpuOffsetXDp,
            prefs.cpuOffsetYDp,
            prefs.cpuFontSizeSp,
            LedController.cpuUsage,
            prefs.cpuColorByLoad,
        )
        ledView.dropletRadiusDp = prefs.dropletSizeDp.toFloat()
        info?.monochrome = prefs.aodIcons
        info?.iconDp = prefs.appIconDp

        val d = ctx.resources.displayMetrics.density
        val peekW = (prefs.peekWidthDp * d).toInt().coerceIn((180 * d).toInt(), (ctx.resources.displayMetrics.widthPixels - (24 * d).toInt()))
        peek?.layoutParams?.width = peekW
        peek?.actionsEnabled = prefs.peekActions
        peek?.expandableEnabled = prefs.peekExpandable
        peek?.applyCustomization(prefs.peekFontSizeSp.toFloat(), prefs.peekLines, prefs.peekHeightDp)

        val type = windowType()
        if (type != 0) applyWindow(type)
        r.requestLayout()
        r.post { positionChildren() }
    }

    private fun computeLayout(ctx: Context, g: Geometry, ringExtent: Int): OverlayLayout.Result {
        val prefs = LedController.prefs
        val dm = ctx.resources.displayMetrics
        val d = dm.density
        val marginPx = (12 * d).toInt()
        val infoView = info
        val peekView = peek
        val infoW = infoView?.measuredWidth?.takeIf { it > 0 } ?: (220 * d).toInt()
        val infoH = infoView?.measuredHeight?.takeIf { it > 0 } ?: (56 * d).toInt()
        val estPeekW = (prefs.peekWidthDp * d).toInt().coerceIn((180 * d).toInt(), (dm.widthPixels - 2 * marginPx).coerceAtLeast(180))
        val peekW = peekView?.measuredWidth?.takeIf { it > 0 } ?: estPeekW
        val peekH = peekView?.measuredHeight?.takeIf { it > 0 } ?: (prefs.peekHeightDp * d).toInt()
        val ledLoc = IntArray(2)
        val ledView = led
        val indBottom = if (ledView != null && ledView.isAttachedToWindow) {
            ledView.getLocationOnScreen(ledLoc)
            ledLoc[1] + ledView.indicatorBottom()
        } else {
            g.cy + ringExtent
        }
        val customInfo = CutoutGeometry.resolveInfoPos(ctx, prefs)
        return OverlayLayout.compute(
            OverlayLayout.Input(
                screenW = dm.widthPixels,
                screenH = dm.heightPixels,
                marginPx = marginPx,
                safeTopPx = marginPx,
                ringCx = g.cx,
                ringCy = g.cy,
                ringExtentPx = ringExtent,
                indicatorBottom = indBottom,
                infoW = infoW,
                infoH = infoH,
                showInfo = state != State.COLLAPSED && (infoView?.visibility == View.VISIBLE),
                peekW = peekW,
                peekH = peekH,
                showPeek = state == State.PEEK && (peekView?.isShowing() == true),
                position = prefs.peekPosition,
                offsetPx = (prefs.peekOffsetDp * d).toInt(),
                customInfoCx = customInfo?.first,
                customInfoCy = customInfo?.second,
            )
        )
    }

    private fun positionChildren() {
        if (isDragging) return
        val r = root ?: return
        val ctx = windowCtx ?: return
        val ledView = led ?: return
        val infoView = info ?: return
        val peekView = peek ?: return
        if (!r.isAttachedToWindow) return
        val loc = IntArray(2)
        r.getLocationOnScreen(loc)
        val g = geometry(ctx)
        val layout = computeLayout(ctx, g, ledView.extentPx(g))
        place(infoView, layout.infoRect.left.toFloat() - loc[0], layout.infoRect.top.toFloat() - loc[1])
        place(peekView, layout.peekRect.left.toFloat() - loc[0], layout.peekRect.top.toFloat() - loc[1])
    }

    private fun place(v: View, x: Float, y: Float) {
        val l = x.roundToInt(); val t = y.roundToInt()
        v.layout(l, t, l + v.measuredWidth, t + v.measuredHeight)
    }

    private fun setState(newState: State) {
        if (state == newState && root != null) return
        state = newState
        val type = windowType()
        if (type != 0) applyWindow(type)
        root?.requestLayout()
        root?.post { positionChildren() }
    }

    private fun bindApps() { info?.bind(NotificationStore.apps()) }

    private fun expandToIcons(withDroplet: Boolean = false) {
        val prefs = LedController.prefs
        bindApps()
        setState(State.ICONS)
        main.removeCallbacks(hidePeekRunnable)
        val newest = NotificationStore.apps().firstOrNull()
        if (withDroplet && prefs.screenOnDroplet && newest != null) {
            info?.visibility = View.VISIBLE
            info?.alpha = 1f
            info?.doOnLayout {
                if (root == null) return@doOnLayout
                info?.prepareDrop()
                val ctx = windowCtx ?: return@doOnLayout
                val infoView = info ?: return@doOnLayout
                val tx = infoView.left.toFloat() + infoView.newestCenterX()
                val ty = infoView.top.toFloat() + infoView.iconCenterY()
                led?.playDroplet(newest.color, ty, targetX = tx) { info?.popNewest() }
            }
        } else {
            info?.show()
        }
    }

    private fun collapseToRing() {
        main.removeCallbacks(autoCollapseIconsRunnable)
        main.removeCallbacks(hidePeekRunnable)
        hidePeek(showIconsAfter = false)
        info?.clearSelection()
        info?.hide()
        setState(State.COLLAPSED)
    }

    private fun onAppTapped(pkg: String) {
        val prefs = LedController.prefs
        main.removeCallbacks(autoCollapseIconsRunnable)
        main.removeCallbacks(hidePeekRunnable)
        if (!prefs.showContent) {
            info?.clearSelection()
            return
        }
        val entries = NotificationStore.entriesFor(pkg)
        if (entries.isEmpty() || (peek?.isShowing() == true && peekPkg == pkg)) {
            hidePeek(showIconsAfter = true)
            return
        }
        peekPkg = pkg
        peek?.actionsEnabled = prefs.peekActions
        peek?.expandableEnabled = prefs.peekExpandable
        peek?.applyCustomization(prefs.peekFontSizeSp.toFloat(), prefs.peekLines, prefs.peekHeightDp)
        peek?.bind(entries)
        peek?.show()
        setState(State.PEEK)
        root?.post { positionChildren() }
        resetPeekTimeout()
    }

    private fun resetPeekTimeout() {
        main.removeCallbacks(hidePeekRunnable)
        main.postDelayed(hidePeekRunnable, PEEK_MS)
    }

    private fun hidePeek(showIconsAfter: Boolean) {
        main.removeCallbacks(hidePeekRunnable)
        peekPkg = null
        peek?.hide()
        info?.clearSelection()
        if (state == State.PEEK) setState(State.ICONS)
    }

    private fun onBackgroundTap() {
        when (state) {
            State.PEEK -> hidePeek(showIconsAfter = true)
            State.ICONS -> collapseToRing()
            State.COLLAPSED -> {
                if (LedController.prefs.onTapMessages && !NotificationStore.isEmpty()) expandToIcons(withDroplet = LedController.prefs.screenOnDroplet)
                else DebugLog.add("Screen-on tap: ignored")
            }
        }
    }

    // ---- actions (same as AOD, but on unlocked screen) ----

    private fun openNotification(e: NotificationStore.Entry) {
        val pi = e.intent ?: return
        root?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        try {
            val opts = android.app.ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= 34) {
                opts.setPendingIntentBackgroundActivityStartMode(
                    android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                )
            }
            pi.send(windowCtx ?: LedController.app, 0, null, null, null, null, opts.toBundle())
        } catch (ex: Exception) {
            DebugLog.add("Could not open notification: ${ex.javaClass.simpleName}")
        }
        collapseToRing()
    }

    private fun replyTo(e: NotificationStore.Entry) {
        root?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        if (ReplyActivity.start(LedController.app, e.key)) collapseToRing()
    }

    private fun markRead(e: NotificationStore.Entry) {
        root?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        val action = e.readAction
        if (action != null) {
            try { action.actionIntent.send() } catch (ex: Exception) {
                DebugLog.add("Mark as read failed: ${ex.javaClass.simpleName}")
            }
            NotificationStore.markSeen(e.key)
            LedController.refreshAll()
        } else {
            NotifyListenerService.instance?.cancelNotification(e.key)
        }
        DebugLog.add("Marked as read from screen-on overlay")
        // store change will update; keep UX responsive
        if (NotificationStore.isEmpty()) collapseToRing() else {
            if (NotificationStore.entriesFor(peekPkg ?: "").isEmpty()) hidePeek(true)
        }
    }

    private fun markAllRead(list: List<NotificationStore.Entry>) {
        root?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        for (e in list) {
            val action = e.readAction
            if (action != null) {
                try { action.actionIntent.send() } catch (ex: Exception) {
                    DebugLog.add("Mark as read failed: ${ex.javaClass.simpleName}")
                }
                NotificationStore.markSeen(e.key)
            } else {
                NotifyListenerService.instance?.cancelNotification(e.key)
            }
        }
        LedController.refreshAll()
        collapseToRing()
    }

    private fun onStoreChanged() {
        if (root == null) return
        if (NotificationStore.isEmpty()) {
            // let controller decide (may keep overlay for meter/battery) -> will hide interactive
            LedController.updateOverlay()
            return
        }
        val apps = NotificationStore.apps()
        if (apps.isEmpty()) { LedController.updateOverlay(); return }
        bindApps()
        val newest = NotificationStore.newestTime()
        if (newest > newestTime) {
            val newestApp = apps.firstOrNull()
            newestTime = newest
            if (state != State.COLLAPSED && newestApp != null && LedController.prefs.screenOnDroplet) {
                info?.doOnLayout {
                    if (root == null) return@doOnLayout
                    info?.prepareDrop()
                    val infoView = info ?: return@doOnLayout
                    val tx = infoView.left.toFloat() + infoView.newestCenterX()
                    val ty = infoView.top.toFloat() + infoView.iconCenterY()
                    led?.playDroplet(newestApp.color, ty, targetX = tx) { info?.popNewest() }
                }
            } else if (state != State.COLLAPSED) {
                info?.show()
            }
            // collapsed: ring droplet handled by LedController/OverlayHost path
        }
        peekPkg?.let { pkg ->
            val entries = NotificationStore.entriesFor(pkg)
            if (entries.isEmpty()) hidePeek(showIconsAfter = true) else peek?.bind(entries)
        }
        refresh()
        root?.post { positionChildren() }
    }

    /** Auto-shows icon strip or plays retracting droplet on a new screen-on notification. */
    fun onNewNotification(color: Int) {
        val prefs = LedController.prefs
        if (prefs.screenOnAutoIcons && prefs.onTapMessages && !NotificationStore.isEmpty()) {
            if (state == State.COLLAPSED) {
                expandToIcons(withDroplet = prefs.screenOnDroplet)
                main.removeCallbacks(autoCollapseIconsRunnable)
                main.postDelayed(autoCollapseIconsRunnable, AUTO_ICONS_MS)
            } else if (state == State.ICONS) {
                main.removeCallbacks(autoCollapseIconsRunnable)
                main.postDelayed(autoCollapseIconsRunnable, AUTO_ICONS_MS)
            }
        } else if (prefs.screenOnDroplet) {
            droplet(color)
        }
    }

    /** Droplet on the ring while collapsed. */
    fun droplet(color: Int) {
        if (!LedController.prefs.screenOnDroplet) return
        val ledView = led ?: run {
            DebugLog.add("Droplet skipped: no overlay view")
            return
        }
        if (state == State.COLLAPSED) {
            DebugLog.add("Droplet on screen-on ring")
            ledView.post { ledView.playDropletBelow(color, DROP_DP * ledView.resources.displayMetrics.density, retract = true) }
        } else {
            DebugLog.add("Droplet skipped: state=$state")
        }
    }

    fun hide() {
        val r = root ?: return
        try { LedController.removeListener(listener) } catch (_: Exception) {}
        main.removeCallbacksAndMessages(null)
        try { wm?.removeViewImmediate(r) } catch (_: Exception) {}
        cleanupRefs()
    }

    private fun cleanupRefs() {
        dragTarget = DragTarget.NONE
        pendingDragTarget = DragTarget.NONE
        root = null; led = null; info = null; peek = null; wm = null; windowCtx = null
        usingA11y = false; state = State.COLLAPSED; peekPkg = null
    }

    private const val DROP_DP = 26f
    private const val PEEK_MS = 12_000L
    private const val AUTO_ICONS_MS = 8_000L

    fun updateBatteryText() {
        led?.setBatteryText(LedController.batteryStatusText())
    }

    fun updateCpuText() {
        val prefs = LedController.prefs
        led?.setCpuLoad(
            LedController.cpuStatusText(),
            prefs.cpuPos,
            prefs.cpuOffsetXDp,
            prefs.cpuOffsetYDp,
            prefs.cpuFontSizeSp,
            LedController.cpuUsage,
            prefs.cpuColorByLoad,
        )
    }
}
