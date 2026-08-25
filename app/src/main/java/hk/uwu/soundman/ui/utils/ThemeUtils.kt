package hk.uwu.soundman.ui.utils

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * When non-null, forces the theme to dark (true) or light (false),
 * overriding both system setting and user preference.
 */
val LocalForcedDarkTheme = staticCompositionLocalOf<Boolean?> { null }

/**
 * Returns whether the app is in dark theme.
 * Respects LocalForcedDarkTheme if set, otherwise follows system.
 */
@Composable
fun isAppDarkTheme(): Boolean {
    LocalForcedDarkTheme.current?.let { return it }
    return rememberAppSettingDark()
}

/**
 * Returns the dark theme preference from the app settings.
 * Currently follows the system setting directly.
 */
@Composable
fun rememberAppSettingDark(): Boolean = isSystemInDarkTheme()
