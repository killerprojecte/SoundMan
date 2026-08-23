package hk.uwu.soundman.hook.scopes.system.hidden

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.resolver.MethodResolver
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap

/**
 * 读取一条播放配置上的 uid / 活跃状态 / 可选 player 身份。
 *
 * 动机：生产环境传入 `AudioPlaybackConfiguration`，单测传入 Java 假类。
 * 不能写死类名，也不能因为缺 `getPlayerInterfaceId` / `getPlayerProxy` 丢掉正在播放的应用。
 * `getClientUid` / `isActive` 缺失立即失败；可选方法缺失视为 ROM 未暴露，返回 null。
 */
class PlaybackConfigurationAccess {
    private val resolved = ConcurrentHashMap<Class<*>, ResolvedAccess>()

    /**
     * 读取客户端 uid。
     *
     * 动机：快照按 uid 列应用。缺 `getClientUid()` 必须失败，不能猜 0。
     *
     * @param config 一条播放配置
     */
    fun clientUid(config: Any): Int {
        val result = invoke(accessOf(config).clientUid, config)
        return result as? Int
            ?: error("${config.javaClass.name}.getClientUid returned non-Int: ${result?.javaClass?.name}")
    }

    /**
     * 读取该配置是否仍在播放。
     *
     * 动机：`getActivePlaybackConfigurations()` 仍可能包含非活跃项，必须显式看 `isActive()`。
     *
     * @param config 一条播放配置
     */
    fun isActive(config: Any): Boolean {
        val result = invoke(accessOf(config).isActive, config)
        return result as? Boolean
            ?: error("${config.javaClass.name}.isActive returned non-Boolean: ${result?.javaClass?.name}")
    }

    /**
     * 读取 PlayerInterfaceId。
     *
     * 动机：有 piid 才能把探测结果写回 hook 记录，供后续 setVolume 命中 STARTED。
     * 缺方法返回 null，调用方仍保留 uid。
     *
     * @param config 一条播放配置
     * @return piid；缺方法时为 null
     */
    fun playerInterfaceId(config: Any): Int? {
        val method = accessOf(config).playerInterfaceId ?: return null
        val result = invoke(method, config)
        return result as? Int
            ?: error("${config.javaClass.name}.getPlayerInterfaceId returned non-Int: ${result?.javaClass?.name}")
    }

    /**
     * 读取 `getPlayerProxy()` 并包装成 [HiddenPlayer]。
     *
     * 动机：hook 记录没有 IPlayer 时，探测到的 proxy 可以补上调音入口。
     * 缺方法或 proxy 为 null 时返回 null，不能丢掉 uid。
     *
     * @param config 一条播放配置
     */
    fun player(config: Any): HiddenPlayer? {
        val method = accessOf(config).playerProxy ?: return null
        val proxy = invoke(method, config) ?: return null
        return HiddenPlayer(proxy)
    }

    private fun accessOf(config: Any): ResolvedAccess =
        resolved.getOrPut(config.javaClass) { ResolvedAccess.resolve(config.javaClass) }

    private data class ResolvedAccess(
        val clientUid: MethodResolver<Any>,
        val isActive: MethodResolver<Any>,
        val playerInterfaceId: MethodResolver<Any>?,
        val playerProxy: MethodResolver<Any>?,
    ) {
        companion object {
            fun resolve(clazz: Class<*>): ResolvedAccess = ResolvedAccess(
                requiredMethod(clazz, METHOD_GET_CLIENT_UID),
                requiredMethod(clazz, METHOD_IS_ACTIVE),
                optionalMethod(clazz, METHOD_GET_PLAYER_INTERFACE_ID),
                optionalMethod(clazz, METHOD_GET_PLAYER_PROXY),
            )
        }
    }

    private companion object {
        const val METHOD_GET_CLIENT_UID = "getClientUid"
        const val METHOD_IS_ACTIVE = "isActive"
        const val METHOD_GET_PLAYER_INTERFACE_ID = "getPlayerInterfaceId"
        const val METHOD_GET_PLAYER_PROXY = "getPlayerProxy"

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
