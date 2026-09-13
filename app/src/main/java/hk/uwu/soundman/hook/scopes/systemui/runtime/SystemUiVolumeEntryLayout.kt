package hk.uwu.soundman.hook.scopes.systemui.runtime

import android.view.View

/**
 * HyperOS 紧凑音量侧栏入口的纯布局/锚点约定。
 *
 * 把资源名顺序、圆钮尺寸、展开可见性从 runtime 里拆出来，
 * 以便 JVM 单测直接验证，而不是去读源码字符串。
 */
object SystemUiVolumeEntryLayout {
    /** 找不到 live 圆钮尺寸和官方 dimen 时的 fail-fast 辅助边长。 */
    const val BUTTON_SIZE_DP = 48

    /** 找不到音量条与 ringer 之间官方空隙时的 fail-fast 间距。 */
    const val MARGIN_VERTICAL_DP = 4

    /** 仅作 circularButtonSpec 辅助色，禁止再拿它画纯白圆钮。 */
    const val FILL_ARGB = 0xFFFFFFFF.toInt()

    /**
     * 音量条放置锚点，按优先级查找。
     *
     * 音量条不在 `MiuiRingerModeLayout` 里，必须沿 parent 向上在 ancestor 上
     * `getIdentifier` + `findViewById`。只把入口插到这个锚点之前。
     */
    val VOLUME_COLUMN_RESOURCE_NAMES: List<String> = listOf(
        "volume_dialog_columns",
        "volume_dialog_content",
        "volume_dialog_column_collapsed",
        "volume_column_view",
    )

    /**
     * 圆钮样式模板，只用来抄尺寸 / icon / outline，禁止在这里插入。
     *
     * `MiuiRingerModeLayout` 里免打扰在静音下面，所以先认 dnd，没有再退到 ringer。
     */
    val STYLE_TEMPLATE_RESOURCE_NAMES: List<String> = listOf(
        "dnd_layout",
        "ringer_layout",
    )

    /**
     * SystemUI 里 phone 图标的 drawable 名，按优先级查找。
     *
     * 禁止再退回模块 `R.mipmap.ic_soundman_logo`。
     */
    val ICON_RESOURCE_NAMES: List<String> = listOf(
        "ic_miplay_phone",
        "miplay_phone",
    )

    /**
     * 免打扰圆钮内部的公开 id 名。
     *
     * 只用来读模板尺寸/背景/icon，禁止把这些系统 id 留在我们自己的入口树上。
     */
    val DND_CHILD_RESOURCE_NAMES: List<String> = listOf(
        "miui_standard_btn",
        "bg_blur",
        "icon",
    )

    /**
     * 折叠态免打扰 chrome 的官方 drawable 名，按优先级查找。
     *
     * 必须设到内层 chrome，禁止再抄 live `View.background`（HyperOS 上经常是 null）。
     */
    val BUTTON_BACKGROUND_RESOURCE_NAMES: List<String> = listOf(
        "o3_miui_volume_ringer_btn_first_bg_collapsed",
        "o3_miui_volume_ringer_btn_first_bg_blur",
    )

    /**
     * 折叠态免打扰底层模糊的官方 drawable 名，按优先级查找。
     *
     * 仅当主题没开 live MiBlur 时才用这些静态图。
     */
    val BLUR_BACKGROUND_RESOURCE_NAMES: List<String> = listOf(
        "o3_miui_volume_ringer_bg_blur",
        "o3_miui_volume_ringer_bg_blur_cc",
    )

    /**
     * 官方 live blur 的插件类名。
     *
     * 新系统折叠态音量条走 `MiBackgroundStyle`；老系统仍用 backdrop。
     * 主题打开 blur 时必须走这些类，禁止再用静态 blur 图冒充。
     */
    val LIVE_BLUR_CLASS_NAMES: List<String> = listOf(
        "miui.systemui.util.MiBlurCompat",
        "miui.systemui.util.MiBackgroundStyle",
        "com.android.systemui.miui.volume.Util",
        "com.android.systemui.miui.volume.RingerButtonRes",
        "com.miui.blur.sdk.backdrop.a",
    )

    /**
     * 官方圆角半径 dimen。没有 live outline 时用它 `setRoundRect`。
     */
    val BUTTON_RADIUS_DIMEN_NAMES: List<String> = listOf(
        "o3_miui_ringer_btn_radius",
    )

    /**
     * 模板缺失时的圆钮宽度 dimen。
     */
    val BUTTON_WIDTH_DIMEN_NAMES: List<String> = listOf(
        "o3_miui_ringer_btn_width",
    )

    /**
     * 模板缺失时的圆钮高度 dimen。
     */
    val BUTTON_HEIGHT_DIMEN_NAMES: List<String> = listOf(
        "o3_miui_ringer_btn_height",
    )

    /**
     * 模板缺失时的图标边长 dimen。
     */
    val ICON_SIZE_DIMEN_NAMES: List<String> = listOf(
        "o3_miui_ringer_icon_size",
    )

    /**
     * DND 展开 timer 的公开 id 名。
     *
     * 插入时若它已经 VISIBLE，说明面板已展开，入口必须立刻 GONE。
     */
    const val TIMER_LAYOUT_RESOURCE_NAME = "timer_layout"

    /**
     * 官方折叠态音量面板背景高度 dimen，按优先级查找。
     *
     * 横屏折叠态 `MiuiVolumeDialogRes.getMarginTop` 用 `(屏幕高 - 该高度) / 2`
     * 做竖直居中；模块把入口插进 `MiuiVolumeDialogView` 后实际高度变大，
     * 需要按同一高度基准做补偿。`miui_volume_background_height_cc` 是
     * 控制中心面板（needShowDialog=false）的等价 dimen，作为兜底查找。
     */
    val CENTERED_HEIGHT_DIMEN_NAMES: List<String> = listOf(
        "o3_miui_volume_background_height",
        "miui_volume_background_height_cc",
    )

    /**
     * 音量面板展开时隐藏第三颗只有紧凑态的入口。
     *
     * @param expanded `MiuiRingerModeLayout.updateExpandedH` 的参数
     * @return [View.GONE] 或 [View.VISIBLE]
     */
    fun entryVisibility(expanded: Boolean): Int =
        if (expanded) View.GONE else View.VISIBLE

    /**
     * 资源包回退顺序：先当前 Context 包名，再插件包，再 SystemUI。
     *
     * @param contextPackageName `root.context.packageName`，空串会被丢掉。
     */
    fun resourcePackages(contextPackageName: String): List<String> {
        val packages = LinkedHashSet<String>()
        if (contextPackageName.isNotBlank()) {
            packages += contextPackageName
        }
        packages += PLUGIN_PACKAGE
        packages += SYSTEM_UI_PACKAGE
        return packages.toList()
    }

    /**
     * 找不到 DND 模板尺寸时的 fail-fast 辅助规格。
     *
     * 默认路径必须抄 DND 实测尺寸/背景，不得用这组数据画一颗纯白圆钮。
     */
    fun circularButtonSpec(): CircularButtonSpec = CircularButtonSpec(
        sizeDp = BUTTON_SIZE_DP,
        marginVerticalDp = MARGIN_VERTICAL_DP,
        wrapContent = true,
        centerHorizontal = true,
        oval = true,
        fillArgb = FILL_ARGB,
    )

    private const val PLUGIN_PACKAGE = "miui.systemui.plugin"
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
}

/**
 * 侧栏圆钮的纯数据规格。
 *
 * @param sizeDp 宽高 dp
 * @param marginVerticalDp 上下 margin dp
 * @param wrapContent 是否使用 WRAP_CONTENT，禁止 MATCH_PARENT 宽条
 * @param centerHorizontal 是否水平居中
 * @param oval 是否椭圆/正圆背景
 * @param fillArgb 填充色
 */
data class CircularButtonSpec(
    val sizeDp: Int,
    val marginVerticalDp: Int,
    val wrapContent: Boolean,
    val centerHorizontal: Boolean,
    val oval: Boolean,
    val fillArgb: Int,
)
