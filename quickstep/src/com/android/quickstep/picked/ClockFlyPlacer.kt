/*
 * Copyright (C) 2026 The PenguinOS Project
 * SPDX-FileCopyrightText: 2026 Miku-Picked UI
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.quickstep.picked

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.Workspace
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.model.data.LauncherAppWidgetInfo
import com.android.launcher3.picked.LockscreenClockWidget
import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.launcher3.widget.custom.CustomWidgetManager
import com.android.quickstep.SystemUiProxy
import java.util.function.Predicate

/**
 * Follows Settings.Secure picked_clock_fly: while it is on, the launcher takes part in the unlock
 * animation ([ClockFlyUnlock]) and the "Lockscreen clock" widget is on the home screen, so the
 * lockscreen clock always has somewhere to land. The widget is placed once when the setting goes
 * on (the user may then move or remove it) and taken away when it goes off.
 *
 * From PenguinOS' CosClockPlacer (by redducc).
 */
class ClockFlyPlacer(private val launcher: QuickstepLauncher) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = launcher.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val provider = LockscreenClockWidget.PROVIDER
    private val isClock = Predicate<ItemInfo?> {
        it is LauncherAppWidgetInfo && it.providerName == provider
    }
    private var unlock: ClockFlyUnlock? = null
    private var enabled = LockscreenClockWidget.isEnabled(launcher)
    private val retry = Runnable { sync() }

    private val observer =
        object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                val now = LockscreenClockWidget.isEnabled(launcher)
                if (now == enabled) return
                enabled = now
                registerUnlock()
                sync()
            }
        }

    val unlockController: ClockFlyUnlock?
        get() = unlock

    fun start() {
        launcher.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(LockscreenClockWidget.SETTING_ENABLED),
            false,
            observer,
        )
        registerUnlock()
        sync()
    }

    fun destroy() {
        main.removeCallbacks(retry)
        launcher.contentResolver.unregisterContentObserver(observer)
        if (unlock != null) {
            SystemUiProxy.INSTANCE.get(launcher)
                .setLauncherUnlockAnimationController(launcher.componentName.className, null)
            unlock = null
        }
    }

    private fun registerUnlock() {
        val proxy = SystemUiProxy.INSTANCE.get(launcher)
        val name = launcher.componentName.className
        if (enabled && unlock == null) {
            unlock = ClockFlyUnlock(launcher).also { proxy.setLauncherUnlockAnimationController(name, it) }
        } else if (!enabled && unlock != null) {
            proxy.setLauncherUnlockAnimationController(name, null)
            unlock = null
        }
    }

    /** Places or removes the widget to match the setting. Safe to call at any time. */
    fun sync() {
        main.removeCallbacks(retry)
        val workspace = launcher.workspace
        if (workspace == null || launcher.isWorkspaceLoading || workspace.pageCount == 0) {
            main.postDelayed(retry, RETRY_MS)
            return
        }
        val placed = prefs.getBoolean(KEY_PLACED, false)
        if (enabled && !placed) {
            if (hasClock(workspace) || place(workspace)) {
                prefs.edit().putBoolean(KEY_PLACED, true).apply()
            }
        } else if (!enabled && placed) {
            workspace.removeItemsByMatcher(isClock, false)
            launcher.modelWriter.deleteItemsFromDatabase(isClock, "Clock flight turned off")
            prefs.edit().putBoolean(KEY_PLACED, false).apply()
        }
    }

    private fun hasClock(workspace: Workspace<*>): Boolean {
        for (i in 0 until workspace.pageCount) {
            val page = workspace.getScreenWithId(workspace.getScreenIdForPageIndex(i)) ?: continue
            val children = page.shortcutsAndWidgets
            for (c in 0 until children.childCount) {
                if (isClock.test(children.getChildAt(c).tag as? ItemInfo)) return true
            }
        }
        return false
    }

    private fun place(workspace: Workspace<*>): Boolean {
        val widgets = CustomWidgetManager.INSTANCE.get(launcher)
        val info = widgets.getWidgetProvider(provider) ?: return false
        for (i in 0 until workspace.pageCount) {
            val screenId = workspace.getScreenIdForPageIndex(i)
            val page = workspace.getScreenWithId(screenId) ?: continue
            val spanX = minOf(LockscreenClockWidget.SPAN_X, page.countX)
            val spanY = minOf(LockscreenClockWidget.SPAN_Y, page.countY)
            val cell = IntArray(2)
            if (!page.findCellForSpan(cell, spanX, spanY)) continue
            val item =
                LauncherAppWidgetInfo(widgets.allocateCustomAppWidgetId(provider), provider).apply {
                    this.spanX = spanX
                    this.spanY = spanY
                    minSpanX = info.minSpanX
                    minSpanY = info.minSpanY
                }
            launcher.modelWriter.addItemToDatabase(
                item,
                Favorites.CONTAINER_DESKTOP,
                screenId,
                cell[0],
                cell[1],
            )
            launcher.bindAppWidget(item)
            return true
        }
        return false
    }

    private companion object {
        const val PREFS = "picked_clock_fly"
        const val KEY_PLACED = "home_widget_placed"
        const val RETRY_MS = 500L
    }
}
