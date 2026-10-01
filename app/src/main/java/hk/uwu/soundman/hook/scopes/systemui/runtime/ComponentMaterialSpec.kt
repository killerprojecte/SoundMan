package hk.uwu.soundman.hook.scopes.systemui.runtime

/**
 * 入口圆钮材质同步的纯数据约定。
 *
 * 这些名字全部来自 HyperOS 的 MIUIX 官方材质链路（`miuix.theme.token` /
 * `miuix.core.util`），编译 classpath 里没有它们，只能反射；把名字集中放在这
 * 里是为了让 JVM 单测能直接验证常量，而不用去读反射代码里的字符串字面量。
 *
 * 场景名与混色字段名与 HyperLight 给音量条 / 控制中心组件用的是同一套
 * （`components` 场景 + `Colored_Regular_*` 混色），因此入口拿到的是和音量条
 * 同源的官方材质，而不是模块自己另画一层。
 */
object ComponentMaterialSpec {
    /** 官方高光材质入口：`applyViewMaterial(View, token, radius, clip)`。 */
    const val MATERIAL_UTILS = "miuix.core.util.HyperMaterialUtils"

    /** 材质构造器，官方用法是 `Builder(30, 0f, "light" | "dark")`。 */
    const val MATERIAL_TOKEN_BUILDER = "miuix.theme.token.MaterialToken\$Builder"

    /** 昼夜材质容器，官方用法是 `MaterialDayNightToken(light, dark)`。 */
    const val DAY_NIGHT_TOKEN = "miuix.theme.token.MaterialDayNightToken"

    /** 昼夜容器包装，官方用法是 `MaterialDayNightConfig.create(token)`。 */
    const val DAY_NIGHT_CONFIG = "miuix.core.util.MaterialDayNightConfig"

    /** 材质混色常量表。 */
    const val COLOR_BLEND_TOKEN = "miuix.theme.token.ColorBlendToken"

    /** 构造材质时的官方默认厚度档位与圆角补偿，与官方调用保持一致。 */
    const val TOKEN_THICKNESS = 30
    const val TOKEN_ROUND_COMPENSATION = 0f

    /** 浅色材质构造参数。 */
    const val TOKEN_KIND_LIGHT = "light"

    /** 深色材质构造参数。 */
    const val TOKEN_KIND_DARK = "dark"

    /**
     * 音量条 / 控制中心组件共用的场景名。
     *
     * HyperLight 对 `VolumeColumn.mProgressView`（音量条滑条）用的就是这一个，
     * 入口沿用它才能拿到同款材质。
     */
    const val SCENE_COMPONENTS = "components"

    /** 浅色混色字段名，按优先级查找。 */
    val LIGHT_BLEND_FIELDS: List<String> = listOf(
        "Colored_Regular_Light",
        "Colored_Thin_Light",
        "Colored_Thick_Light",
        "Colored_Extra_Thin_Light",
    )

    /** 深色混色字段名，按优先级查找。 */
    val DARK_BLEND_FIELDS: List<String> = listOf(
        "Colored_Regular_Dark",
        "Colored_Thin_Dark",
        "Colored_Thick_Dark",
        "Colored_Extra_Thin_Dark",
    )

    /** 场景 →（浅色混色字段，深色混色字段）。 */
    private val BLEND_FIELDS: Map<String, Pair<List<String>, List<String>>> = mapOf(
        SCENE_COMPONENTS to (LIGHT_BLEND_FIELDS to DARK_BLEND_FIELDS),
    )

    /** 指定场景与昼夜的混色字段名，未知场景返回空表（材质退化为不带混色）。 */
    fun blendFields(scene: String, night: Boolean): List<String> =
        BLEND_FIELDS[scene]?.let { pair -> if (night) pair.second else pair.first } ?: emptyList()
}
