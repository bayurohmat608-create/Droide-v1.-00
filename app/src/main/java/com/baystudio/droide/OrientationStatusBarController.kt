package com.baystudio.droide

import android.content.res.Configuration
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat









internal fun applyDroideOrientationStatusBar(
    window: Window,
    orientation: Int,
    hideForPortraitEditor: Boolean = false,
) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    val shouldHide = orientation == Configuration.ORIENTATION_LANDSCAPE ||
        (orientation == Configuration.ORIENTATION_PORTRAIT && hideForPortraitEditor)
    if (shouldHide) {
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
    } else {
        controller.show(WindowInsetsCompat.Type.statusBars())
    }
}
