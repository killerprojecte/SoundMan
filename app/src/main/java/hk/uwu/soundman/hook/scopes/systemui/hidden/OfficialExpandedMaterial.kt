package hk.uwu.soundman.hook.scopes.systemui.hidden

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import java.lang.reflect.InvocationTargetException

/**
 * 复用 SystemUI 音量面板展开态的官方材质链路。
 *
 * 这些类仅存在于 miui.systemui.plugin ClassLoader，编译期不可链接，因此这里集中进行反射适配。
 *
 * 官方 MiuiVolumeDialogMotion.updateExpandBgState 的判定树在两代系统上不同：
 *
 * - HyperOS4（plugin 18.x）：`Util.isAdvancedMaterialEffective(ctx)` 为真走
 *   `setMiViewBackgroundStyle + setMiBgBlur`；否则 S 版 blur；否则静态背景。
 * - HyperOS3（plugin 17.x）：`MiBlurCompat.getBackgroundBlurOpenedInDefaultTheme(ctx)` 为真走
 *   `Util.setMiViewBlurAndBlendColor(view, 1, getBgBlandColor(true))`；否则 S 版 blur；否则静态背景。
 *   OS3 没有 isAdvancedMaterialEffective / MiBackgroundStyle / setOutlineRoundRect。
 *
 * 两棵树的 S 分支与静态分支完全一致；这里把两个首查分支都做成软探测（方法缺失按 false 处理），
 * 同一份代码即可在两代插件上各自命中官方原生材质。advanced material 完整调用失败时才依次
 * 尝试官方 S 版 blur 和静态展开背景。
 */
class OfficialExpandedMaterial(
    private val classLoader: ClassLoader,
    private val context: Context,
    private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
) {
    fun apply(view: View): Mode {
        check(view.isAttachedToWindow) { "Official expanded material requires an attached View" }
        applyOutline(view)
        view.background = null
        val advanced = isAdvancedMaterialEffective()
        val themeBlur = themeBlurOpened()
        val lowEnd = isLowEndDevice()
        val defaultPluginTheme = isDefaultPluginTheme()
        return when (
            OfficialExpandedMaterialPolicy.choose(advanced, themeBlur, lowEnd, defaultPluginTheme)
        ) {
            OfficialExpandedMaterialMode.ADVANCED -> {
                check(applyAdvanced(view)) { "Official advanced volume material application failed" }
                Mode.ADVANCED
            }

            OfficialExpandedMaterialMode.THEME_BLUR -> {
                check(applyThemeBlur(view)) { "Official theme-blur volume material application failed" }
                Mode.THEME_BLUR
            }

            OfficialExpandedMaterialMode.BLUR_FOR_S -> {
                check(applyBlurForS(view)) { "Official S volume blur application failed" }
                Mode.BLUR_FOR_S
            }

            OfficialExpandedMaterialMode.STATIC -> {
                view.background = expandedBackground()
                Mode.STATIC
            }
        }
    }

    /**
     * 官方 updateExpandBgSize 的圆角轮廓。
     *
     * OS4 走 `MiBlurCompat.setOutlineRoundRect(view, radius, advanced)`；
     * OS3 官方等价物是 `Util.setRoundRect(view, radius)`（内部同样是
     * `setClipToOutline(true)` + `ViewOutlineProvider`），方法缺失时逐级回退。
     */
    fun applyOutline(view: View): Int {
        val radius = invokeRequired(RES, "getBgRadius", context) as? Int
            ?: error("MiuiVolumeDialogRes.getBgRadius returned non-Int")
        val outlined = invokeRequired(
            MI_BLUR_COMPAT,
            "setOutlineRoundRect",
            view,
            radius.toFloat(),
            isAdvancedMaterialEffective(),
            quiet = true,
        )
        if (outlined === FAILED) {
            val rounded = invokeRequired(
                UTIL,
                "setRoundRect",
                view,
                radius.toFloat(),
                quiet = true,
            )
            check(rounded !== FAILED) {
                "Official expanded outline unavailable: neither MiBlurCompat.setOutlineRoundRect nor Util.setRoundRect"
            }
        }
        view.clipToOutline = true
        return radius
    }

    fun clear(view: View) {
        // OS4 的 setMiBgBlur(View, I, Z) 与 OS3 的 setMiBgBlur(View, I) 签名不同，
        // parametersMatch 保证各自只命中本系统的重载，逐个软尝试即可。
        invokeOptional(UTIL, "setMiBgBlur", view, 0, false)
        invokeOptional(UTIL, "setMiBgBlur", view, 0)
        // THEME_BLUR 模式把 view blur mode 设为 1，关闭时归零（OS3/OS4 均有此 API）。
        invokeOptional(MI_BLUR_COMPAT, "setMiViewBlurModeCompat", view, 0)
        invokeOptional(UTIL, "setViewBlurForS", view, 0)
        view.background = null
    }

    /** OS4 `Util.isAdvancedMaterialEffective`；OS3 无此方法，按 false 处理。 */
    private fun isAdvancedMaterialEffective(): Boolean =
        invokeRequired(UTIL, "isAdvancedMaterialEffective", context, quiet = true) as? Boolean ?: false

    /** OS3 `MiBlurCompat.getBackgroundBlurOpenedInDefaultTheme`；OS4 无此方法，按 false 处理。 */
    private fun themeBlurOpened(): Boolean =
        invokeRequired(MI_BLUR_COMPAT, "getBackgroundBlurOpenedInDefaultTheme", context, quiet = true)
            as? Boolean ?: false

    private fun isLowEndDevice(): Boolean =
        invokeRequired(BLUR_UTILS, "isLowEndDevice") as? Boolean
            ?: error("BlurUtils.isLowEndDevice returned non-Boolean")

    private fun isDefaultPluginTheme(): Boolean {
        val themeClass = loadClass(THEME_UTILS) ?: error("ThemeUtils class unavailable")
        val instance = themeClass.getField("INSTANCE").get(null)
        return invokeRequired(themeClass, instance, "getDefaultPluginTheme") as? Boolean
            ?: error("ThemeUtils.getDefaultPluginTheme returned non-Boolean")
    }

    /** OS4 官方 advanced material 分支，MiBackgroundStyle 缺失时返回 false 落到后续分支。 */
    private fun applyAdvanced(view: View): Boolean {
        val blandColor = invokeRequired(RES, "getBgBlandColor", true).takeUnless { it === FAILED }
            ?: return false
        val styleClass = loadClass(STYLE) ?: return false
        val instance = runCatching { styleClass.getField("INSTANCE").get(null) }
            .onFailure { log(Log.ERROR, TAG, "Unable to resolve MiBackgroundStyle.INSTANCE", it) }
            .getOrNull() ?: return false
        val glassToken = invokeRequired(styleClass, instance, "getVOLUMPANEL_EXPAND_GLASS_TOKEN")
            .takeUnless { it === FAILED } ?: return false
        val styled =
            invokeRequired(UTIL, "setMiViewBackgroundStyle", view, 1, blandColor, glassToken)
        if (styled === FAILED) return false
        val radius = invokeRequired(RES, "getBlandBlurRadius", context) as? Int ?: return false
        return invokeRequired(UTIL, "setMiBgBlur", view, radius, true) !== FAILED
    }

    /**
     * OS3 官方主题模糊分支：
     * `Util.setMiViewBlurAndBlendColor(view, 1, MiuiVolumeDialogRes.getBgBlandColor(true))`。
     */
    private fun applyThemeBlur(view: View): Boolean {
        val blandColor = invokeRequired(RES, "getBgBlandColor", true).takeUnless { it === FAILED }
            ?: return false
        return invokeRequired(UTIL, "setMiViewBlurAndBlendColor", view, 1, blandColor) !== FAILED
    }

    private fun applyBlurForS(view: View): Boolean {
        val radius = invokeRequired(RES, "getBgRadius", context) as? Int ?: return false
        return invokeOptional(UTIL, "setViewBlurForS", view, radius) !== FAILED
    }

    private fun expandedBackground(): Drawable {
        val resId = invokeRequired(RES, "getBgRes", true) as? Int
            ?: error("MiuiVolumeDialogRes.getBgRes returned non-Int")
        require(resId != 0) { "MiuiVolumeDialogRes.getBgRes returned zero" }
        return context.resources.getDrawable(resId, context.theme)
            ?: error("Official expanded background drawable is unavailable")
    }

    private fun invokeRequired(
        className: String,
        methodName: String,
        vararg args: Any?,
        quiet: Boolean = false,
    ): Any? {
        val clazz = loadClass(className, quiet) ?: return FAILED
        return invokeRequired(clazz, null, methodName, *args, quiet = quiet)
    }

    private fun invokeRequired(
        clazz: Class<*>,
        target: Any?,
        methodName: String,
        vararg args: Any?,
        quiet: Boolean = false,
    ): Any? {
        val method = (clazz.methods.asSequence() + clazz.declaredMethods.asSequence()).firstOrNull {
            it.name == methodName && parametersMatch(it.parameterTypes, args)
        } ?: run {
            // quiet 用于能力探测：另一代插件本来就没有这个方法，缺失是预期而非错误。
            if (!quiet) {
                log(Log.ERROR, TAG, "Official material method missing: ${clazz.name}.$methodName", null)
            }
            return FAILED
        }
        method.isAccessible = true
        return try {
            method.invoke(target, *args)
        } catch (error: InvocationTargetException) {
            log(
                Log.ERROR,
                TAG,
                "Official material call failed: ${clazz.name}.$methodName",
                error.cause ?: error
            )
            FAILED
        } catch (error: ReflectiveOperationException) {
            log(Log.ERROR, TAG, "Official material call failed: ${clazz.name}.$methodName", error)
            FAILED
        } catch (error: IllegalArgumentException) {
            log(
                Log.ERROR,
                TAG,
                "Official material arguments rejected: ${clazz.name}.$methodName",
                error
            )
            FAILED
        }
    }

    private fun invokeOptional(className: String, methodName: String, vararg args: Any?): Any? {
        val clazz = try {
            classLoader.loadClass(className)
        } catch (_: ClassNotFoundException) {
            return FAILED
        }
        val method = (clazz.methods.asSequence() + clazz.declaredMethods.asSequence()).firstOrNull {
            it.name == methodName && parametersMatch(it.parameterTypes, args)
        } ?: return FAILED
        method.isAccessible = true
        return try {
            method.invoke(null, *args)
        } catch (error: Throwable) {
            log(
                Log.WARN,
                TAG,
                "Optional official material call failed: ${clazz.name}.$methodName",
                error
            )
            FAILED
        }
    }

    private fun loadClass(name: String, quiet: Boolean = false): Class<*>? = try {
        classLoader.loadClass(name)
    } catch (error: ClassNotFoundException) {
        if (!quiet) {
            log(Log.ERROR, TAG, "Official material class missing: $name", error)
        }
        null
    }

    private fun parametersMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (types.size != args.size) return false
        return types.indices.all { index ->
            val value = args[index] ?: return@all !types[index].isPrimitive
            types[index].isInstance(value) || when (types[index]) {
                java.lang.Boolean.TYPE -> value is Boolean
                java.lang.Integer.TYPE -> value is Int
                java.lang.Float.TYPE -> value is Float
                else -> false
            }
        }
    }

    enum class Mode { ADVANCED, THEME_BLUR, BLUR_FOR_S, STATIC }

    private companion object {
        const val TAG = "SoundMan.ExpandedMaterial"
        const val UTIL = "com.android.systemui.miui.volume.Util"
        const val RES = "com.android.systemui.miui.volume.MiuiVolumeDialogRes"
        const val STYLE = "miui.systemui.util.MiBackgroundStyle"
        const val MI_BLUR_COMPAT = "miui.systemui.util.MiBlurCompat"
        const val BLUR_UTILS = "miui.systemui.util.BlurUtils"
        const val THEME_UTILS = "miui.systemui.util.ThemeUtils"
        val FAILED = Any()
    }
}

enum class OfficialExpandedMaterialMode { ADVANCED, THEME_BLUR, BLUR_FOR_S, STATIC }

/**
 * 官方 updateExpandBgState 判定树的统一投影：
 *
 * - `advancedMaterialEffective`（OS4 首查）为真 → ADVANCED；
 * - `themeBlurOpened`（OS3 首查）为真 → THEME_BLUR；
 * - 两者皆假（或对应探测 API 缺失）时，S 版 blur 需要非低端机且默认插件主题；
 * - 其余情况落静态背景。
 *
 * 两代插件各自缺失对方的首查 API（软探测恒 false），因此同一棵树在两代上
 * 命中的分支恰好等于各自官方代码的分支。
 */
object OfficialExpandedMaterialPolicy {
    fun choose(
        advancedMaterialEffective: Boolean,
        themeBlurOpened: Boolean,
        lowEndDevice: Boolean,
        defaultPluginTheme: Boolean,
    ): OfficialExpandedMaterialMode = when {
        advancedMaterialEffective -> OfficialExpandedMaterialMode.ADVANCED
        themeBlurOpened -> OfficialExpandedMaterialMode.THEME_BLUR
        !lowEndDevice && defaultPluginTheme -> OfficialExpandedMaterialMode.BLUR_FOR_S
        else -> OfficialExpandedMaterialMode.STATIC
    }
}
