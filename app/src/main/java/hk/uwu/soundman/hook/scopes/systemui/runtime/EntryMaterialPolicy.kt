package hk.uwu.soundman.hook.scopes.systemui.runtime

import hk.uwu.soundman.model.EntryMaterial

/**
 * 入口圆钮（音量条上方那颗）的材质同步策略，纯函数，JVM 单测直接验证。
 *
 * 背景：HyperLight 给音量条加的「液态玻璃」是它自己的 Drawable
 * （直接 `View.setBackground` 到 `VolumeColumn.mProgressView` 上），模块无法复用；
 * 但它给音量条 / 控制中心组件套的「高光材质」走的是官方 MIUIX 链路
 * `miuix.core.util.HyperMaterialUtils.applyViewMaterial`，这条链路对任何反射调用
 * 它的模块都开放——所以入口可以拿到和音量条同源的官方材质。
 */
enum class EntryMaterialMode {
    /** 复用 HyperLight 的液态玻璃渲染器，观感与参数都跟它一致。 */
    HYPERLIGHT_GLASS,

    /** 内置展开面板同款液态玻璃，叠在入口现有材质之上。 */
    LIQUID_GLASS,

    /** 和音量条 / 控制中心组件同一套官方 MIUIX 高光材质。 */
    COMPONENT,

    /** 兜底：官方折叠态 ringer 圆钮 chrome，模块原本的行为。 */
    RINGER,
}

object EntryMaterialPolicy {
    /**
     * 材质降级顺序。
     *
     * 用户要的是液态玻璃观感，所以**玻璃优先**：只要面板玻璃开着且入口玻璃开关没关，
     * 入口就走玻璃；否则才退到官方组件材质（和音量条同源、但不是玻璃）；
     * 都没有就保持原本的 ringer chrome。
     *
     * 官方组件材质与液态玻璃不叠加——官方材质自带完整边缘高光，再叠一层玻璃会双重
     * 描边（内置面板对官方 ADVANCED 材质也是这样处理的）。
     *
     * @param componentMaterialAvailable 官方 MIUIX 材质链路可用
     * @param liquidGlassEnabled 面板液态玻璃开关（入口玻璃层复用它的配置）
     */
    fun choose(
        componentMaterialAvailable: Boolean,
        liquidGlassEnabled: Boolean,
        entryMaterial: EntryMaterial = EntryMaterial.DEFAULT,
        hyperLightReady: Boolean = false,
    ): EntryMaterialMode = when (entryMaterial) {
        EntryMaterial.COMPONENT ->
            if (componentMaterialAvailable) EntryMaterialMode.COMPONENT else EntryMaterialMode.RINGER

        EntryMaterial.LIQUID ->
            ownGlass(componentMaterialAvailable, liquidGlassEnabled)

        EntryMaterial.HYPERLIGHT ->
            if (hyperLightReady) {
                EntryMaterialMode.HYPERLIGHT_GLASS
            } else {
                ownGlass(componentMaterialAvailable, liquidGlassEnabled)
            }
    }

    /**
     * SoundMan 自研玻璃的降级链：面板玻璃开着就叠玻璃，否则退官方高光材质，
     * 都没有就保持原本的 ringer chrome。
     *
     * [EntryMaterial.HYPERLIGHT] 拿不到 HyperLight 时同样走这条链——用户要的是
     * 「跟随」，但跟随失败不该让入口变成没有材质的裸按钮。
     */
    private fun ownGlass(
        componentMaterialAvailable: Boolean,
        liquidGlassEnabled: Boolean,
    ): EntryMaterialMode = when {
        liquidGlassEnabled -> EntryMaterialMode.LIQUID_GLASS
        componentMaterialAvailable -> EntryMaterialMode.COMPONENT
        else -> EntryMaterialMode.RINGER
    }

    /**
     * [android.content.res.Configuration.uiMode] 是否处于夜间。
     *
     * 官方材质按昼夜分 token，这里保持纯函数所以不引 android 包，
     * 常量取自 `Configuration.UI_MODE_NIGHT_MASK` / `UI_MODE_NIGHT_YES`。
     */
    fun isNight(uiMode: Int): Boolean = uiMode and UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES

    private const val UI_MODE_NIGHT_MASK = 0x30
    private const val UI_MODE_NIGHT_YES = 0x20
}
