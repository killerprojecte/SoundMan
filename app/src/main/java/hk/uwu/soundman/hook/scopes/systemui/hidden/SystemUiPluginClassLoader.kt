package hk.uwu.soundman.hook.scopes.systemui.hidden

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.resolver.FieldResolver
import com.highcapable.kavaref.resolver.MethodResolver
import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiPluginHookTargets.FIELD_CLASS_LOADER_FACTORY
import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiPluginHookTargets.FIELD_PLUGIN_FACTORY
import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiPluginHookTargets.GET_PACKAGE
import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiPluginHookTargets.METHOD_GET
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap

/**
 * 从 SystemUI `PluginInstance` 取出插件包名和插件 ClassLoader。
 *
 * 动机：HyperOS 音量侧栏类跑在 SystemUI 进程里，由插件 ClassLoader 加载；
 * `PluginInstance` / `PluginFactory` 不在编译 classpath，也没有可编译的公开 API，
 * 因此必须按 factory 链做 hidden 访问。提取器本身缺成员就立即失败，
 * 由 hooker 捕获并打日志，避免异常打穿 SystemUI。
 *
 * 反射理由：factory 具体类型会随 ROM / 插件 reload 变化，因此字段和方法都按 runtime class 查找并缓存。
 */
class SystemUiPluginClassLoader {
    private val fields = ConcurrentHashMap<MemberKey, FieldResolver<Any>>()
    private val methods = ConcurrentHashMap<MemberKey, MethodResolver<Any>>()

    /**
     * 调用 `getPackage()` 读取插件包名。
     *
     * @param pluginInstance `PluginInstance` 运行时对象
     * @return `getPackage()` 的返回值
     */
    fun packageName(pluginInstance: Any): String {
        val instanceClass = pluginInstance.javaClass
        val result = runCatching {
            invoke(methodOf(instanceClass, GET_PACKAGE), pluginInstance)
        }.getOrNull() ?: invoke(methodOf(instanceClass, "getPackageName"), pluginInstance)
        return result as? String
            ?: throw IllegalStateException(
                "Method $GET_PACKAGE() on ${instanceClass.name} returned non-String: " +
                    "${result?.javaClass?.name}",
            )
    }

    /**
     * 沿 `mPluginFactory` → `mClassLoaderFactory` → `get()` 取出插件 ClassLoader。
     *
     * @param pluginInstance `PluginInstance` 运行时对象
     * @return factory `get()` 返回的 ClassLoader
     */
    fun classLoader(pluginInstance: Any): ClassLoader {
        val factory = runCatching {
            fieldValue(pluginInstance, FIELD_PLUGIN_FACTORY)
                ?: throw IllegalStateException(
                    "Field $FIELD_PLUGIN_FACTORY on ${pluginInstance.javaClass.name} is null",
                )
        }.getOrNull()
        if (factory == null) {
            val pluginData =
                fieldValue(pluginInstance, "pluginData") ?: throw IllegalStateException(
                    "Field pluginData on ${pluginInstance.javaClass.name} is null",
                )
            val contextWrapper = fieldValue(pluginData, "context") ?: throw IllegalStateException(
                "Field context on ${pluginData.javaClass.name} is null",
            )
            val result =
                invoke(methodOf(contextWrapper.javaClass, "getClassLoader"), contextWrapper)
            return result as? ClassLoader ?: throw IllegalStateException(
                "Method getClassLoader() on ${contextWrapper.javaClass.name} returned non-ClassLoader: " +
                        "${result?.javaClass?.name}",
            )
        } else {
            val classLoaderFactory = fieldValue(factory, FIELD_CLASS_LOADER_FACTORY)
                ?: throw IllegalStateException(
                    "Field $FIELD_CLASS_LOADER_FACTORY on ${factory.javaClass.name} is null",
                )
            val result =
                invoke(methodOf(classLoaderFactory.javaClass, METHOD_GET), classLoaderFactory)
            return result as? ClassLoader
                ?: throw IllegalStateException(
                    "Method $METHOD_GET() on ${classLoaderFactory.javaClass.name} returned non-ClassLoader: " +
                            "${result?.javaClass?.name}",
                )
        }
    }

    private fun fieldValue(instance: Any, name: String): Any? =
        fieldOf(instance.javaClass, name).copy().of(instance).getQuietly()

    private fun fieldOf(clazz: Class<*>, name: String): FieldResolver<Any> =
        fields.getOrPut(MemberKey(clazz, name)) { resolveDeclaredField(clazz, name) }

    private fun methodOf(clazz: Class<*>, name: String): MethodResolver<Any> =
        methods.getOrPut(MemberKey(clazz, name)) { resolveNoArgMethod(clazz, name) }

    private fun invoke(method: MethodResolver<Any>, instance: Any): Any? = try {
        method.copy().of(instance).invoke()
    } catch (error: InvocationTargetException) {
        throw error.targetException ?: error
    }

    private data class MemberKey(
        val clazz: Class<*>,
        val name: String,
    )

    private companion object {
        fun resolveDeclaredField(clazz: Class<*>, name: String): FieldResolver<Any> {
            @Suppress("UNCHECKED_CAST")
            val resolved = (clazz as Class<Any>).resolve().optional(silent = true)
            return resolved.firstFieldOrNull { name(name) }
                ?: error("Missing field $name on ${clazz.name}")
        }

        fun resolveNoArgMethod(clazz: Class<*>, name: String): MethodResolver<Any> {
            @Suppress("UNCHECKED_CAST")
            val resolved = (clazz as Class<Any>).resolve().optional(silent = true)
            return resolved.firstMethodOrNull {
                name(name)
                emptyParameters()
                superclass()
            } ?: error("Missing method $name() on ${clazz.name}")
        }
    }
}
