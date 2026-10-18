package com.wanderwildwood.jimeikin.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * Called first inside a bottom sheet: keeps the status-bar icons dark while the sheet is up.
 *
 * The sheet is a window of its own, spread over the whole screen, so it decides the icons
 * while it is open. It picks light icons whenever the phone is in dark theme, but this app
 * stays white either way, and on Android 15 and later the white bar showed white icons.
 * Android 12 is left as it was.
 */
@Composable
fun DarkStatusIconsInSheet() {
    if (Build.VERSION.SDK_INT < 35) return
    val view = LocalView.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }
}
