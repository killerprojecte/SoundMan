package hk.uwu.soundman.hook.scopes.systemui.hidden

import android.content.Context
import android.util.Log
import android.view.View
import com.highcapable.kavaref.extension.makeAccessible
import com.highcapable.kavaref.extension.toClassOrNull
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 给 SoundMan 入口套上和折叠态音量条同一套 live MiBlur。
 *
 * 动机：官方圆钮的毛玻璃不是 `o3_miui_volume_ringer_bg_blur` 那张静态图。
 * 新系统折叠态音量条走 `Util.isAdvancedMaterialEffective` +
 * `MiBlurCompat.setOutlineRoundRect` + `Util.setMiViewBackgroundStyle`；
 * 老系统仍是 `Util.setRoundRect` + `Util.setMiViewBlurAndBlendColor`。
 * 新路径报错或缺失时回退老路径，新系统上老路径仍然可用。
 *
 * 反射理由：`MiBlurCompat` / `Util` / `RingerButtonRes` / `MiuiColorBlendToken` /
 * `MiBackgroundStyle` 只在 `miui.systemui.plugin` ClassLoader 里，编译 classpath
 * 没有这些类，也没有可链接的公开 SDK。
 */
class OfficialRingerBlur(
    private val pluginClassLoader: ClassLoader,
    private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
) {
    private val classes = ConcurrentHashMap<String, Class<*>>()
    private val methods = ConcurrentHashMap<MethodKey, Method>()
    private var usedNewMaterialChrome = false

    /**
     * 当前主题是否走 live 背景模糊。
     *
     * 先探新系统 `isAdvancedMaterialEffective` /
     * `getBackgroundMaterialOpenedInDefaultTheme`，没有再回退老
     * `getBackgroundBlurOpenedInDefaultTheme`。
     *
     * `null` 表示探测 API 不存在或调用失败。
     */
    fun themeBlurOpened(context: Context): Boolean? {
        val types = arrayOf<Class<*>>(Context::class.java)
        val args = arrayOf<Any?>(context)
        return invokeStatic<Boolean>(
            VOLUME_UTIL,
            METHOD_ADVANCED_MATERIAL,
            types,
            args,
            quiet = true,
        ) ?: invokeStatic<Boolean>(
            MI_BLUR_COMPAT,
            METHOD_THEME_MATERIAL_OPENED,
            types,
            args,
            quiet = true,
        ) ?: invokeStatic(
            MI_BLUR_COMPAT,
            METHOD_THEME_BLUR_OPENED,
            types,
            args,
        )
    }

    /**
     * 最近一次 [applyCollapsedChrome] 是否走了新系统 material 路径。
     *
     * 新路径把 blur 直接打在 chrome 上，不要再叠 backdrop / 静态 blur 图。
     */
    fun usedNewMaterialChrome(): Boolean = usedNewMaterialChrome

    /**
     * 按折叠态音量条 **chrome** 套 live blur。
     *
     * 先走新系统 `setOutlineRoundRect` + `setMiViewBackgroundStyle`，
     * 失败再回退 `setRoundRect` + `setMiViewBlurAndBlendColor`。
     * 不要再叠 `setMiBgBlur` / window blur，叠上去会比官方更深。
     *
     * @param view 圆钮 chrome
     * @param radiusPx 官方 `o3_miui_ringer_btn_radius`
     * @return blend 调用成功
     */
    fun applyCollapsedChrome(view: View, radiusPx: Int): Boolean {
        usedNewMaterialChrome = false
        val token = collapsedOffBlendToken(view.context)
        if (token == null) {
            log(Log.ERROR, TAG, "Official ringer blend token was not resolved", null)
            return false
        }
        if (applyNewMaterialChrome(view, radiusPx, token)) {
            usedNewMaterialChrome = true
            return true
        }
        log(
            Log.INFO,
            TAG,
            "New volume-column material chrome unavailable; falling back to legacy MiBlur",
            null
        )
        val applied = applyLegacyChrome(view, radiusPx, token)
        if (!applied) {
            log(Log.ERROR, TAG, "Official setMiViewBlurAndBlendColor was not applied", null)
        }
        return applied
    }

    /**
     * 创建官方 `bg_blur` 同款 Backdrop 层。
     *
     * 新系统折叠态音量条不再用 backdrop；`setMiViewBackgroundStyle` 在就跳过。
     * 老路径：`com.miui.blur.sdk.backdrop.a(Context)` + `setBlurEnabled(true)`。
     */
    fun createCollapsedBlurLayer(context: Context, radiusPx: Int): View? {
        if (hasNewMaterialChrome()) return null
        val clazz = loadClass(BACKDROP_BLUR_VIEW) ?: return null
        val created = try {
            clazz.getConstructor(Context::class.java).newInstance(context)
        } catch (error: ReflectiveOperationException) {
            log(Log.ERROR, TAG, "Unable to construct $BACKDROP_BLUR_VIEW", error)
            null
        } ?: return null
        val view = created as? View
        if (view == null) {
            log(Log.ERROR, TAG, "$BACKDROP_BLUR_VIEW is not a View: ${created.javaClass.name}", null)
            return null
        }
        val enabled = invokeOn(clazz, view, METHOD_SET_BLUR_ENABLED, arrayOf(true))
        if (enabled === FAILED) return null
        invokeOn(clazz, view, METHOD_SET_CORNER_RADIUS, arrayOf(radiusPx.toFloat()))
        return view
    }

    private fun applyNewMaterialChrome(view: View, radiusPx: Int, token: Any): Boolean {
        val glass = collapsedGlassToken() ?: return false
        val radius = radiusPx.toFloat()
        val outlined = invokeClass(
            MI_BLUR_COMPAT,
            METHOD_SET_OUTLINE_ROUND_RECT,
            arrayOf(view, radius, true),
            quiet = true,
        ) != FAILED || invokeClass(
            MI_BLUR_COMPAT,
            METHOD_SET_BLUR_OUTLINE_ROUND_RECT,
            arrayOf(view, radius),
            quiet = true,
        ) != FAILED
        if (!outlined) return false
        return invokeClass(
            VOLUME_UTIL,
            METHOD_SET_MI_VIEW_BACKGROUND_STYLE,
            arrayOf(view, 1, token, glass),
            quiet = true,
        ) != FAILED
    }

    private fun applyLegacyChrome(view: View, radiusPx: Int, token: Any): Boolean {
        invokeInstanceOrStatic(VOLUME_UTIL, METHOD_SET_ROUND_RECT, view, radiusPx.toFloat())
        return invokeInstanceOrStatic(
            VOLUME_UTIL,
            METHOD_SET_MI_VIEW_BLUR_AND_BLEND,
            view,
            1,
            token,
        )
    }

    private fun collapsedOffBlendToken(context: Context): Any? {
        val booleanType = Boolean::class.javaPrimitiveType!!
        val isBionics = invokeStatic<Boolean>(
            VOLUME_UTIL,
            METHOD_BIONICS_MATERIAL,
            arrayOf<Class<*>>(Context::class.java),
            arrayOf(context),
            quiet = true,
        ) == true
        invokeStatic<Any>(
            RINGER_BUTTON_RES,
            METHOD_GET_BUTTON_BG_BLEND,
            arrayOf<Class<*>>(booleanType, booleanType, booleanType, booleanType),
            arrayOf(false, true, false, isBionics),
            quiet = true,
        )?.let { return it }
        invokeStatic<Any>(
            RINGER_BUTTON_RES,
            METHOD_GET_BUTTON_BG_BLEND,
            arrayOf<Class<*>>(booleanType, booleanType, booleanType),
            arrayOf(false, true, false),
            quiet = true,
        )?.let { return it }
        val holder = loadClass(MIUI_COLOR_BLEND_TOKEN) ?: return null
        val instance = runCatching {
            holder.getField(FIELD_INSTANCE).get(null)
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Unable to read $MIUI_COLOR_BLEND_TOKEN.$FIELD_INSTANCE", error)
        }.getOrNull() ?: return null
        val result = invokeOn(holder, instance, METHOD_GET_RINGER_BG_OFF, emptyArray())
        return if (result === FAILED) null else result
    }

    private fun collapsedGlassToken(): Any? {
        val holder = loadClass(MI_BACKGROUND_STYLE, quiet = true) ?: return null
        val instance = runCatching {
            holder.getField(FIELD_INSTANCE).get(null)
        }.getOrNull() ?: return null
        val result = invokeOn(
            holder,
            instance,
            METHOD_GET_COLLAPSED_CLOSED_GLASS,
            emptyArray(),
            quiet = true,
        )
        return if (result == null || result === FAILED) null else result
    }

    private fun hasNewMaterialChrome(): Boolean {
        val clazz = loadClass(VOLUME_UTIL, quiet = true) ?: return false
        return clazz.methods.any { method ->
            method.name == METHOD_SET_MI_VIEW_BACKGROUND_STYLE && method.parameterTypes.size == 4
        } || clazz.declaredMethods.any { method ->
            method.name == METHOD_SET_MI_VIEW_BACKGROUND_STYLE && method.parameterTypes.size == 4
        }
    }

    private fun invokeInstanceOrStatic(className: String, methodName: String, vararg args: Any?): Boolean {
        val clazz = loadClass(className) ?: return false
        return invokeOn(clazz, null, methodName, args) != FAILED
    }

    private fun invokeClass(
        className: String,
        methodName: String,
        args: Array<out Any?>,
        quiet: Boolean,
    ): Any? {
        val clazz = loadClass(className, quiet) ?: return FAILED
        return invokeOn(clazz, null, methodName, args, quiet)
    }

    private fun <T> invokeStatic(
        className: String,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any?>,
        quiet: Boolean = false,
    ): T? {
        val clazz = loadClass(className, quiet) ?: return null
        val result = invokeOn(clazz, null, methodName, args, quiet) ?: return null
        if (result === FAILED) return null
        @Suppress("UNCHECKED_CAST")
        return result as? T
    }

    private fun invokeOn(
        clazz: Class<*>,
        target: Any?,
        methodName: String,
        args: Array<out Any?>,
        quiet: Boolean = false,
    ): Any? {
        val method = resolveMethod(clazz, methodName, args, quiet) ?: return FAILED
        return try {
            method.invoke(target, *args)
        } catch (error: InvocationTargetException) {
            if (!quiet) {
                log(
                    Log.ERROR,
                    TAG,
                    "Official blur call failed: ${clazz.name}.$methodName",
                    error.cause ?: error
                )
            }
            FAILED
        } catch (error: ReflectiveOperationException) {
            if (!quiet) {
                log(Log.ERROR, TAG, "Official blur call failed: ${clazz.name}.$methodName", error)
            }
            FAILED
        } catch (error: IllegalArgumentException) {
            if (!quiet) {
                log(
                    Log.ERROR,
                    TAG,
                    "Official blur arguments rejected: ${clazz.name}.$methodName",
                    error
                )
            }
            FAILED
        }
    }

    private fun resolveMethod(
        clazz: Class<*>,
        methodName: String,
        args: Array<out Any?>,
        quiet: Boolean = false,
    ): Method? {
        val key = MethodKey(clazz, methodName, args.size)
        methods[key]?.let { return it }
        val match = clazz.methods.firstOrNull { method ->
            method.name == methodName && parametersMatch(method.parameterTypes, args)
        } ?: clazz.declaredMethods.firstOrNull { method ->
            method.name == methodName && parametersMatch(method.parameterTypes, args)
        }
        if (match == null) {
            if (!quiet) {
                log(
                    Log.ERROR,
                    TAG,
                    "Official blur method missing: ${clazz.name}.$methodName args=${args.size}",
                    null,
                )
            }
            return null
        }
        match.makeAccessible()
        methods[key] = match
        return match
    }

    private fun parametersMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (types.size != args.size) return false
        types.indices.forEach { index ->
            val arg = args[index] ?: return@forEach
            val type = types[index]
            if (type.isInstance(arg)) return@forEach
            if (type.isPrimitive && boxedMatches(type, arg)) return@forEach
            return false
        }
        return true
    }

    private fun boxedMatches(primitive: Class<*>, arg: Any): Boolean {
        return when (primitive) {
            java.lang.Boolean.TYPE -> arg is Boolean
            java.lang.Integer.TYPE -> arg is Int
            java.lang.Float.TYPE -> arg is Float
            else -> false
        }
    }

    private fun loadClass(name: String, quiet: Boolean = false): Class<*>? {
        classes[name]?.let { return it }
        val clazz = name.toClassOrNull(pluginClassLoader)
        if (clazz == null && !quiet) {
            log(Log.ERROR, TAG, "Official blur class missing: $name", null)
        }
        if (clazz != null) classes[name] = clazz
        return clazz
    }

    private data class MethodKey(
        val clazz: Class<*>,
        val name: String,
        val arity: Int,
    )

    private companion object {
        const val TAG = "SoundMan.SystemUi"
        val FAILED = Any()

        const val MI_BLUR_COMPAT = "miui.systemui.util.MiBlurCompat"
        const val MI_BACKGROUND_STYLE = "miui.systemui.util.MiBackgroundStyle"
        const val VOLUME_UTIL = "com.android.systemui.miui.volume.Util"
        const val RINGER_BUTTON_RES = "com.android.systemui.miui.volume.RingerButtonRes"
        const val MIUI_COLOR_BLEND_TOKEN = "miui.systemui.util.MiuiColorBlendToken"
        const val BACKDROP_BLUR_VIEW = "com.miui.blur.sdk.backdrop.a"
        const val FIELD_INSTANCE = "INSTANCE"
        const val METHOD_THEME_BLUR_OPENED = "getBackgroundBlurOpenedInDefaultTheme"
        const val METHOD_THEME_MATERIAL_OPENED = "getBackgroundMaterialOpenedInDefaultTheme"
        const val METHOD_ADVANCED_MATERIAL = "isAdvancedMaterialEffective"
        const val METHOD_BIONICS_MATERIAL = "isBionicsAdvancedMaterialEnabled"
        const val METHOD_SET_ROUND_RECT = "setRoundRect"
        const val METHOD_SET_OUTLINE_ROUND_RECT = "setOutlineRoundRect"
        const val METHOD_SET_BLUR_OUTLINE_ROUND_RECT = "setBlurOutlineRoundRect"
        const val METHOD_SET_MI_VIEW_BACKGROUND_STYLE = "setMiViewBackgroundStyle"
        const val METHOD_SET_MI_VIEW_BLUR_AND_BLEND = "setMiViewBlurAndBlendColor"
        const val METHOD_GET_BUTTON_BG_BLEND = "getButtonBgBlendColor"
        const val METHOD_GET_RINGER_BG_OFF = "getRINGER_BG_OFF"
        const val METHOD_GET_COLLAPSED_CLOSED_GLASS = "getVOLUMPANEL_COLLAPSED_CLOSED_GLASS_TOKEN"
        const val METHOD_SET_BLUR_ENABLED = "setBlurEnabled"
        const val METHOD_SET_CORNER_RADIUS = "setCornerRadius"
    }
}
