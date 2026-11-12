/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.quickstep.picked

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.launcher3.CellLayout
import com.android.launcher3.LauncherState
import com.android.launcher3.Utilities
import com.android.launcher3.picked.LockscreenClockView
import com.android.launcher3.picked.LockscreenClockWidget
import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.quickstep.SystemUiProxy
import com.android.systemui.shared.system.smartspace.ILauncherUnlockAnimationController
import com.android.systemui.shared.system.smartspace.SmartspaceState
import kotlin.math.PI
import kotlin.math.pow

/**
 * Flies the lockscreen clock onto the "Lockscreen clock" home screen widget on unlock, while the
 * home screen zooms in under it. PenguinOS' COS clock unlock (by redducc), for clock faces handed
 * over as a picture.
 *
 * SystemUI's KeyguardUnlockAnimationController calls [prepareForUnlock] with the lockscreen
 * clock's bounds on screen (the last argument is 0 for a large clock, -1 for a small one), then
 * [playUnlockAnimation] and hides its own clock; this view draws the clock picture from there to
 * the widget on one spring.
 */
class ClockFlyUnlock(private val launcher: QuickstepLauncher) :
    ILauncherUnlockAnimationController.Stub() {
    private val main = Handler(Looper.getMainLooper())
    private val failSafe = Runnable { finish() }
    private var flight: Flight? = null
    private val springs = ArrayList<SpringAnimation>()
    private var playing = false

    override fun prepareForUnlock(animateClock: Boolean, clockBounds: Rect, fontHeight: Int) {
        val bounds = Rect(clockBounds)
        main.post {
            if (playing && flight != null) return@post
            prepare(if (animateClock) bounds else null, fontHeight)
        }
    }

    override fun setUnlockAmount(amount: Float, forceIfAnimating: Boolean) {
        main.post { if (amount >= 1f && (forceIfAnimating || !playing)) finish() }
    }

    override fun playUnlockAnimation(unlocked: Boolean, duration: Long, startDelay: Long) {
        main.post { if (!unlocked) finish() else if (!playing) play() }
    }

    override fun setSmartspaceSelectedPage(selectedPage: Int) {}

    override fun setSmartspaceVisibility(visibility: Int) {}

    override fun dispatchSmartspaceStateToSysui() {
        main.post { dispatchState() }
    }

    /** There is no smartspace to share; tell SystemUI so it never tries the smartspace flight. */
    fun dispatchState() {
        SystemUiProxy.INSTANCE.get(launcher).notifySysuiSmartspaceStateUpdated(SmartspaceState())
    }

    private fun content(): List<View> = listOfNotNull(launcher.workspace, launcher.hotseat)

    private fun prepare(bounds: Rect?, fontHeight: Int) {
        stop()
        playing = false
        content().forEach {
            it.scaleX = ZOOM
            it.scaleY = ZOOM
            it.alpha = 0f
        }
        flight?.remove()
        flight = null
        val clock = landingClock()
        val face = clock?.snapshot
        if (bounds != null && clock != null && face != null && !bounds.isEmpty) {
            flight = Flight(clock, bounds, face, small = fontHeight < 0)
        }
        main.removeCallbacks(failSafe)
        main.postDelayed(failSafe, FAIL_SAFE_MS)
    }

    private fun play() {
        stop()
        playing = true
        val flight = flight
        val views = content()
        views.forEachIndexed { i, view ->
            springs += spring(view, DynamicAnimation.SCALE_X, ICON_RESPONSE, ICON_BOUNCE)
            springs += spring(view, DynamicAnimation.SCALE_Y, ICON_RESPONSE, ICON_BOUNCE)
            springs +=
                spring(view, DynamicAnimation.ALPHA, ALPHA_RESPONSE, 0f).apply {
                    if (flight == null && i == views.lastIndex) {
                        addEndListener { _, canceled, _, _ -> if (!canceled) finish() }
                    }
                }
        }
        if (flight == null) {
            if (views.isEmpty()) finish()
        } else {
            flight.flying = true
            val progress = FloatValueHolder(0f)
            springs +=
                SpringAnimation(progress).apply {
                    spring = force(1f, CLOCK_RESPONSE, 0f)
                    setMinimumVisibleChange(0.002f)
                    addUpdateListener { _, value, _ -> flight.progress = value }
                    addEndListener { _, canceled, _, _ -> if (!canceled) finish() }
                }
        }
        springs.forEach { it.start() }
    }

    private fun finish() {
        main.removeCallbacks(failSafe)
        stop()
        playing = false
        content().forEach {
            it.scaleX = 1f
            it.scaleY = 1f
            it.alpha = 1f
        }
        flight?.land()
        flight = null
    }

    private fun stop() {
        val running = ArrayList(springs)
        springs.clear()
        running.forEach { it.cancel() }
    }

    private fun spring(
        view: View,
        property: DynamicAnimation.ViewProperty,
        response: Float,
        bounce: Float,
    ) =
        SpringAnimation(view, property).apply {
            spring = force(1f, response, bounce)
            setMinimumVisibleChange(
                if (property == DynamicAnimation.ALPHA) DynamicAnimation.MIN_VISIBLE_CHANGE_ALPHA
                else DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE
            )
        }

    private fun landingClock(): LockscreenClockView? {
        val workspace = launcher.workspace ?: return null
        if (!launcher.isInState(LauncherState.NORMAL)) return null
        val page = workspace.getPageAt(workspace.nextPage) as? CellLayout ?: return null
        val children = page.shortcutsAndWidgets
        for (i in 0 until children.childCount) {
            LockscreenClockWidget.clockOf(children.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** Draws the clock picture between the lockscreen clock's rect and the widget's. */
    private inner class Flight(
        private val clock: LockscreenClockView,
        bounds: Rect,
        private val face: Bitmap,
        private val small: Boolean,
    ) : View(launcher) {
        private val layer: ViewGroup = launcher.dragLayer
        private val start = RectF(bounds)
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val box = RectF()

        var progress = 0f
            set(value) {
                field = value
                invalidate()
            }

        var flying = false
            set(value) {
                field = value
                invalidate()
            }

        init {
            val origin = IntArray(2).also { layer.getLocationOnScreen(it) }
            start.offset(-origin[0].toFloat(), -origin[1].toFloat())
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            clock.setClockHidden(true)
            layer.addView(
                this,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        override fun draw(canvas: Canvas) {
            if (!flying) return
            // Undo the drag layer's insets; the rects are in its coordinates.
            val save = canvas.save()
            canvas.translate(-left.toFloat(), -top.toFloat())
            super.draw(canvas)
            canvas.restoreToCount(save)
        }

        override fun onDraw(canvas: Canvas) {
            // Aim at where the widget's clock is right now, zoom and all, so the flight lands
            // exactly on it whenever it ends.
            val (end, _) = clock.clockRectIn(layer) ?: return
            val from =
                if (!small) start
                else {
                    val height = start.width() * face.height / face.width
                    RectF(
                        start.left,
                        start.centerY() - height / 2f,
                        start.right,
                        start.centerY() + height / 2f,
                    )
                }
            val p = progress
            box.set(
                from.left + (end.left - from.left) * p,
                from.top + (end.top - from.top) * p,
                from.right + (end.right - from.right) * p,
                from.bottom + (end.bottom - from.bottom) * p,
            )
            paint.alpha = if (small) (255 * (p * 2f).coerceAtMost(1f)).toInt() else 255
            canvas.drawBitmap(face, null, box, paint)
        }

        fun land() {
            remove()
            clock.setClockHidden(false)
        }

        fun remove() {
            (parent as? ViewGroup)?.removeView(this)
            if (!flying) clock.setClockHidden(false)
        }
    }

    private companion object {
        const val ZOOM = 1.18f
        const val CLOCK_RESPONSE = 0.3f
        const val ICON_RESPONSE = 0.45f
        const val ICON_BOUNCE = 0.12f
        const val ALPHA_RESPONSE = 0.2f
        const val FAIL_SAFE_MS = 2500L

        /** A spring that settles in about [response] seconds with [bounce] (0 = no overshoot). */
        fun force(to: Float, response: Float, bounce: Float) =
            SpringForce(to).apply {
                val omega = 2 * PI / response
                stiffness = (omega * omega).toFloat()
                dampingRatio = 1f - bounce
            }
    }
}
