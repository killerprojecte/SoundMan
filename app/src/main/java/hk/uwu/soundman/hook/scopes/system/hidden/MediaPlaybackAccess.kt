package hk.uwu.soundman.hook.scopes.system.hidden

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.resolver.MethodResolver
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap

/**
 * 读取一条播放配置上的 player 状态与 AudioAttributes。
 *
 * 动机：MiSound 用 `getPlayerState() == STARTED` 以及 usage / volumeControlStream 过滤媒体播放。
 * 旧探测走 `isActive()`，不能把这些字段塞进 [PlaybackConfigurationAccess] 的必需面。
 * `getPlayerState` / `getAudioAttributes` 缺失立即失败；attributes 上缺 usage / stream 同样失败。
 */
class MediaPlaybackAccess {
    private val resolvedConfigs = ConcurrentHashMap<Class<*>, ResolvedConfig>()
    private val resolvedAttributes = ConcurrentHashMap<Class<*>, ResolvedAttributes>()

    /**
     * 读取 `getPlayerState()`。
     *
     * 动机：STARTED=2 才算正在播放。缺方法不能猜 0。
     *
     * @param config 一条播放配置
     */
    fun playerState(config: Any): Int {
        val result = invoke(configAccess(config).playerState, config)
        return result as? Int
            ?: error("${config.javaClass.name}.getPlayerState returned non-Int: ${result?.javaClass?.name}")
    }

    /**
     * 读取 AudioAttributes.usage。
     *
     * 动机：USAGE_MEDIA=1 才进官方多应用音量。缺 attributes 不能猜。
     *
     * @param config 一条播放配置
     */
    fun usage(config: Any): Int = attributeInt(config, AttributeField.USAGE)

    /**
     * 读取 AudioAttributes.volumeControlStream。
     *
     * 动机：STREAM_MUSIC=3 时即使 usage 不是 MEDIA 也保留，对齐 MiSound。
     *
     * @param config 一条播放配置
     */
    fun volumeControlStream(config: Any): Int =
        attributeInt(config, AttributeField.VOLUME_CONTROL_STREAM)

    private fun attributeInt(config: Any, field: AttributeField): Int {
        val attributes = invoke(configAccess(config).audioAttributes, config)
            ?: error("${config.javaClass.name}.getAudioAttributes returned null")
        val method = when (field) {
            AttributeField.USAGE -> attributesAccess(attributes).usage
            AttributeField.VOLUME_CONTROL_STREAM -> attributesAccess(attributes).volumeControlStream
        }
        val result = invoke(method, attributes)
        return result as? Int
            ?: error("${attributes.javaClass.name}.${field.methodName} returned non-Int: ${result?.javaClass?.name}")
    }

    private fun configAccess(config: Any): ResolvedConfig =
        resolvedConfigs.getOrPut(config.javaClass) { ResolvedConfig.resolve(config.javaClass) }

    private fun attributesAccess(attributes: Any): ResolvedAttributes =
        resolvedAttributes.getOrPut(attributes.javaClass) { ResolvedAttributes.resolve(attributes.javaClass) }

    private data class ResolvedConfig(
        val playerState: MethodResolver<Any>,
        val audioAttributes: MethodResolver<Any>,
    ) {
        companion object {
            fun resolve(clazz: Class<*>): ResolvedConfig = ResolvedConfig(
                requiredMethod(clazz, METHOD_GET_PLAYER_STATE),
                requiredMethod(clazz, METHOD_GET_AUDIO_ATTRIBUTES),
            )
        }
    }

    private data class ResolvedAttributes(
        val usage: MethodResolver<Any>,
        val volumeControlStream: MethodResolver<Any>,
    ) {
        companion object {
            fun resolve(clazz: Class<*>): ResolvedAttributes = ResolvedAttributes(
                requiredMethod(clazz, METHOD_GET_USAGE),
                requiredMethod(clazz, METHOD_GET_VOLUME_CONTROL_STREAM),
            )
        }
    }

    private enum class AttributeField(val methodName: String) {
        USAGE(METHOD_GET_USAGE),
        VOLUME_CONTROL_STREAM(METHOD_GET_VOLUME_CONTROL_STREAM),
    }

    private companion object {
        const val METHOD_GET_PLAYER_STATE = "getPlayerState"
        const val METHOD_GET_AUDIO_ATTRIBUTES = "getAudioAttributes"
        const val METHOD_GET_USAGE = "getUsage"
        const val METHOD_GET_VOLUME_CONTROL_STREAM = "getVolumeControlStream"

        fun requiredMethod(clazz: Class<*>, name: String): MethodResolver<Any> =
            optionalMethod(clazz, name)
                ?: error("Missing method $name() on ${clazz.name}")

        fun optionalMethod(clazz: Class<*>, name: String): MethodResolver<Any>? {
            @Suppress("UNCHECKED_CAST")
            val resolved = (clazz as Class<Any>).resolve().optional(silent = true)
            return resolved.firstMethodOrNull {
                name(name)
                emptyParameters()
                superclass()
            }
        }

        fun invoke(method: MethodResolver<Any>, instance: Any): Any? = try {
            method.copy().of(instance).invoke()
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        }
    }
}
