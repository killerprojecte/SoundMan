package hk.uwu.soundman.ui.theme

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import hk.uwu.soundman.miuix.theme.sDarkColorScheme
import hk.uwu.soundman.miuix.theme.sLightColorScheme
import hk.uwu.soundman.ui.utils.isAppDarkTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * SoundMan 统一主题入口。
 *
 * 动机：此前 [MainScreen] 和 [SoundPanel] 直接调用 `MiuixTheme { }`（无 controller 重载），
 * 该重载使用 `LocalColors` 的默认值 `lightColorScheme()`，不会跟随系统暗黑模式切换，
 * 导致 App 在暗黑模式下仍显示浅色主题。
 *
 * 本 composable 封装 `MiuixTheme(controller)` 并传入 `ColorSchemeMode.System`，
 * 由 [ThemeController.currentColors] 内部调用 `isSystemInDarkTheme()` 自动切换 light/dark colorScheme。
 * 同时使用 S 自定义的 [sLightColorScheme] / [sDarkColorScheme] 保留项目已有的颜色微调。
 *
 * 通过 [isAppDarkTheme] 读取暗黑状态，保留 [hk.uwu.soundman.ui.utils.LocalForcedDarkTheme]
 * 对 overlay 弹窗等场景的强制暗色覆盖能力。
 *
 * 状态栏图标外观根据暗黑模式自动切换：浅色模式下状态栏图标为深色，暗黑模式下为浅色。
 * 这与 Activity XML 主题中的 `windowLightStatusBar` 配合，确保 Compose 内容加载前后状态栏外观一致。
 *
 * @param content 主题作用域内的 Compose 内容。
 */
@Composable
fun AppTheme(
    content: @Composable () -> Unit,
) {
    val darkTheme = isAppDarkTheme()
    val context = LocalContext.current
    val lightColors = sLightColorScheme()
    val darkColors = sDarkColorScheme()
    val controller = remember(darkTheme) {
        ThemeController(
            colorSchemeMode = ColorSchemeMode.System,
            lightColors = lightColors,
            darkColors = darkColors,
        )
    }

    MiuixTheme(controller = controller) {
        LaunchedEffect(darkTheme) {
            val window = (context as? Activity)?.window ?: return@LaunchedEffect
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
        content()
    }
}
