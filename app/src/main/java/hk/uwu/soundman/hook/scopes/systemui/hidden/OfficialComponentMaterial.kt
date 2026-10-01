package hk.uwu.soundman.hook.scopes.systemui.hidden

import android.content.Context
import android.graphics.Outline
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import com.highcapable.kavaref.extension.makeAccessible
import hk.uwu.soundman.hook.scopes.systemui.runtime.ComponentMaterialSpec
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 给 SoundMan 入口套上和音量条 / 控制中心组件同一套官方 MIUIX 高光材质。
 *
 * 官方链路（HyperLight 给音量条用的也是这一条）：
 * `MaterialToken$Builder(30, 0f, "light"|"dark")` → `setColorBlend(ColorBlendToken.X)`
 * → `build()` → `MaterialDayNightToken(light, dark)` → `MaterialDayNightConfig.create(...)`
 * → `token.get(night)` → `HyperMaterialUtils.applyViewMaterial(view, token, radius, clip)`。
 *
 * 反射理由：这些类只在 miui.systemui.plugin（或宿主）ClassLoader 里，编译 classpath
 * 没有它们，也没有可链接的公开 SDK。签名在不同 MIUIX 版本上会有出入，因此全部按
 * 名字 + 形参类型软匹配，匹配不上就返回 false 让调用方降级。
 */
class OfficialComponentMaterial(
    private val pluginClassLoader: ClassLoader,
    private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
) {
    private val classes = ConcurrentHashMap<String, Class<*>>()

    @Volatile
    private var hostClassLoader: ClassLoader? = null

    /** 官方材质链路是否可用；不可用时应降级到 ringer chrome / 液态玻璃。 */
    fun available(context: Context): Boolean {
        hostClassLoader = context.classLoader
        return load(ComponentMaterialSpec.MATERIAL_UTILS) != null
    }

    /**
     * 套用官方组件材质。
     *
     * 官方材质自带完整背景，调用方不要再叠静态 blur 图或液态玻璃。
     *
     * @param view 入口 chrome
     * @param radiusPx 官方圆钮半径
     * @param night 当前是否深色模式
     * @return 材质调用成功
     */
    fun apply(view: View, radiusPx: Int, night: Boolean): Boolean {
        hostClassLoader = view.context.classLoader
        val token = dayNightToken(night) ?: return false
        val utils = load(ComponentMaterialSpec.MATERIAL_UTILS) ?: return false
        val method = resolveApplyMaterial(utils) ?: return false
        val args = buildApplyArgs(method, view, token, radiusPx) ?: return false
        if (invoke(method, null, args, quiet = true) === FAILED) {
            log(Log.WARN, TAG, "Official component material rejected our arguments", null)
            return false
        }
        applyOutline(view, radiusPx.toFloat())
        return true
    }

    private fun dayNightToken(night: Boolean): Any? {
        val light = buildToken(night = false) ?: return null
        val dark = buildToken(night = true) ?: return null
        val holder = load(ComponentMaterialSpec.DAY_NIGHT_TOKEN) ?: return light
        val paired = construct(holder, arrayOf(light, dark)) ?: return light
        val configClass = load(ComponentMaterialSpec.DAY_NIGHT_CONFIG)
        val config = configClass?.methods?.firstOrNull { candidate ->
            candidate.name == METHOD_CREATE && candidate.parameterTypes.size == 1
        }?.let { creator ->
            creator.makeAccessible()
            invoke(creator, null, arrayOf(paired), quiet = true)
        }
        val source = if (config == null || config === FAILED) paired else config
        val resolved = source.javaClass.methods.firstOrNull { candidate ->
            candidate.name == METHOD_GET && candidate.parameterTypes.size == 1 &&
                isBooleanLike(candidate.parameterTypes[0])
        }?.let { getter ->
            getter.makeAccessible()
            invoke(getter, source, arrayOf(night), quiet = true)
        }
        return when {
            resolved == null || resolved === FAILED -> source
            else -> resolved
        }
    }

    private fun buildToken(night: Boolean): Any? {
        val builderClass = load(ComponentMaterialSpec.MATERIAL_TOKEN_BUILDER) ?: return null
        val kind = if (night) ComponentMaterialSpec.TOKEN_KIND_DARK else ComponentMaterialSpec.TOKEN_KIND_LIGHT
        val builder = construct(
            builderClass,
            arrayOf<Any?>(
                ComponentMaterialSpec.TOKEN_THICKNESS,
                ComponentMaterialSpec.TOKEN_ROUND_COMPENSATION,
                kind,
            ),
        ) ?: construct(builderClass, arrayOf<Any?>(kind)) ?: construct(builderClass, emptyArray())
        if (builder == null) {
            log(Log.WARN, TAG, "Official material builder is unavailable", null)
            return null
        }
        val blend = colorBlendToken(night)
        if (blend != null) {
            builder.javaClass.methods.firstOrNull { candidate ->
                candidate.name == METHOD_SET_COLOR_BLEND && candidate.parameterTypes.size == 1
            }?.let { setter ->
                setter.makeAccessible()
                invoke(setter, builder, arrayOf(blend), quiet = true)
            }
        }
        return builder.javaClass.methods.firstOrNull { candidate ->
            candidate.name == METHOD_BUILD && candidate.parameterTypes.isEmpty()
        }?.let { build ->
            build.makeAccessible()
            invoke(build, builder, emptyArray(), quiet = true)
        }?.takeIf { it !== FAILED }
    }

    private fun colorBlendToken(night: Boolean): Any? {
        val tokenClass = load(ComponentMaterialSpec.COLOR_BLEND_TOKEN) ?: return null
        for (name in ComponentMaterialSpec.blendFields(SCENE, night)) {
            val value = runCatching {
                tokenClass.getDeclaredField(name).apply { isAccessible = true }.get(null)
            }.getOrNull()
            if (value != null) return value
        }
        log(Log.DEBUG, TAG, "Official color blend token missing; building material without it", null)
        return null
    }

    private fun resolveApplyMaterial(utils: Class<*>): Method? {
        val match = (utils.methods.asSequence() + utils.declaredMethods.asSequence()).firstOrNull { candidate ->
            candidate.name == METHOD_APPLY_VIEW_MATERIAL &&
                candidate.parameterTypes.size in MIN_APPLY_ARGS..MAX_APPLY_ARGS &&
                candidate.parameterTypes[0] == View::class.java
        }
        if (match == null) {
            log(
                Log.WARN,
                TAG,
                "Official ${ComponentMaterialSpec.MATERIAL_UTILS}.$METHOD_APPLY_VIEW_MATERIAL is missing",
                null,
            )
            return null
        }
        return match.apply { makeAccessible() }
    }

    /**
     * 按形参类型填参：`View` 放 view、`float/int` 放半径、`boolean` 放 clip，
     * 其余第一个未占用槽位放 token。不同 MIUIX 版本的形参顺序由此解耦。
     */
    private fun buildApplyArgs(method: Method, view: View, token: Any, radiusPx: Int): Array<Any?>? {
        val types = method.parameterTypes
        val args = arrayOfNulls<Any?>(types.size)
        var tokenUsed = false
        for (index in types.indices) {
            when {
                types[index] == View::class.java -> args[index] = view
                isFloatLike(types[index]) -> args[index] = radiusPx.toFloat()
                isIntLike(types[index]) -> args[index] = radiusPx
                isBooleanLike(types[index]) -> args[index] = true
                !tokenUsed -> {
                    args[index] = token
                    tokenUsed = true
                }

                else -> args[index] = null
            }
        }
        if (!tokenUsed) {
            log(
                Log.WARN,
                TAG,
                "Official material signature has no token slot: ${method.toGenericString()}",
                null,
            )
            return null
        }
        return args
    }

    private fun applyOutline(view: View, radiusPx: Float) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                outline.setRoundRect(0, 0, target.width, target.height, radiusPx)
            }
        }
    }

    /**
     * 依次在插件 ClassLoader 与宿主 ClassLoader 里查找。
     *
     * MIUIX 通常随 miui.systemui.plugin 分发，但部分 ROM 也把它打进宿主，两处都试才能覆盖。
     */
    private fun load(name: String): Class<*>? {
        classes[name]?.let { return it }
        val host = hostClassLoader
        val resolved = runCatching { Class.forName(name, false, pluginClassLoader) }.getOrNull()
            ?: host?.let { runCatching { Class.forName(name, false, it) }.getOrNull() }
        if (resolved != null) classes[name] = resolved
        return resolved
    }

    private fun construct(clazz: Class<*>, args: Array<out Any?>): Any? {
        val constructor = clazz.declaredConstructors.firstOrNull { candidate ->
            candidate.parameterTypes.size == args.size && parametersMatch(candidate.parameterTypes, args)
        } ?: return null
        constructor.isAccessible = true
        return try {
            constructor.newInstance(*args)
        } catch (error: Throwable) {
            log(Log.DEBUG, TAG, "Official material construction failed: ${clazz.name}", error)
            null
        }
    }

    private fun invoke(method: Method, target: Any?, args: Array<out Any?>, quiet: Boolean): Any? {
        return try {
            method.invoke(target, *args)
        } catch (error: InvocationTargetException) {
            if (!quiet) {
                log(Log.ERROR, TAG, "Official material call failed: ${method.name}", error.cause ?: error)
            }
            FAILED
        } catch (error: Throwable) {
            if (!quiet) {
                log(Log.ERROR, TAG, "Official material call failed: ${method.name}", error)
            }
            FAILED
        }
    }

    private fun parametersMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (types.size != args.size) return false
        return types.indices.all { index ->
            val value = args[index] ?: return@all !types[index].isPrimitive
            val type = types[index]
            type.isInstance(value) || isFloatLike(type) && value is Float ||
                isIntLike(type) && value is Int || isBooleanLike(type) && value is Boolean
        }
    }

    private fun isFloatLike(type: Class<*>): Boolean =
        type == java.lang.Float.TYPE || type == Float::class.javaObjectType

    private fun isIntLike(type: Class<*>): Boolean =
        type == java.lang.Integer.TYPE || type == Int::class.javaObjectType

    private fun isBooleanLike(type: Class<*>): Boolean =
        type == java.lang.Boolean.TYPE || type == Boolean::class.javaObjectType

    private companion object {
        const val TAG = "SoundMan.ComponentMaterial"
        const val METHOD_APPLY_VIEW_MATERIAL = "applyViewMaterial"
        const val METHOD_SET_COLOR_BLEND = "setColorBlend"
        const val METHOD_BUILD = "build"
        const val METHOD_CREATE = "create"
        const val METHOD_GET = "get"
        const val MIN_APPLY_ARGS = 3
        const val MAX_APPLY_ARGS = 5

        /** 与音量条保持一致的场景：HyperLight 也是用这一个给 `mProgressView` 上材质。 */
        const val SCENE = ComponentMaterialSpec.SCENE_COMPONENTS
        val FAILED = Any()
    }
}
