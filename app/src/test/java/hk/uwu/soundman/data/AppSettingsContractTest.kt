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
            ),
            AppSettingsKeys.all,
        )
        assertEquals(5, AppSettingsKeys.all.size)
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
    }
}
