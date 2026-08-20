package hk.uwu.soundman.hook.scopes.system

import android.content.Context
import android.os.Binder
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import hk.uwu.soundman.hook.core.YLog
import hk.uwu.soundman.hook.scopes.system.hidden.HiddenAudioSystem
import hk.uwu.soundman.hook.scopes.system.hidden.HiddenPlayer
import hk.uwu.soundman.hook.scopes.system.hidden.HiddenPlayerAccess
import hk.uwu.soundman.hook.scopes.system.hidden.MediaPlaybackAccess
import hk.uwu.soundman.hook.scopes.system.hidden.OutputDeviceMapper
import hk.uwu.soundman.hook.scopes.system.hidden.PlaybackConfigurationAccess
import hk.uwu.soundman.hook.scopes.system.hidden.PlaybackProbeFactory
import hk.uwu.soundman.hook.scopes.system.hidden.SystemMediaDeviceProbe
import hk.uwu.soundman.hook.scopes.system.runtime.OutputDeviceConsolidator
import hk.uwu.soundman.hook.scopes.system.runtime.SnapshotPlaybackMerge
import hk.uwu.soundman.hook.scopes.system.runtime.SystemAudioRuntime

/**
 * 安装 AudioService 稳定 Hook，并把所有业务状态委托给不持有 HookHandle 的 Runtime。
 *
 * 动机：由 [hk.uwu.soundman.hook.HookEntry] 在 `loadSystem` 中装载，不再包一层自研 Scope。
 */
object SystemAudioHooker : YukiBaseHooker() {
    private val runtimeLock = Any()
    private val pendingPlayerUpdates = ArrayList<PlayerUpdate>()
    private val trackCallingUid = ThreadLocal<Int>()
    private var runtimeInitializing = false
    private var runtime: SystemAudioRuntime? = null
    private lateinit var audioSystem: HiddenAudioSystem
    private lateinit var playerAccess: HiddenPlayerAccess

    override fun onHook() {
        audioSystem = HiddenAudioSystem(requireAppClassLoader())
        val playerIdCardClass = "android.media.PlayerBase\$PlayerIdCard".toClass()
        playerAccess = HiddenPlayerAccess(playerIdCardClass)
        val audioServiceClass = "com.android.server.audio.AudioService".toClass()
        val audioService = audioServiceClass.resolve()
        audioService.firstMethod {
            name = "trackPlayer"
            returnType = Int::class.javaPrimitiveType
            parameters(playerIdCardClass)
        }.hook {
            before {
                trackCallingUid.set(Binder.getCallingUid())
            }
            after {
                val uid = trackCallingUid.get()
                trackCallingUid.remove()
                if (throwable != null) return@after
                checkNotNull(uid) { "trackPlayer calling UID was not captured" }
                val card = args[0] ?: error("AudioService.trackPlayer missing PlayerIdCard")
                val piid = (result as? Number)?.toInt()
                    ?: error("AudioService.trackPlayer returned non-numeric result: $result")
                dispatchPlayerUpdate(PlayerUpdate.Track(piid, uid, playerAccess.fromPlayerIdCard(card)))
            }
        }
        audioService.method {
            name = "playerEvent"
        }.hookAll {
            after {
                if (throwable == null) {
                    dispatchPlayerUpdate(PlayerUpdate.Event(args(0).int(), args(1).int()))
                }
            }
        }
        audioService.firstMethod {
            name = "releasePlayer"
            returnType = Void.TYPE
            parameters(Int::class.javaPrimitiveType!!)
        }.hook {
            after {
                if (throwable == null) dispatchPlayerUpdate(PlayerUpdate.Release(args(0).int()))
            }
        }
        audioService.firstMethod {
            name = "systemReady"
            returnType = Void.TYPE
            emptyParameters()
        }.hook {
            after {
                if (throwable == null) initializeRuntime(systemContext)
            }
        }
    }

    private fun requireAppClassLoader(): ClassLoader =
        appClassLoader ?: error("SystemAudioHooker appClassLoader is null")

    private fun initializeRuntime(context: Context) {
        synchronized(runtimeLock) {
            if (runtime != null) {
                runtime!!.onSystemReady()
                return
            }
            check(!runtimeInitializing) { "SystemAudioRuntime initialization re-entered" }
            runtimeInitializing = true
        }
        try {
            check(::audioSystem.isInitialized) { "HiddenAudioSystem is not initialized before SystemAudioRuntime" }
            val created = SystemAudioRuntime(
                context = context,
                outputDeviceMapper = OutputDeviceMapper(audioSystem),
                outputDeviceConsolidator = OutputDeviceConsolidator(),
                playbackProbe = PlaybackProbeFactory.create(
                    access = PlaybackConfigurationAccess(),
                    mediaAccess = MediaPlaybackAccess(),
                    packageNameForUid = { uid -> context.packageManager.getNameForUid(uid) },
                    logError = { message, throwable -> YLog.error(message, throwable) },
                ),
                playbackMerge = SnapshotPlaybackMerge(),
                systemMediaDeviceProbe = SystemMediaDeviceProbe { streamType ->
                    audioSystem.getDevicesForStream(streamType)
                },
                log = { level, message, throwable ->
                    when (level) {
                        SystemAudioRuntime.LOG_DEBUG -> YLog.debug(message, throwable)
                        SystemAudioRuntime.LOG_INFO -> YLog.info(message, throwable)
                        SystemAudioRuntime.LOG_WARN -> YLog.warn(message, throwable)
                        else -> YLog.error(message, throwable)
                    }
                },
            )
            synchronized(runtimeLock) { runtime = created }
            created.onSystemReady()
            val queued = synchronized(runtimeLock) {
                val copy = pendingPlayerUpdates.toList()
                pendingPlayerUpdates.clear()
                copy
            }
            queued.forEach { it.dispatch(created) }
        } catch (throwable: Throwable) {
            YLog.error("Failed to initialize system audio runtime after systemReady", throwable)
            throw throwable
        } finally {
            synchronized(runtimeLock) { runtimeInitializing = false }
        }
    }

    private fun dispatchPlayerUpdate(update: PlayerUpdate) {
        synchronized(runtimeLock) {
            val current = runtime
            if (current == null) {
                pendingPlayerUpdates += update
                YLog.debug("Cached AudioService player update before systemReady: $update", null)
            } else {
                update.dispatch(current)
            }
        }
    }

    private sealed class PlayerUpdate {
        abstract fun dispatch(runtime: SystemAudioRuntime)
        data class Track(val piid: Int, val uid: Int, val player: HiddenPlayer?) : PlayerUpdate() {
            override fun dispatch(runtime: SystemAudioRuntime) = runtime.onTrackPlayer(piid, uid, player)
        }
        data class Event(val piid: Int, val state: Int) : PlayerUpdate() {
            override fun dispatch(runtime: SystemAudioRuntime) = runtime.onPlayerEvent(piid, state)
        }
        data class Release(val piid: Int) : PlayerUpdate() {
            override fun dispatch(runtime: SystemAudioRuntime) = runtime.onReleasePlayer(piid)
        }
    }
}
