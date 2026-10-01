package hk.uwu.soundman.hook.scopes.systemui.hidden

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView

/**
 * 克隆官方静音/免打扰按钮（`miui_ringer_mode_layout`）并绑定官方 RingerButtonHelper。
 *
 * 动机：HyperLight 给铃铛/月亮上的液态玻璃不是一张孤立的 `of0` Drawable ——
 * 描边（HyperLightStrokeUtils）、陀螺仪折射（StrokeGyroTransformer）、触控辉光
 * （LiquidGlassTouchGlow）都挂在它自己的 hook 点上。我们手工调 `zf0.l` 只能拿到
 * 一层裸玻璃，描边/动效全部缺失，无论怎么调参数观感都差一截。
 *
 * 而同类模块 AppVolumeBarHook（MIT）在同一台设备上把官方布局 inflate 出来、
 * 绑上官方 RingerButtonHelper 之后，按钮自动带上了 HyperLight 的完整效果 ——
 * 说明 HyperLight 拦的是官方材质链路本身：谁走官方路，谁就被它覆盖。
 *
 * 所以本类只做一件事：让入口走官方按钮同一条链路。
 * HyperLight 装了就自动带全套玻璃；没装就是官方材质，仍和铃铛月亮一致。
 *
 * 层级参考官方布局：bg_blur -> miui_standard_btn -> icon。
 * RingerButtonHelper 构造函数负责 Folme 触摸绑定与 bg_blur 初始化；
 * updateState() 负责官方折叠态材质。图标必须在 updateState() 之后再替换，
 * 否则会被官方按静音/勿扰状态重设的 tint 覆盖。
 */
internal object OfficialRingerClone {

    private const val TAG = "SoundMan.SystemUi"

    private const val OFFICIAL_LAYOUT_NAME = "miui_ringer_mode_layout"
    private const val ID_BLUR = "bg_blur"
    private const val ID_STANDARD_BTN = "miui_standard_btn"
    private const val ID_ICON = "icon"

    private const val HELPER_CLASS =
        "com.android.systemui.miui.volume.MiuiRingerModeLayout\$RingerButtonHelper"
    private const val HELPER_UPDATE_STATE = "updateState"

    /**
     * 克隆官方按钮。
     *
     * @param officialLayout 官方 `MiuiRingerModeLayout` 实例，作为 helper 的 owner；
     *   为 null 时 helper 绑不上，只能走 [applyFallbackMaterial]。
     * @param iconDrawable SoundMan 入口图标；在官方 updateState() 之后覆盖克隆按钮
     *   的 icon 并清掉官方 tint。
     * @param applyFallbackMaterial helper 绑不上时给 standardBtn 兜底的材质函数，
     *   通常传 [OfficialRingerBlur.applyCollapsedChrome]。
     * @param onClick SoundMan 自己的点击处理（打开面板）。
     *   ⚠️ 必须传：官方 helper 在构造时已经给 `bg_blur` 绑了它自己的
     *   OnClickListener（切换静音/勿扰），而 `bg_blur` 又是我们主动设成可点击的
     *   （官方按压缩小动画绑在它身上）。只给 root 设监听器的话，点击会被内层
     *   `bg_blur` 消费掉 —— 表现就是「点 SoundMan 入口却把铃铛按下去了」。
     *   所以这里必须把它覆盖成我们的：既保住按压动画，又把 click 还给我们。
     * @return 克隆出的按钮根 View；布局 inflate 失败、模板缺层或材质全失败时返回 null。
     */
    fun create(
        targetContext: Context,
        packages: List<String>,
        pluginClassLoader: ClassLoader?,
        officialLayout: View?,
        iconDrawable: Drawable,
        applyFallbackMaterial: (View) -> Boolean,
        onClick: View.OnClickListener,
        log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
    ): View? {
        val root = inflateOfficialButton(targetContext, packages, log) ?: return null
        val blurView = findViewByIdName(root, ID_BLUR, packages)
        val standardView = findViewByIdName(root, ID_STANDARD_BTN, packages)
        val iconView = findViewByIdName(root, ID_ICON, packages) as? ImageView
        if (blurView == null || standardView == null || iconView == null) {
            log(
                Log.INFO,
                TAG,
                "Official ringer clone template incomplete " +
                    "(blur=${blurView != null} standard=${standardView != null} icon=${iconView != null})",
                null,
            )
            return null
        }
        val helperBound = bindOfficialHelper(root, blurView, officialLayout, pluginClassLoader, log)
        if (!helperBound && !applyFallbackMaterial(standardView)) {
            log(
                Log.INFO,
                TAG,
                "Official ringer clone helper binding failed and fallback material refused",
                null,
            )
            return null
        }
        // 官方 updateState() 会按静音/勿扰状态重设 icon 内容与 tint，
        // 所以图标必须等它跑完再覆盖；SoundMan 的 drawable 自带颜色，清掉 tint。
        iconView.setImageDrawable(iconDrawable)
        iconView.imageTintList = null
        root.isClickable = true
        root.isFocusable = true
        // 官方按钮的按压缩小绑定在 bg_blur 上；miui_standard_btn 在其上层，
        // 一旦可点击就把触摸事件全截走，按压动画就再也收不到了。
        standardView.isClickable = false
        standardView.isFocusable = false
        blurView.isClickable = true
        blurView.isFocusable = true
        // 覆盖官方 helper 绑在 bg_blur 上的点击监听：它绑的是「切换静音/勿扰」，
        // 留着就会变成点入口按铃铛。绑定顺序必须在 bindOfficialHelper 之后。
        blurView.setOnClickListener(onClick)
        log(
            Log.INFO,
            TAG,
            "Official ringer button cloned (helper=$helperBound) at " +
                Integer.toHexString(System.identityHashCode(root)),
            null,
        )
        return root
    }

    private fun inflateOfficialButton(
        targetContext: Context,
        packages: List<String>,
        log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
    ): View? {
        packages.distinct().forEach { pkg ->
            try {
                val pkgContext = if (pkg == targetContext.packageName) {
                    targetContext
                } else {
                    targetContext.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
                }
                val layoutId = pkgContext.resources.getIdentifier(OFFICIAL_LAYOUT_NAME, "layout", pkg)
                if (layoutId == 0) return@forEach
                val view = LayoutInflater.from(pkgContext).inflate(layoutId, null, false)
                if (view != null) {
                    log(Log.INFO, TAG, "Inflated official $OFFICIAL_LAYOUT_NAME from $pkg", null)
                    return view
                }
            } catch (throwable: Throwable) {
                log(
                    Log.INFO,
                    TAG,
                    "Could not inflate official $OFFICIAL_LAYOUT_NAME from $pkg",
                    throwable,
                )
            }
        }
        return null
    }

    private fun bindOfficialHelper(
        clonedRoot: View,
        clonedBlur: View,
        officialLayout: View?,
        pluginClassLoader: ClassLoader?,
        log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
    ): Boolean {
        if (officialLayout == null) return false
        val loaders = listOfNotNull(
            officialLayout.javaClass.classLoader,
            pluginClassLoader,
            OfficialRingerClone::class.java.classLoader,
        ).distinct()
        loaders.forEach { loader ->
            try {
                val helperClass = Class.forName(HELPER_CLASS, false, loader)
                val constructor = helperClass.declaredConstructors.firstOrNull { ctor ->
                    val types = ctor.parameterTypes
                    types.size == 4 &&
                        View::class.java.isAssignableFrom(types[1]) &&
                        types[2] == Boolean::class.javaPrimitiveType &&
                        types[3] == Boolean::class.javaPrimitiveType
                } ?: return@forEach
                constructor.isAccessible = true
                // 第二个布尔传 false：克隆按钮不承载静音/勿扰状态，只走官方关闭态胶囊。
                val helper = constructor.newInstance(officialLayout, clonedBlur, false, false)
                helperClass.getDeclaredMethod(HELPER_UPDATE_STATE).apply {
                    isAccessible = true
                }.invoke(helper)
                log(
                    Log.INFO,
                    TAG,
                    "Bound official RingerButtonHelper to cloned SoundMan entry (loader=${loader.javaClass.name})",
                    null,
                )
                return true
            } catch (throwable: Throwable) {
                log(
                    Log.INFO,
                    TAG,
                    "Could not bind official RingerButtonHelper from ${loader.javaClass.name}",
                    throwable,
                )
            }
        }
        return false
    }

    private fun findViewByIdName(
        scope: View,
        name: String,
        packages: List<String>,
    ): View? {
        packages.distinct().forEach { pkg ->
            val id = runCatching {
                scope.context.resources.getIdentifier(name, "id", pkg)
            }.getOrDefault(0)
            if (id == 0) return@forEach
            val view = scope.findViewById<View>(id)
            if (view != null) return view
        }
        return null
    }
}
