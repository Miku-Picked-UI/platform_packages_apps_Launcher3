/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.picked

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo
import com.android.launcher3.widget.LauncherAppWidgetProviderInfo.CLS_CUSTOM_WIDGET_PREFIX
import com.android.systemui.plugins.CustomWidgetPlugin
import kotlin.math.min

/**
 * The "Lockscreen clock" home screen widget: shows the lockscreen clock (drawn by SystemUI's
 * HomeClockRenderer), and is where the clock lands when it flies home on unlock (see
 * ClockFlyUnlock).
 *
 * From PenguinOS' lockscreen clock widget (by redducc). This launcher only knows custom widgets
 * through plugins, so it is a [CustomWidgetPlugin] that CustomWidgetManager registers itself.
 */
class LockscreenClockWidget : CustomWidgetPlugin {

    override fun onCreate(sysuiContext: Context?, pluginContext: Context?) {}

    override fun onDestroy() {}

    override fun updateWidgetInfo(info: AppWidgetProviderInfo, context: Context) {
        info.label = context.getString(R.string.picked_lockscreen_clock_widget)
        info.previewLayout = 0
        info.previewImage = 0
        info.descriptionRes = 0
        info.resizeMode =
            AppWidgetProviderInfo.RESIZE_HORIZONTAL or AppWidgetProviderInfo.RESIZE_VERTICAL
        (info as? LauncherAppWidgetProviderInfo)?.apply {
            spanX = SPAN_X
            spanY = SPAN_Y
            minSpanX = 2
            minSpanY = 1
            maxSpanX = Int.MAX_VALUE
            maxSpanY = Int.MAX_VALUE
        }
    }

    override fun onViewCreated(parent: AppWidgetHostView) {
        parent.clipChildren = false
        parent.clipToPadding = false
        parent.addView(
            LockscreenClockView(parent.context),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    companion object {
        /** The widget's provider, as CustomWidgetManager names plugin widgets. */
        @JvmField
        val PROVIDER =
            ComponentName("android", CLS_CUSTOM_WIDGET_PREFIX + LockscreenClockWidget::class.java.name)

        const val SPAN_X = 4
        const val SPAN_Y = 2

        /** Settings.Secure (shared with SystemUI): fly the lockscreen clock home on unlock. */
        const val SETTING_ENABLED = "picked_clock_fly"

        @JvmStatic
        fun isEnabled(context: Context): Boolean =
            Settings.Secure.getInt(context.contentResolver, SETTING_ENABLED, 0) != 0

        /** The clock view inside a widget's host view, if [view] is one of these widgets. */
        @JvmStatic
        fun clockOf(view: View?): LockscreenClockView? {
            val group = view as? ViewGroup ?: return null
            for (i in 0 until group.childCount) {
                (group.getChildAt(i) as? LockscreenClockView)?.let { return it }
            }
            return null
        }
    }
}

/** Shows SystemUI's snapshot of the lockscreen clock, fitted and centred. */
class LockscreenClockView(context: Context) : FrameLayout(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var session: Messenger? = null
    private var opening = false
    private val inbox = Messenger(Handler(Looper.getMainLooper()) { onState(it); true })
    private var hidden = false

    /** The clock picture SystemUI sent last. */
    var snapshot: Bitmap? = null
        private set

    private val release = Runnable {
        send(MSG_DESTROY)
        session = null
    }

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        MAIN_EXECUTOR.handler.removeCallbacks(release)
        open()
    }

    override fun onDetachedFromWindow() {
        MAIN_EXECUTOR.handler.postDelayed(release, RELEASE_DELAY_MS)
        super.onDetachedFromWindow()
    }

    private fun open() {
        if (session != null || opening) return
        opening = true
        val metrics = resources.displayMetrics
        val request =
            Bundle().apply {
                putInt(KEY_WIDTH, metrics.widthPixels)
                putInt(KEY_HEIGHT, metrics.widthPixels / 2)
                putInt(KEY_DISPLAY_ID, display?.displayId ?: 0)
                putParcelable(KEY_CALLBACK, inbox)
            }
        UI_HELPER_EXECUTOR.execute {
            val reply =
                runCatching { context.contentResolver.call(PROVIDER, METHOD, null, request) }
                    .onFailure { Log.e(TAG, "SystemUI would not draw the clock", it) }
                    .getOrNull()
            MAIN_EXECUTOR.execute {
                opening = false
                session = reply?.getParcelable(KEY_CALLBACK, Messenger::class.java)
            }
        }
    }

    private fun send(what: Int) {
        runCatching { session?.send(Message.obtain(null, what)) }
    }

    private fun onState(message: Message) {
        if (message.what != MSG_STATE) return
        message.data.getParcelable(KEY_SNAPSHOT, Bitmap::class.java)?.let {
            snapshot = it
            invalidate()
        }
    }

    /** Hides the clock while the flying copy lands on it. */
    fun setClockHidden(hide: Boolean) {
        if (hidden == hide) return
        hidden = hide
        invalidate()
    }

    /** Where the clock picture is drawn, in this view's coordinates. */
    fun imageRect(): RectF? {
        val image = snapshot ?: return null
        if (width <= 0 || height <= 0) return null
        val scale = min(width / image.width.toFloat(), height / image.height.toFloat())
        val w = image.width * scale
        val h = image.height * scale
        return RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    /** The clock picture's rect in [ancestor]'s coordinates and the scale on the way there. */
    fun clockRectIn(ancestor: View): Pair<RectF, Float>? {
        val local = imageRect() ?: return null
        val point = floatArrayOf(0f, 0f)
        val scale = Utilities.getDescendantCoordRelativeToAncestor(this, ancestor, point, false)
        return RectF(
            point[0] + local.left * scale,
            point[1] + local.top * scale,
            point[0] + local.right * scale,
            point[1] + local.bottom * scale,
        ) to scale
    }

    override fun onDraw(canvas: Canvas) {
        if (hidden) return
        val image = snapshot ?: return
        canvas.drawBitmap(image, null, imageRect() ?: return, paint)
    }

    private companion object {
        const val TAG = "LockscreenClockWidget"
        const val RELEASE_DELAY_MS = 5000L
        val PROVIDER: Uri = Uri.parse("content://com.android.systemui.customization")

        const val METHOD = "home_clock"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_DISPLAY_ID = "display_id"
        const val KEY_CALLBACK = "callback"
        const val KEY_SNAPSHOT = "snapshot"
        const val MSG_STATE = 1
        const val MSG_DESTROY = 3
    }
}
