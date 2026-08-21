package hk.uwu.soundman.hook.scopes.systemui.hidden

import org.junit.Assert.assertEquals
import org.junit.Test

class OfficialExpandedMaterialPolicyTest {
    @Test
    fun advancedMaterialAlwaysUsesOfficialGlassPath() {
        assertEquals(
            OfficialExpandedMaterialMode.ADVANCED,
            OfficialExpandedMaterialPolicy.choose(
                advancedMaterialEffective = true,
                themeBlurOpened = false,
                lowEndDevice = true,
                defaultPluginTheme = false,
            ),
        )
        // OS4 无 getBackgroundBlurOpenedInDefaultTheme（探测恒 false），advanced 依旧优先。
        assertEquals(
            OfficialExpandedMaterialMode.ADVANCED,
            OfficialExpandedMaterialPolicy.choose(
                advancedMaterialEffective = true,
                themeBlurOpened = true,
                lowEndDevice = false,
                defaultPluginTheme = true,
            ),
        )
    }

    @Test
    fun os3ThemeBlurTakesPriorityOverSBlur() {
        // OS3 官方树：getBackgroundBlurOpenedInDefaultTheme 为真是首查分支。
        assertEquals(
            OfficialExpandedMaterialMode.THEME_BLUR,
            OfficialExpandedMaterialPolicy.choose(
                advancedMaterialEffective = false,
                themeBlurOpened = true,
                lowEndDevice = true,
                defaultPluginTheme = false,
            ),
        )
    }

    @Test
    fun nonAdvancedUsesSBlurOnlyForEligiblePluginTheme() {
        // 两代共同的后备分支：探测全假时按 S blur 条件落位。
        assertEquals(
            OfficialExpandedMaterialMode.BLUR_FOR_S,
            OfficialExpandedMaterialPolicy.choose(
                advancedMaterialEffective = false,
                themeBlurOpened = false,
                lowEndDevice = false,
                defaultPluginTheme = true,
            ),
        )
        assertEquals(
            OfficialExpandedMaterialMode.STATIC,
            OfficialExpandedMaterialPolicy.choose(
                advancedMaterialEffective = false,
                themeBlurOpened = false,
                lowEndDevice = true,
                defaultPluginTheme = true,
            ),
        )
        assertEquals(
            OfficialExpandedMaterialMode.STATIC,
            OfficialExpandedMaterialPolicy.choose(
                advancedMaterialEffective = false,
                themeBlurOpened = false,
                lowEndDevice = false,
                defaultPluginTheme = false,
            ),
        )
    }
}
