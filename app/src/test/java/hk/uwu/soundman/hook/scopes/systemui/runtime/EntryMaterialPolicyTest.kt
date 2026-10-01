package hk.uwu.soundman.hook.scopes.systemui.runtime

import hk.uwu.soundman.model.EntryMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryMaterialPolicyTest {
    @Test
    fun liquidGlassWinsWhenPanelGlassIsOn() {
        assertEquals(
            EntryMaterialMode.LIQUID_GLASS,
            EntryMaterialPolicy.choose(
                componentMaterialAvailable = true,
                liquidGlassEnabled = true,
            ),
        )
        assertEquals(
            EntryMaterialMode.LIQUID_GLASS,
            EntryMaterialPolicy.choose(
                componentMaterialAvailable = false,
                liquidGlassEnabled = true,
            ),
        )
    }

    @Test
    fun officialComponentMaterialIsUsedWhenPanelGlassIsOff() {
        assertEquals(
            EntryMaterialMode.COMPONENT,
            EntryMaterialPolicy.choose(componentMaterialAvailable = true, liquidGlassEnabled = false),
        )
    }

    @Test
    fun ringerChromeIsTheLastResort() {
        assertEquals(
            EntryMaterialMode.RINGER,
            EntryMaterialPolicy.choose(
                componentMaterialAvailable = false,
                liquidGlassEnabled = false,
            ),
        )
    }

    @Test
    fun hyperLightMaterialUsesItsGlassWhenReady() {
        assertEquals(
            EntryMaterialMode.HYPERLIGHT_GLASS,
            EntryMaterialPolicy.choose(
                componentMaterialAvailable = true,
                liquidGlassEnabled = true,
                entryMaterial = EntryMaterial.HYPERLIGHT,
                hyperLightReady = true,
            ),
        )
    }

    @Test
    fun hyperLightMaterialFallsBackToOwnGlassWhenNotReady() {
        // 跟随拿不到 HyperLight 时不该让入口变成裸按钮：先退自研玻璃，再退官方材质。
        assertEquals(
            EntryMaterialMode.LIQUID_GLASS,
            EntryMaterialPolicy.choose(
                componentMaterialAvailable = true,
                liquidGlassEnabled = true,
                entryMaterial = EntryMaterial.HYPERLIGHT,
                hyperLightReady = false,
            ),
        )
        assertEquals(
            EntryMaterialMode.COMPONENT,
            EntryMaterialPolicy.choose(
                componentMaterialAvailable = true,
                liquidGlassEnabled = false,
                entryMaterial = EntryMaterial.HYPERLIGHT,
                hyperLightReady = false,
            ),
        )
        assertEquals(
            EntryMaterialMode.RINGER,
            EntryMaterialPolicy.choose(
                componentMaterialAvailable = false,
                liquidGlassEnabled = false,
                entryMaterial = EntryMaterial.HYPERLIGHT,
                hyperLightReady = false,
            ),
        )
    }

    @Test
    fun componentMaterialIgnoresHyperLightAndOwnGlass() {
        // 显式选官方高光材质时，就算 HyperLight 可用也别抢，用户要的就是这个观感。
        assertEquals(
            EntryMaterialMode.COMPONENT,
            EntryMaterialPolicy.choose(
                componentMaterialAvailable = true,
                liquidGlassEnabled = true,
                entryMaterial = EntryMaterial.COMPONENT,
                hyperLightReady = true,
            ),
        )
        assertEquals(
            EntryMaterialMode.RINGER,
            EntryMaterialPolicy.choose(
                componentMaterialAvailable = false,
                liquidGlassEnabled = true,
                entryMaterial = EntryMaterial.COMPONENT,
                hyperLightReady = true,
            ),
        )
    }

    @Test
    fun liquidMaterialNeverUsesHyperLightGlass() {
        assertEquals(
            EntryMaterialMode.LIQUID_GLASS,
            EntryMaterialPolicy.choose(
                componentMaterialAvailable = true,
                liquidGlassEnabled = true,
                entryMaterial = EntryMaterial.LIQUID,
                hyperLightReady = true,
            ),
        )
    }

    @Test
    fun nightModeReadsConfigurationMask() {
        assertTrue(EntryMaterialPolicy.isNight(0x20))
        assertTrue(EntryMaterialPolicy.isNight(0x21))
        assertFalse(EntryMaterialPolicy.isNight(0x10))
        assertFalse(EntryMaterialPolicy.isNight(0x11))
        assertFalse(EntryMaterialPolicy.isNight(0))
    }

    @Test
    fun componentSceneMatchesTheVolumeBar() {
        // HyperLight 对 VolumeColumn.mProgressView 用的场景名就是这一个，
        // 改成别的场景入口就会和音量条不同源。
        assertEquals("components", ComponentMaterialSpec.SCENE_COMPONENTS)
    }

    @Test
    fun blendFieldFallbacksArePaired() {
        assertEquals(
            ComponentMaterialSpec.LIGHT_BLEND_FIELDS.size,
            ComponentMaterialSpec.DARK_BLEND_FIELDS.size,
        )
        ComponentMaterialSpec.LIGHT_BLEND_FIELDS.indices.forEach { index ->
            assertTrue(ComponentMaterialSpec.LIGHT_BLEND_FIELDS[index].endsWith("_Light"))
            assertTrue(ComponentMaterialSpec.DARK_BLEND_FIELDS[index].endsWith("_Dark"))
            assertEquals(
                ComponentMaterialSpec.LIGHT_BLEND_FIELDS[index].removeSuffix("_Light"),
                ComponentMaterialSpec.DARK_BLEND_FIELDS[index].removeSuffix("_Dark"),
            )
        }
    }

    @Test
    fun unknownSceneHasNoBlendFields() {
        assertTrue(ComponentMaterialSpec.blendFields("nope", night = false).isEmpty())
        assertTrue(
            ComponentMaterialSpec.blendFields(ComponentMaterialSpec.SCENE_COMPONENTS, false).isNotEmpty()
        )
    }
}
