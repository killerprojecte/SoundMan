package hk.uwu.soundman.hook.scopes.system

import android.annotation.SuppressLint
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import hk.uwu.soundman.hook.core.YLog
import hk.uwu.soundman.hook.scopes.system.PreferredDeviceHooker.resolveSystemDevice
import hk.uwu.soundman.hook.scopes.system.hidden.SystemMediaDeviceProbe
import hk.uwu.soundman.ipc.PreferredDeviceSync
import hk.uwu.soundman.ipc.PreferredDeviceUsage
import hk.uwu.soundman.ipc.SoundManProtocol
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zygote 只钩 Application 启动。冷启动从模块 prefs 恢复本 uid 的伪装 usage。
 *
 * 最多三条独立链路：MEDIA / 铃声 / 闹钟。单设备不伪装；
 * Mix 拆开后再 `setPreferredDevice` 钉到所选硬件。
 */
object PreferredDeviceHooker : YukiBaseHooker() {
    private val applying = ThreadLocal.withInitial { false }
    private val tracks = CopyOnWriteArrayList<WeakReference<AudioTrack>>()
    private val players = CopyOnWriteArrayList<WeakReference<MediaPlayer>>()
    private val receiverRegistered = AtomicBoolean(false)
    private val trackHooksInstalled = AtomicBoolean(false)
    private val deviceCallbackRegistered = AtomicBoolean(false)
    private val loggedUnsetSkip = AtomicBoolean(false)
    @Volatile
    private var application: Application? = null
    private val systemMediaDeviceProbe: SystemMediaDeviceProbe? =
        SystemMediaDeviceProbe.createForAppProcess()
    private val routeLock = Any()
    private var cachedRoute: RouteState = RouteState.Unset
    private var cachedDevice: AudioDeviceInfo? = null
    @Volatile
    private var cachedUsage: Int = PreferredDeviceUsage.USAGE_MEDIA

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != PreferredDeviceSync.ACTION) return
            val hint = try {
                PreferredDeviceSync.decodeIntent(intent)
            } catch (error: Throwable) {
                YLog.error("[route] decode broadcast failed uid=${Process.myUid()}", error)
                return
            }
            try {
                if (hint.uid != Process.myUid()) return
                applyHint(hint, source = "broadcast")
            } catch (error: Throwable) {
                YLog.error("[route] broadcast handle failed uid=${Process.myUid()}", error)
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            YLog.info("[route] devices added count=${addedDevices.size} uid=${Process.myUid()}")
            applyToRegistered()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            synchronized(routeLock) { cachedDevice = null }
            applyToRegistered()
        }
    }

    override fun onHook() {
        hookApplicationStart()
    }

    private fun hookApplicationStart() {
        "android.app.Instrumentation".toClass().resolve().firstMethod {
            name = "callApplicationOnCreate"
            parameters(Application::class.java)
        }.hook {
            before {
                try {
                    val app = args[0] as? Application ?: return@before
                    application = app
                    YLog.info("[route] application start pkg=${app.packageName} uid=${Process.myUid()} ${describeRoute()}")
                    loadStoredRoute()
                    installTrackHooks()
                    ensureReceiver()
                    ensureDeviceCallback()
                } catch (error: Throwable) {
                    YLog.error("[route] application start failed uid=${Process.myUid()}", error)
                }
            }
        }
    }

    /**
     * 开机后模块 App 不会自动起来，广播到不了。
     * Application.onCreate 之前从模块 prefs 读全量 hint，按占用设备重算三条链路。
     *
     * 系统当前 MEDIA 输出设备由 App 进程自行探测（[resolveSystemDevice]），
     * 因为此时还没有 snapshot 可用。广播到达后会由模块进程的 rebroadcast 更新。
     */
    private fun loadStoredRoute() {
        val uid = Process.myUid()
        val entries = moduleHintEntries()
        YLog.info("[route] prefs keys=${entries.size} entries=${describeEntries(entries)} uid=$uid")
        val systemDevice = resolveSystemDevice()
        YLog.info(
            "[route] cold start system device=${
                systemDevice?.let { "${it.publicType}|${it.address.ifEmpty { "<empty>" }}" } ?: "null"
            } uid=$uid")
        val hint = try {
            val allocated = PreferredDeviceUsage.withAllocatedUsages(
                PreferredDeviceSync.hintsFromEntries(entries),
                systemDevice,
            )
            YLog.info("[route] allocated ${PreferredDeviceUsage.describe(allocated)} self=$uid")
            allocated.firstOrNull { candidate -> candidate.uid == uid }
        } catch (error: Throwable) {
            YLog.error("[route] allocate from prefs failed uid=$uid", error)
            return
        }
        if (hint == null) {
            YLog.warn("[route] no stored hint uid=$uid; stay Unset until broadcast")
            return
        }
        applyHint(hint, source = "prefs")
    }

    /**
     * App 进程冷启动时自行探测系统当前 MEDIA 输出设备。
     *
     * 动机：冷启动时没有 snapshot，模块进程可能还没起来。
     * 通过反射 `AudioSystem.getDevicesForStream(STREAM_MUSIC)` 获取实际路由，
     * 不使用设备优先级猜测。
     */
    private fun resolveSystemDevice(): PreferredDeviceSync.DeviceSpec? {
        val context = currentApplication() ?: return null
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return null
        val probe = systemMediaDeviceProbe ?: run {
            YLog.warn("[route] SystemMediaDeviceProbe unavailable (reflection failed)")
            return null
        }
        return probe.probe(audioManager)
    }

    private fun moduleHintEntries(): Map<String, *> {
        return try {
            val modulePrefs = prefs(PreferredDeviceSync.PREFS_NAME)
            val entries = modulePrefs.all()
            YLog.info(
                "[route] yuki prefs available=${modulePrefs.isPreferencesAvailable} " +
                        "keys=${entries.size} uid=${Process.myUid()}",
            )
            entries
        } catch (error: Throwable) {
            YLog.error("[route] yuki prefs failed uid=${Process.myUid()}", error)
            emptyMap<String, Any>()
        }
    }

    private fun describeEntries(entries: Map<String, *>): String =
        entries.entries.joinToString(prefix = "[", postfix = "]") { (key, value) ->
            "$key=${(value as? String)?.ifEmpty { "<follow>" } ?: value}"
        }

    private fun applyHint(hint: PreferredDeviceSync.RouteHint, source: String) {
        synchronized(routeLock) {
            cachedRoute = if (hint.followSystem) {
                RouteState.FollowSystem
            } else {
                RouteState.Device(PreferredDeviceSync.DeviceSpec(hint.publicType, hint.address))
            }
            cachedDevice = null
            cachedUsage = hint.usage
        }
        loggedUnsetSkip.set(false)
        YLog.info(
            "[route] apply source=$source ${PreferredDeviceSync.describe(hint)} " +
                    "rewrite=${PreferredDeviceUsage.shouldRewrite(hint.usage)}",
        )
        applyToRegistered()
    }

    private fun installTrackHooks() {
        if (!trackHooksInstalled.compareAndSet(false, true)) return
        try {
            hookAudioTrack()
            hookPlayerBaseUsage()
            hookMediaPlayer()
            YLog.info("[route] disguise hooks installed uid=${Process.myUid()}")
        } catch (error: Throwable) {
            trackHooksInstalled.set(false)
            YLog.error("[route] disguise hooks failed uid=${Process.myUid()}", error)
        }
    }

    private fun hookAudioTrack() {
        val resolved = "android.media.AudioTrack".toClass().resolve()
        val constructors = resolved.constructor {
            parameterCount { count -> count >= 4 }
        }
        check(constructors.isNotEmpty()) { "AudioTrack has no constructors with >= 4 parameters" }
        constructors.hookAll {
            before {
                runHookSide("AudioTrack.<init> disguise") {
                    rewriteUsageArgs()
                }
            }
            after {
                if (throwable != null) return@after
                runHookSide("AudioTrack.<init> pin") {
                    val track = instance as AudioTrack
                    registerTrack(track)
                    applyToTrack(track)
                }
            }
        }
        resolved.firstMethod {
            name = "play"
            emptyParameters()
        }.hook {
            after {
                if (throwable != null) return@after
                runHookSide("AudioTrack.play pin") {
                    val track = instance as AudioTrack
                    registerTrack(track)
                    applyToTrack(track)
                }
            }
        }
        resolved.firstMethod {
            name = "setPreferredDevice"
            parameters(AudioDeviceInfo::class.java)
        }.hook {
            before {
                if (applying.get() == true) return@before
                runHookSide("AudioTrack.setPreferredDevice pin") {
                    val forced = resolveForcedDevice() ?: return@runHookSide
                    args(0).set(forced)
                }
            }
        }
        "android.media.AudioTrack\$Builder".toClass().resolve().firstMethod {
            name = "build"
            emptyParameters()
        }.hook {
            before {
                runHookSide("AudioTrack.Builder.build disguise") {
                    rewriteBuilderUsage(instance)
                }
            }
            after {
                if (throwable != null) return@after
                runHookSide("AudioTrack.Builder.build pin") {
                    val track = result as? AudioTrack ?: return@runHookSide
                    registerTrack(track)
                    applyToTrack(track)
                }
            }
        }
    }

    /**
     * 这台 ROM 的 PlayerBase 没有 `setAudioAttributes`。
     * 构造写入 `PlayerBase(AudioAttributes, int)`，运行中改 usage 走 `baseUpdateAudioAttributes`。
     */
    private fun hookPlayerBaseUsage() {
        val resolved = "android.media.PlayerBase".toClass().resolve()
        resolved.constructor {
            parameters(AudioAttributes::class.java, Int::class.javaPrimitiveType!!)
        }.hookAll {
            before {
                runHookSide("PlayerBase.<init> disguise") {
                    rewriteUsageArgs()
                }
            }
        }
        resolved.firstMethod {
            name = "baseUpdateAudioAttributes"
            parameters(AudioAttributes::class.java)
        }.hook {
            before {
                runHookSide("PlayerBase.baseUpdateAudioAttributes disguise") {
                    rewriteUsageArgs()
                }
            }
        }
        YLog.info("[route] PlayerBase disguise hooks installed uid=${Process.myUid()}")
    }

    private fun hookMediaPlayer() {
        val resolved = "android.media.MediaPlayer".toClass().resolve()
        resolved.firstMethod {
            name = "start"
            emptyParameters()
        }.hook {
            after {
                if (throwable != null) return@after
                runHookSide("MediaPlayer.start pin") {
                    val player = instance as MediaPlayer
                    registerPlayer(player)
                    applyToPlayer(player)
                }
            }
        }
        resolved.firstMethod {
            name = "setAudioAttributes"
            parameters(AudioAttributes::class.java)
        }.hook {
            before {
                runHookSide("MediaPlayer.setAudioAttributes disguise") {
                    rewriteUsageArgs()
                }
            }
        }
        resolved.firstMethod {
            name = "setPreferredDevice"
            parameters(AudioDeviceInfo::class.java)
        }.hook {
            before {
                if (applying.get() == true) return@before
                runHookSide("MediaPlayer.setPreferredDevice pin") {
                    val forced = resolveForcedDevice() ?: return@runHookSide
                    args(0).set(forced)
                }
            }
        }
    }

    @SuppressLint("WrongConstant")
    private fun com.highcapable.yukihookapi.hook.param.HookParam.rewriteUsageArgs() {
        val usage = cachedUsage
        val uid = Process.myUid()
        if (currentRoute() is RouteState.Unset) {
            logUnsetSkip("rewrite usage")
            return
        }
        if (!PreferredDeviceUsage.shouldRewrite(usage)) {
            YLog.debug("[route] skip disguise uid=$uid usage=${PreferredDeviceUsage.name(usage)} ${describeRoute()}")
            return
        }
        var seenAttributes = false
        args.forEachIndexed { index, value ->
            val attributes = value as? AudioAttributes ?: return@forEachIndexed
            seenAttributes = true
            if (attributes.usage == usage) {
                YLog.debug("[route] usage already ${PreferredDeviceUsage.name(usage)} uid=$uid")
                return@forEachIndexed
            }
            YLog.info(
                "[route] disguise usage ${PreferredDeviceUsage.name(attributes.usage)}->${
                    PreferredDeviceUsage.name(
                        usage
                    )
                } " +
                        "uid=$uid arg=$index",
            )
            args(index).set(AudioAttributes.Builder(attributes).setUsage(usage).build())
        }
        val first = args.firstOrNull()
        if (first is Int && first in 0..10) {
            val target = PreferredDeviceUsage.streamType(usage)
            if (first != target) {
                YLog.info("[route] disguise streamType $first->$target uid=$uid")
                args(0).set(target)
            }
        } else if (!seenAttributes) {
            val argTypes = StringBuilder("[")
            args.forEachIndexed { index, value ->
                if (index > 0) argTypes.append(", ")
                argTypes.append(value?.javaClass?.simpleName ?: "null")
            }
            argTypes.append(']')
            YLog.debug("[route] no AudioAttributes to disguise uid=$uid args=$argTypes")
        }
    }

    @SuppressLint("WrongConstant")
    private fun rewriteBuilderUsage(builder: Any?) {
        val usage = cachedUsage
        val uid = Process.myUid()
        if (builder == null) return
        if (currentRoute() is RouteState.Unset) {
            logUnsetSkip("Builder usage")
            return
        }
        if (!PreferredDeviceUsage.shouldRewrite(usage)) {
            YLog.debug(
                "[route] skip Builder disguise uid=$uid usage=${
                    PreferredDeviceUsage.name(
                        usage
                    )
                }"
            )
            return
        }
        val field = builder.javaClass.getDeclaredField("mAttributes").apply { isAccessible = true }
        val attributes = field.get(builder) as? AudioAttributes ?: run {
            YLog.debug("[route] Builder mAttributes missing uid=$uid")
            return
        }
        if (attributes.usage == usage) {
            YLog.debug("[route] Builder usage already ${PreferredDeviceUsage.name(usage)} uid=$uid")
            return
        }
        YLog.info(
            "[route] disguise Builder usage ${PreferredDeviceUsage.name(attributes.usage)}->" +
                    "${PreferredDeviceUsage.name(usage)} uid=$uid",
        )
        field.set(builder, AudioAttributes.Builder(attributes).setUsage(usage).build())
    }

    private fun registerTrack(track: AudioTrack) {
        prune(tracks)
        if (tracks.none { it.get() === track }) {
            tracks += WeakReference(track)
        }
        ensureReceiver()
        ensureDeviceCallback()
    }

    private fun registerPlayer(player: MediaPlayer) {
        prune(players)
        if (players.none { it.get() === player }) {
            players += WeakReference(player)
        }
        ensureReceiver()
        ensureDeviceCallback()
    }

    private fun applyToTrack(track: AudioTrack) {
        when (val route = currentRoute()) {
            RouteState.Unset -> logUnsetSkip("AudioTrack pin")
            RouteState.FollowSystem -> {
                if (track.preferredDevice == null) return
                YLog.info("[route] AudioTrack clear preferredDevice uid=${Process.myUid()}")
                applyPreferred { track.setPreferredDevice(null) }
            }
            is RouteState.Device -> {
                val device = liveDevice(route.spec) ?: return
                if (sameDevice(track.preferredDevice, device)) return
                applyPreferred {
                    val ok = track.setPreferredDevice(device)
                    YLog.info(
                        "[route] AudioTrack.pin ${if (ok) "ok" else "failed"} uid=${Process.myUid()} " +
                                "type=${device.type} address=${
                                    device.address.orEmpty().ifEmpty { "<empty>" }
                                } " +
                                "usage=${PreferredDeviceUsage.name(cachedUsage)}",
                    )
                }
            }
        }
    }

    private fun applyToPlayer(player: MediaPlayer) {
        when (val route = currentRoute()) {
            RouteState.Unset -> logUnsetSkip("MediaPlayer pin")
            RouteState.FollowSystem -> {
                if (player.preferredDevice == null) return
                YLog.info("[route] MediaPlayer clear preferredDevice uid=${Process.myUid()}")
                applyPreferred { player.setPreferredDevice(null) }
            }
            is RouteState.Device -> {
                val device = liveDevice(route.spec) ?: return
                if (sameDevice(player.preferredDevice, device)) return
                applyPreferred {
                    val ok = player.setPreferredDevice(device)
                    YLog.info(
                        "[route] MediaPlayer.pin ${if (ok) "ok" else "failed"} uid=${Process.myUid()} " +
                                "type=${device.type} address=${
                                    device.address.orEmpty().ifEmpty { "<empty>" }
                                } " +
                                "usage=${PreferredDeviceUsage.name(cachedUsage)}",
                    )
                }
            }
        }
    }

    private fun sameDevice(current: AudioDeviceInfo?, target: AudioDeviceInfo): Boolean =
        current != null && PreferredDeviceSync.matches(
            current.type,
            current.address.orEmpty(),
            PreferredDeviceSync.DeviceSpec(target.type, target.address.orEmpty())
        )

    private fun applyToRegistered() {
        prune(tracks)
        prune(players)
        tracks.forEach { reference ->
            val track = reference.get() ?: return@forEach
            applyToTrack(track)
        }
        players.forEach { reference ->
            val player = reference.get() ?: return@forEach
            applyToPlayer(player)
        }
    }

    private fun resolveForcedDevice(): AudioDeviceInfo? {
        val route = currentRoute() as? RouteState.Device ?: return null
        return liveDevice(route.spec)
    }

    private fun liveDevice(spec: PreferredDeviceSync.DeviceSpec): AudioDeviceInfo? {
        synchronized(routeLock) {
            cachedDevice?.let { cached ->
                if (PreferredDeviceSync.matches(
                        cached.type,
                        cached.address.orEmpty(),
                        spec
                    )
                ) return cached
            }
        }
        val context = currentApplication() ?: run {
            YLog.warn("[route] no Application; skip device resolve uid=${Process.myUid()}")
            return null
        }
        val audioManager = context.getSystemService(AudioManager::class.java)
        if (audioManager == null) {
            YLog.error("[route] AudioManager unavailable uid=${Process.myUid()}")
            return null
        }
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val device = PreferredDeviceSync.findDevice(devices = outputs, spec = spec)
        if (device == null) {
            val listed = outputs.joinToString(prefix = "[", postfix = "]") { output ->
                "type=${output.type} address=${output.address.orEmpty().ifEmpty { "<empty>" }}"
            }
            YLog.error(
                "[route] device not found uid=${Process.myUid()} publicType=${spec.publicType} " +
                        "address=${spec.address.ifEmpty { "<empty>" }} outputs=$listed",
            )
            return null
        }
        synchronized(routeLock) { cachedDevice = device }
        return device
    }

    private fun currentRoute(): RouteState = synchronized(routeLock) { cachedRoute }

    private fun describeRoute(): String = synchronized(routeLock) {
        val route = when (val current = cachedRoute) {
            RouteState.Unset -> "Unset"
            RouteState.FollowSystem -> "FollowSystem"
            is RouteState.Device ->
                "Device(type=${current.spec.publicType} address=${current.spec.address.ifEmpty { "<empty>" }})"
        }
        "$route usage=${PreferredDeviceUsage.name(cachedUsage)}"
    }

    private fun logUnsetSkip(where: String) {
        if (!loggedUnsetSkip.compareAndSet(false, true)) return
        YLog.warn("[route] $where skipped; state=Unset uid=${Process.myUid()} (waiting for prefs or broadcast)")
    }

    private fun ensureReceiver() {
        if (!receiverRegistered.compareAndSet(false, true)) return
        val context = currentApplication()
        if (context == null) {
            receiverRegistered.set(false)
            YLog.warn("[route] cannot register receiver before Application uid=${Process.myUid()}")
            return
        }
        val uid = Process.myUid()
        try {
            context.registerReceiver(
                receiver,
                IntentFilter(PreferredDeviceSync.ACTION),
                SoundManProtocol.CONTROL_PERMISSION,
                Handler(Looper.getMainLooper()),
                Context.RECEIVER_EXPORTED,
            )
            YLog.info("[route] receiver registered uid=$uid ${describeRoute()}")
        } catch (error: Throwable) {
            receiverRegistered.set(false)
            YLog.error("[route] register receiver failed uid=$uid", error)
        }
    }

    private fun ensureDeviceCallback() {
        if (!deviceCallbackRegistered.compareAndSet(false, true)) return
        val context = currentApplication()
        val audioManager = context?.getSystemService(AudioManager::class.java)
        if (audioManager == null) {
            deviceCallbackRegistered.set(false)
            return
        }
        try {
            audioManager.registerAudioDeviceCallback(
                deviceCallback,
                Handler(Looper.getMainLooper())
            )
        } catch (error: Throwable) {
            deviceCallbackRegistered.set(false)
            YLog.error("[route] register AudioDeviceCallback failed uid=${Process.myUid()}", error)
        }
    }

    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun currentApplication(): Application? {
        application?.let { return it }
        val found = try {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Application
        } catch (_: Throwable) {
            null
        }
        if (found != null) application = found
        return found
    }

    private inline fun runHookSide(label: String, block: () -> Unit) {
        try {
            if (currentApplication() == null) return
            block()
        } catch (error: Throwable) {
            YLog.error("[route] $label failed uid=${Process.myUid()}", error)
        }
    }

    private fun applyPreferred(block: () -> Unit) {
        applying.set(true)
        try {
            block()
        } finally {
            applying.set(false)
        }
    }

    private fun <T : Any> prune(refs: CopyOnWriteArrayList<WeakReference<T>>) {
        refs.removeAll { it.get() == null }
    }

    private sealed interface RouteState {
        data object Unset : RouteState
        data object FollowSystem : RouteState
        data class Device(val spec: PreferredDeviceSync.DeviceSpec) : RouteState
    }
}
