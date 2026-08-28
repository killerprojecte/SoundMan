package hk.uwu.soundman.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
            ),
            AppSettingsKeys.all,
        )
        assertEquals(9, AppSettingsKeys.all.size)
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
