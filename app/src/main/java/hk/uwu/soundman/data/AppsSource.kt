package hk.uwu.soundman.data

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.highcapable.kavaref.extension.classOf
import hk.uwu.soundman.R
import hk.uwu.soundman.hook.scopes.system.hidden.SystemMediaDeviceProbe
import hk.uwu.soundman.ipc.PreferredDeviceSync
import hk.uwu.soundman.ipc.SoundManHostBridgeClient
import hk.uwu.soundman.ipc.SoundManProtocol
import hk.uwu.soundman.log.AppLog
import hk.uwu.soundman.model.AdjustableApp
import hk.uwu.soundman.model.OutputTarget
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

enum class ActiveMediaAppsError { HOST_UNAVAILABLE }

sealed interface ActiveMediaAppsState {
    data class Available(val apps: List<AdjustableApp>) : ActiveMediaAppsState
    data class Error(val reason: ActiveMediaAppsError) : ActiveMediaAppsState
}

data class HostCommandResult(
    val commandId: String,
    val uid: Int,
    val success: Boolean,
    val resultCode: Int,
    val effectiveTarget: OutputTarget?,
)

interface ActiveMediaAppsSource {
    fun observe(observer: (ActiveMediaAppsState) -> Unit): () -> Unit
}

/** App 端 Binder 客户端；业务快照与命令结果只通过 host callback 发布。 */
class HostPlaybackSource(
    context: Context,
    private val ruleStore: RuleStore,
    private val installedAppsAccess: InstalledAppsAccess,
) : ActiveMediaAppsSource, AutoCloseable {
    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager
    private val observers = CopyOnWriteArraySet<(ActiveMediaAppsState) -> Unit>()
    private val resultObservers = CopyOnWriteArraySet<(HostCommandResult) -> Unit>()
    private val deviceObservers = CopyOnWriteArraySet<(AudioDeviceScan) -> Unit>()
    private val worker = HandlerThread("SoundMan.AppIpc").apply { start() }
    private val handler = Handler(worker.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SoundMan.ConnectWait").apply { isDaemon = true }
    }
    private val workerDispatchLock = Any()

    @Volatile
    private var state: ActiveMediaAppsState = ActiveMediaAppsState.Available(emptyList())

    @Volatile
    private var deviceScan = AudioDeviceScan(emptyList(), AudioDeviceScanError.HOST_UNAVAILABLE)

    @Volatile
    private var closed = false

    @Volatile
    private var cachedSystemDevice: PreferredDeviceSync.DeviceSpec? = null

    private val systemMediaDeviceProbe: SystemMediaDeviceProbe? =
        SystemMediaDeviceProbe.createForAppProcess()
    private var deviceCallbackRegistered = false
    private val deviceChangeDebounce = Runnable { reprobeSystemDeviceAndRebroadcast() }

    private var sessionInitialized = false
    private var reconnectAttempt = 0
    private var reconnectScheduled = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            scheduleDeviceReprobe()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            scheduleDeviceReprobe()
        }
    }

    private fun scheduleDeviceReprobe() {
        handler.removeCallbacks(deviceChangeDebounce)
        handler.postDelayed(deviceChangeDebounce, DEVICE_CHANGE_DEBOUNCE_MS)
    }

    private fun reprobeSystemDeviceAndRebroadcast() {
        if (closed) return
        val audioManager = applicationContext.getSystemService(classOf<AudioManager>())
        if (audioManager == null) {
            AppLog.error("[route] AudioManager unavailable for system device reprobe")
            return
        }
        val probe = systemMediaDeviceProbe ?: run {
            AppLog.error("[route] SystemMediaDeviceProbe unavailable (reflection failed)")
            return
        }
        val newDevice = probe.probe(audioManager)
        val oldDevice = cachedSystemDevice
        if (newDevice == oldDevice) return
        cachedSystemDevice = newDevice
        AppLog.info(
            "[route] system device changed old=${
                oldDevice?.let { "${it.publicType}|${it.address.ifEmpty { "<empty>" }}" } ?: "null"
            } new=${
                newDevice?.let { "${it.publicType}|${it.address.ifEmpty { "<empty>" }}" } ?: "null"
            }")
        try {
            PreferredDeviceSync.rebroadcastAllocated(applicationContext, newDevice)
        } catch (error: Throwable) {
            AppLog.error("[route] Failed to rebroadcast after system device change", error)
        }
    }

    private fun ensureDeviceCallback() {
        if (deviceCallbackRegistered) return
        val audioManager = applicationContext.getSystemService(classOf<AudioManager>())
        if (audioManager == null) {
            AppLog.error("[route] AudioManager unavailable for device callback registration")
            return
        }
        try {
            audioManager.registerAudioDeviceCallback(deviceCallback, handler)
            deviceCallbackRegistered = true
            AppLog.info("[route] module process AudioDeviceCallback registered")
        } catch (error: Throwable) {
            AppLog.error("[route] Failed to register AudioDeviceCallback", error)
        }
    }

    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        if (closed || sessionInitialized) return@Runnable
        AppLog.info("Attempting scheduled SoundMan host reconnect")
        connectThenOnWorker("scheduled reconnect failed")
    }

    private val snapshotWatchdog = Runnable {
        if (closed || sessionInitialized) return@Runnable
        AppLog.error("Host handshake produced no snapshot; dropping session and reconnecting")
        sessionInitialized = false
        bridge.resetSession()
        publishUnavailable()
        scheduleReconnect("snapshot watchdog")
    }

    private val bridge = SoundManHostBridgeClient(
        context = applicationContext,
        handshakeHandler = handler,
        eventListener = { event -> postToWorker("host event") { handleEvent(event) } },
        unavailableListener = { reason ->
            postToWorker("host unavailable") {
                sessionInitialized = false
                AppLog.error("SoundMan host unavailable: $reason")
                publishUnavailable()
                scheduleReconnect("host unavailable: $reason")
            }
        },
    )

    init {
        ensureDeviceCallback()
        check(postToWorker("initial connection") {
            connectThenOnWorker("initial connection failed")
        }) { "HostPlaybackSource worker rejected initial connection" }
    }

    override fun observe(observer: (ActiveMediaAppsState) -> Unit): () -> Unit {
        observers += observer
        observer(state)
        return { observers -= observer }
    }

    fun currentDeviceScan(): AudioDeviceScan = deviceScan

    /** 当前缓存的系统 MEDIA 输出设备，供面板 publish 时透传给 allocate。 */
    fun currentSystemDevice(): PreferredDeviceSync.DeviceSpec? = cachedSystemDevice

    fun observeDevices(observer: (AudioDeviceScan) -> Unit): () -> Unit {
        deviceObservers += observer
        observer(deviceScan)
        return { deviceObservers -= observer }
    }

    fun observeResults(observer: (HostCommandResult) -> Unit): () -> Unit {
        resultObservers += observer
        return { resultObservers -= observer }
    }

    fun replaceRules(): String = enqueueCommand("replaceRules") { commandId ->
        sendRules(commandId)
    }

    fun setVolume(uid: Int, percent: Int): String {
        require(uid >= 0) { "uid must be non-negative" }
        require(percent in 0..100) { "percent must be in 0..100" }
        return enqueueCommand("setVolume") { commandId ->
            bridge.setVolume(commandId, uid, percent)
        }
    }

    fun setRoute(uid: Int, target: OutputTarget): String {
        require(uid >= 0) { "uid must be non-negative" }
        return enqueueCommand("setRoute") { commandId ->
            bridge.setRoute(commandId, uid, target)
        }
    }

    private fun enqueueCommand(operation: String, command: (String) -> Unit): String {
        check(!closed) { "HostPlaybackSource is closed" }
        val commandId = UUID.randomUUID().toString()
        check(postToWorker(operation) {
            cancelScheduledReconnect()
            if (bridge.isConnected() && sessionInitialized) {
                runCommand(operation, commandId, command)
                return@postToWorker
            }
            connectThenOnWorker("command connection failed: $operation") {
                runCommand(operation, commandId, command)
            }
        }) { "HostPlaybackSource worker rejected $operation" }
        return commandId
    }

    private fun connectThenOnWorker(failureReason: String, onConnected: () -> Unit = {}) {
        try {
            connectExecutor.execute {
                val connected = try {
                    bridge.connect()
                } catch (error: RuntimeException) {
                    if (closed) return@execute
                    AppLog.error("Unable to connect to SoundMan host", error)
                    false
                }
                postToWorker("connection result: $failureReason") {
                    if (!connected || !initializeSessionIfNeeded()) {
                        if (failureReason.startsWith("command connection failed:")) {
                            AppLog.error(
                                "Host command could not connect: ${
                                    failureReason.removePrefix(
                                        "command connection failed: "
                                    )
                                }"
                            )
                        }
                        publishUnavailable()
                        scheduleReconnect(failureReason)
                        return@postToWorker
                    }
                    onConnected()
                }
            }
        } catch (error: RuntimeException) {
            if (!closed) AppLog.error(
                "Host connect executor rejected request: $failureReason",
                error
            )
        }
    }

    private fun initializeSessionIfNeeded(): Boolean {
        try {
            if (!bridge.isConnected()) {
                sessionInitialized = false
                AppLog.error("Unable to connect to SoundMan host")
                return false
            }
            if (!sessionInitialized) {
                sendRules(UUID.randomUUID().toString())
                bridge.requestSnapshot(UUID.randomUUID().toString())
                armSnapshotWatchdog()
                try {
                    PreferredDeviceSync.publishAll(
                        applicationContext,
                        ruleStore.readAll().values,
                        cachedSystemDevice
                    )
                } catch (error: Throwable) {
                    AppLog.error("[route] Failed to republish preferred device rules", error)
                }
            }
            return true
        } catch (error: RuntimeException) {
            sessionInitialized = false
            AppLog.error("Unable to initialize SoundMan host session", error)
            return false
        }
    }

    private fun runCommand(operation: String, commandId: String, command: (String) -> Unit) {
        try {
            command(commandId)
        } catch (error: RuntimeException) {
            AppLog.error("Host command failed: $operation", error)
            sessionInitialized = false
            publishUnavailable()
            scheduleReconnect("host command failed: $operation")
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (closed || sessionInitialized || reconnectScheduled) return
        val attempt = reconnectAttempt++
        val delayMs = (RECONNECT_BASE_DELAY_MS shl attempt.coerceAtMost(RECONNECT_MAX_SHIFT)).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        reconnectScheduled = true
        AppLog.warn("Scheduling SoundMan host reconnect attempt=${attempt + 1} delayMs=$delayMs reason=$reason")
        if (!postDelayedToWorker("host reconnect", reconnectRunnable, delayMs)) {
            reconnectScheduled = false
        }
    }

    private fun armSnapshotWatchdog() {
        handler.removeCallbacks(snapshotWatchdog)
        postDelayedToWorker("snapshot watchdog", snapshotWatchdog, SNAPSHOT_WATCHDOG_MS)
    }

    private fun cancelSnapshotWatchdog() {
        handler.removeCallbacks(snapshotWatchdog)
    }

    private fun cancelScheduledReconnect() {
        if (!reconnectScheduled) return
        handler.removeCallbacks(reconnectRunnable)
        reconnectScheduled = false
    }

    private fun sendRules(commandId: String) {
        val revision = ruleStore.revision()
        val rules = ruleStore.readAll().values.toList()
        bridge.replaceRules(commandId, revision, rules)
    }

    private fun handleEvent(event: SoundManProtocol.Event) {
        if (closed) return
        when (event) {
            is SoundManProtocol.Event.SnapshotAvailable -> {
                sessionInitialized = true
                reconnectAttempt = 0
                cancelScheduledReconnect()
                cancelSnapshotWatchdog()
                publishSnapshot(event.snapshot)
            }
            is SoundManProtocol.Event.ResultAvailable -> publishResult(event.result)
            is SoundManProtocol.Event.HostError -> {
                AppLog.error("SoundMan host error: ${event.message}")
                publishUnavailable()
            }
            is SoundManProtocol.Event.HostClosed -> {
                sessionInitialized = false
                AppLog.error("SoundMan host closed: ${event.reason}")
                publishUnavailable()
                scheduleReconnect("host closed: ${event.reason}")
            }
        }
    }

    private fun publishSnapshot(snapshot: SoundManProtocol.Snapshot) {
        publishDevices(AudioDeviceScan(snapshot.outputDevices, null))
        val systemDevice = snapshot.systemMediaDevice?.let { device ->
            val identity = device.candidates.first()
            PreferredDeviceSync.DeviceSpec(identity.internalType, identity.address)
        }
        if (systemDevice != null) {
            cachedSystemDevice = systemDevice
            AppLog.info(
                "[route] snapshot system device=${
                    "${systemDevice.publicType}|${systemDevice.address.ifEmpty { "<empty>" }}"
                }"
            )
        }
        val apps = snapshot.playback
            .map { entry -> loadApp(entry.packageName, entry.uid) }
            .sortedBy { it.label.lowercase() }
        AppLog.info(
            "Publishing host snapshot revision=${snapshot.revision} apps=${apps.size} devices=${snapshot.outputDevices.size}",
        )
        publish(ActiveMediaAppsState.Available(apps))
    }

    private fun loadApp(packageName: String, uid: Int): AdjustableApp {
        if (!installedAppsAccess.hasAccess(applicationContext)) {
            AppLog.warn("Skipping package lookup for uid=$uid package=$packageName without installed-apps access")
            return unknownApp(packageName, uid)
        }
        try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            return AdjustableApp(
                packageName = packageName,
                label = info.loadLabel(packageManager).toString(),
                uid = uid,
                icon = info.loadIcon(packageManager),
                isSystemApp = info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0,
            )
        } catch (error: PackageManager.NameNotFoundException) {
            AppLog.warn("Active uid=$uid package=$packageName is no longer installed", error)
        }
        return unknownApp(packageName, uid)
    }

    private fun unknownApp(packageName: String, uid: Int): AdjustableApp {
        return AdjustableApp(
            packageName = packageName,
            label = applicationContext.getString(R.string.unknown_app, uid),
            uid = uid,
            icon = packageManager.defaultActivityIcon,
        )
    }

    private fun publishResult(result: SoundManProtocol.CommandResult) {
        val publicResult = HostCommandResult(
            commandId = result.commandId,
            uid = result.uid ?: -1,
            success = result.success,
            resultCode = result.resultCode,
            effectiveTarget = result.effectiveTarget,
        )
        mainHandler.post { resultObservers.forEach { it(publicResult) } }
    }

    private fun publishUnavailable() {
        publishDevices(AudioDeviceScan(emptyList(), AudioDeviceScanError.HOST_UNAVAILABLE))
        publish(ActiveMediaAppsState.Error(ActiveMediaAppsError.HOST_UNAVAILABLE))
    }

    private fun publishDevices(newScan: AudioDeviceScan) {
        deviceScan = newScan
        mainHandler.post { deviceObservers.forEach { it(newScan) } }
    }

    private fun publish(newState: ActiveMediaAppsState) {
        state = newState
        mainHandler.post { observers.forEach { it(newState) } }
    }

    private companion object {
        const val RECONNECT_BASE_DELAY_MS = 500L
        const val RECONNECT_MAX_DELAY_MS = 30_000L
        const val RECONNECT_MAX_SHIFT = 6
        const val SNAPSHOT_WATCHDOG_MS = 2_000L
        const val DEVICE_CHANGE_DEBOUNCE_MS = 200L
    }

    private fun postToWorker(label: String, action: () -> Unit): Boolean {
        val accepted = synchronized(workerDispatchLock) {
            if (closed) return false
            handler.post {
                if (!closed) action()
            }
        }
        if (!accepted && !closed) AppLog.error("HostPlaybackSource worker rejected $label")
        return accepted
    }

    private fun postDelayedToWorker(label: String, action: Runnable, delayMs: Long): Boolean {
        val accepted = synchronized(workerDispatchLock) {
            if (closed) return false
            handler.postDelayed({ if (!closed) action.run() }, delayMs)
        }
        if (!accepted && !closed) AppLog.error("HostPlaybackSource worker rejected delayed $label")
        return accepted
    }

    @Synchronized
    override fun close() {
        synchronized(workerDispatchLock) {
            if (closed) return
            closed = true
            handler.removeCallbacksAndMessages(null)
        }
        reconnectScheduled = false
        if (deviceCallbackRegistered) {
            try {
                applicationContext.getSystemService(classOf<AudioManager>())
                    ?.unregisterAudioDeviceCallback(deviceCallback)
            } catch (error: Throwable) {
                AppLog.error("[route] Failed to unregister AudioDeviceCallback", error)
            }
            deviceCallbackRegistered = false
        }
        bridge.close()
        connectExecutor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        observers.clear()
        deviceObservers.clear()
        resultObservers.clear()
        worker.quitSafely()
        try {
            worker.join(1_000L)
            if (worker.isAlive) AppLog.error("Host IPC worker did not stop within timeout")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            AppLog.error("Interrupted while stopping host IPC worker", error)
        }
    }
}
