package hk.uwu.soundman.data

import hk.uwu.soundman.model.EntryPosition
import hk.uwu.soundman.model.PanelMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsContractTest {
    @Test
    fun defaultsKeepOptionalVisualFeaturesDisabled() {
        val settings = AppSettings()

        assertFalse(settings.smoothCornersEnabled)
        assertFalse(settings.volumePercentEnabled)
        assertFalse(settings.systemUiBuiltinVolumePanelEnabled)
        assertFalse(settings.hideSystemAppsEnabled)
        assertFalse(settings.alarmFirstEnabled)
        assertFalse(settings.liquidGlassEnabled)
        assertFalse(settings.liquidGlassRefractionEnabled)
        assertEquals(AppSettingsDefaults.SMOOTH_CORNERS_ENABLED, settings.smoothCornersEnabled)
        assertEquals(AppSettingsDefaults.VOLUME_PERCENT_ENABLED, settings.volumePercentEnabled)
        assertEquals(
            AppSettingsDefaults.SYSTEM_UI_BUILTIN_VOLUME_PANEL_ENABLED,
            settings.systemUiBuiltinVolumePanelEnabled,
        )
        assertEquals(
            AppSettingsDefaults.HIDE_SYSTEM_APPS_ENABLED,
            settings.hideSystemAppsEnabled,
        )
        assertEquals(
            AppSettingsDefaults.ALARM_FIRST_ENABLED,
            settings.alarmFirstEnabled,
        )
        assertEquals(
            AppSettingsDefaults.LIQUID_GLASS_ENABLED,
            settings.liquidGlassEnabled,
        )
        assertEquals(
            AppSettingsDefaults.LIQUID_GLASS_REFRACTION_ENABLED,
            settings.liquidGlassRefractionEnabled,
        )
        assertEquals(20, settings.liquidGlassBlurRadius)
        assertEquals(
            AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS,
            settings.liquidGlassBlurRadius,
        )
        assertEquals(0, AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS_MIN)
        assertEquals(20, AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS_MAX)
        assertEquals(
            AppSettingsDefaults.LIQUID_GLASS_BLEND_COLOR,
            settings.liquidGlassBlendColor,
        )
        assertEquals(0x20FFFFFF, AppSettingsDefaults.LIQUID_GLASS_BLEND_COLOR)
        // 入口默认仍在音量条上方：新增档位不能改变既有用户的排布。
        assertEquals(AppSettingsDefaults.ENTRY_POSITION, settings.entryPosition)
        assertEquals(EntryPosition.DEFAULT, settings.entryPosition)
        assertEquals(EntryPosition.ABOVE, settings.entryPosition)
        // 播放门控默认开启：与音质音效官方入口一致，也是该判定首次发布时的行为。
        assertEquals(
            AppSettingsDefaults.ENTRY_PLAYBACK_ONLY_ENABLED,
            settings.entryPlaybackOnlyEnabled,
        )
        assertTrue(settings.entryPlaybackOnlyEnabled)
        // 面板玻璃默认跟随 HyperLight：与系统展开面板同一条链路。
        assertEquals(
            AppSettingsDefaults.HYPER_LIGHT_PANEL_GLASS_ENABLED,
            settings.hyperLightPanelGlassEnabled,
        )
        assertTrue(settings.hyperLightPanelGlassEnabled)
    }

    /**
     * 面板材质不落盘，是从两个既有开关推导出来的。
     *
     * 这样老用户升级后「跟随 HyperLight」或「自研玻璃」的选择不会被重置，
     * 也不需要给跨进程镜像再搬一个新键。
     */
    @Test
    fun panelMaterialIsDerivedFromTheTwoGlassSwitches() {
        assertEquals(PanelMaterial.DEFAULT, AppSettings().panelMaterial)
        assertEquals(PanelMaterial.HYPERLIGHT, AppSettings().panelMaterial)
        // 两个开关同时为真时 HyperLight 优先：面板挂上它的玻璃后自研玻璃不会再叠加。
        assertEquals(
            PanelMaterial.HYPERLIGHT,
            AppSettings(hyperLightPanelGlassEnabled = true, liquidGlassEnabled = true)
                .panelMaterial,
        )
        assertEquals(
            PanelMaterial.HYPERLIGHT,
            AppSettings(hyperLightPanelGlassEnabled = true, liquidGlassEnabled = false)
                .panelMaterial,
        )
        assertEquals(
            PanelMaterial.LIQUID,
            AppSettings(hyperLightPanelGlassEnabled = false, liquidGlassEnabled = true)
                .panelMaterial,
        )
        assertEquals(
            PanelMaterial.OFFICIAL,
            AppSettings(hyperLightPanelGlassEnabled = false, liquidGlassEnabled = false)
                .panelMaterial,
        )
    }

    @Test
    fun preferenceKeysAreStableAndDistinct() {
        assertEquals(
            setOf(
                "smooth_corners_enabled",
                "volume_percent_enabled",
                "system_ui_builtin_volume_panel_enabled",
                "hide_system_apps_enabled",
                "alarm_first_enabled",
                "liquid_glass_enabled",
                "liquid_glass_refraction_enabled",
                "liquid_glass_blur_radius",
                "liquid_glass_blend_color",
                "entry_position",
                "entry_playback_only_enabled",
                "entry_material",
                "hyperlight_panel_glass_enabled",
            ),
            AppSettingsKeys.all,
        )
        assertEquals(13, AppSettingsKeys.all.size)
        assertNotEquals(AppSettingsKeys.SMOOTH_CORNERS, AppSettingsKeys.VOLUME_PERCENT)
        assertNotEquals(
            AppSettingsKeys.VOLUME_PERCENT,
            AppSettingsKeys.SYSTEM_UI_BUILTIN_VOLUME_PANEL
        )
        assertNotEquals(
            AppSettingsKeys.SYSTEM_UI_BUILTIN_VOLUME_PANEL,
            AppSettingsKeys.HIDE_SYSTEM_APPS
        )
        assertNotEquals(
            AppSettingsKeys.HIDE_SYSTEM_APPS,
            AppSettingsKeys.ALARM_FIRST
        )
        assertNotEquals(
            AppSettingsKeys.ALARM_FIRST,
            AppSettingsKeys.LIQUID_GLASS
        )
        assertNotEquals(
            AppSettingsKeys.LIQUID_GLASS,
            AppSettingsKeys.LIQUID_GLASS_REFRACTION
        )
        assertNotEquals(
            AppSettingsKeys.LIQUID_GLASS_REFRACTION,
            AppSettingsKeys.LIQUID_GLASS_BLUR_RADIUS
        )
        assertNotEquals(
            AppSettingsKeys.LIQUID_GLASS_BLUR_RADIUS,
            AppSettingsKeys.LIQUID_GLASS_BLEND_COLOR
        )
    }
}
