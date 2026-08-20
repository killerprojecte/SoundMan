package hk.uwu.soundman.hook.scopes.system.hidden

import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import android.os.RemoteException
import com.highcapable.kavaref.extension.makeAccessible
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 隐藏 `android.media.IPlayer` 的反射访问面。
 *
 * 动机：AudioService.trackPlayer 拿到的是 framework IPlayer，公开 SDK 没有这个类型。
 * 业务层通过 [setVolume] 调音。把运行时 IPlayer / player proxy 包装成本类。
 * 构造期解析方法，缺 `setVolume(float)` 立即失败。
 *
 * @param player 具备 `setVolume(float)` 的运行时播放器
 */
class HiddenPlayer(player: Any) {
    private val instance: Any = player
    private val setVolume: Method = resolveSetVolume(player.javaClass)
    private val pause: Method? = resolveNoArg(player.javaClass, METHOD_PAUSE, METHOD_TRACK_PAUSE)
    private val start: Method? = resolveNoArg(player.javaClass, METHOD_START, METHOD_TRACK_START)

    /**
     * 底层 IPlayer 的 Binder，用于监听播放器进程死亡。
     *
     * 动机：当播放器所在进程死亡后，Binder 代理变为 dead。
     * 通过 [IBinder.DeathRecipient] 可以在进程死亡的瞬间主动清理，
     * 不必等到下次调用 `setVolume` / `pause` 时才收到 `DeadObjectException`。
     * 如果 player 不是 [IInterface]（例如测试假对象），则为 null。
     */
    val binder: IBinder? = (player as? IInterface)?.asBinder()

    /**
     * 播放器是否仍然存活。
     *
     * 动机：在 kick 或 setVolume 之前可以先检查，避免不必要的跨进程调用。
     * 没有 binder 时（测试假对象）始终返回 true。
     *
     * @return binder 存在时返回 `binder.isBinderAlive`，否则 true
     */
    fun isAlive(): Boolean = binder?.isBinderAlive ?: true

    /**
     * 设置该播放器的音量倍率。
     *
     * 动机：SystemAudioRuntime 在规则生效或临时调音时调用 IPlayer.setVolume(float)。
     * 当播放器进程已死亡时抛出 [PlayerDeadException]，调用方据此清理记录。
     *
     * @param volume 0f..1f 的倍率；具体范围由 framework 解释
     * @throws PlayerDeadException 播放器进程已死亡
     */
    fun setVolume(volume: Float) {
        try {
            setVolume.invoke(instance, volume)
        } catch (error: InvocationTargetException) {
            throw wrapIfDead(error.targetException ?: error)
        }
    }

    /**
     * 暂停再启动，迫使当前 Track 按新策略重新选输出设备。
     *
     * 动机：部分 ROM 在改道后仍需要 kick 一下已在播的 IPlayer。
     * 当播放器进程已死亡时返回 false 而非抛异常，调用方据此清理记录。
     *
     * @return 是否成功发出 pause+start；播放器进程已死亡或缺少方法时返回 false
     */
    fun restartForReroute(): Boolean {
        val pauseMethod = pause
        val startMethod = start
        if (pauseMethod == null || startMethod == null) return false
        try {
            pauseMethod.invoke(instance)
            startMethod.invoke(instance)
            return true
        } catch (error: InvocationTargetException) {
            val target = error.targetException ?: error
            if (target is DeadObjectException || target is RemoteException) {
                throw PlayerDeadException("IPlayer died during restartForReroute", target)
            }
            throw target
        }
    }

    private companion object {
        const val METHOD_SET_VOLUME = "setVolume"
        const val METHOD_PAUSE = "pause"
        const val METHOD_START = "start"
        const val METHOD_TRACK_PAUSE = "trackPause"
        const val METHOD_TRACK_START = "trackStart"

        fun resolveNoArg(playerClass: Class<*>, vararg names: String): Method? {
            names.forEach { name ->
                val method = try {
                    playerClass.getMethod(name)
                } catch (publicMissing: NoSuchMethodException) {
                    try {
                        playerClass.getDeclaredMethod(name)
                    } catch (declaredMissing: NoSuchMethodException) {
                        null
                    }
                }
                if (method != null) {
                    method.makeAccessible()
                    return method
                }
            }
            return null
        }

        fun resolveSetVolume(playerClass: Class<*>): Method {
            val parameterTypes = arrayOf(Float::class.javaPrimitiveType!!)
            val method = try {
                playerClass.getMethod(METHOD_SET_VOLUME, *parameterTypes)
            } catch (publicMissing: NoSuchMethodException) {
                try {
                    playerClass.getDeclaredMethod(METHOD_SET_VOLUME, *parameterTypes)
                } catch (declaredMissing: NoSuchMethodException) {
                    throw IllegalStateException(
                        "Missing method $METHOD_SET_VOLUME(float) on ${playerClass.name}",
                        declaredMissing,
                    )
                }
            }
            method.makeAccessible()
            return method
        }

        /**
         * 如果异常是 [DeadObjectException] 或 [RemoteException]，包装为 [PlayerDeadException]；
         * 否则原样抛出。
         */
        fun wrapIfDead(throwable: Throwable): Throwable {
            if (throwable is DeadObjectException || throwable is RemoteException) {
                return PlayerDeadException("IPlayer is dead", throwable)
            }
            return throwable
        }
    }
}

/**
 * 播放器进程已死亡时抛出的异常。
 *
 * 动机：区分普通的调用异常和 binder 死亡，让 [SystemAudioRuntime] 能据此
 * 主动移除已死亡的播放器记录并发送快照更新，避免死播放器继续显示在音量面板。
 */
class PlayerDeadException(message: String, cause: Throwable) : RuntimeException(message, cause)
