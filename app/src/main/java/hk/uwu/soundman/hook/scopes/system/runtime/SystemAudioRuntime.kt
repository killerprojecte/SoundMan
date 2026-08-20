package hk.uwu.soundman.hook.scopes.system.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.RemoteCallbackList
import hk.uwu.soundman.hook.scopes.system.hidden.HiddenPlayer
import hk.uwu.soundman.hook.scopes.system.hidden.OutputDeviceMapper
import hk.uwu.soundman.hook.scopes.system.hidden.PlaybackProbe
import hk.uwu.soundman.hook.scopes.system.hidden.PlayerDeadException
import hk.uwu.soundman.hook.scopes.system.hidden.ProbedPlayback
import hk.uwu.soundman.hook.scopes.system.hidden.SystemMediaDeviceProbe
import hk.uwu.soundman.internal.ipc.ISoundManClientCallback
import hk.uwu.soundman.internal.ipc.ISoundManHostService
import hk.uwu.soundman.ipc.HostOfferPublisher
import hk.uwu.soundman.ipc.PreferredDeviceSync
import hk.uwu.soundman.ipc.SoundManProtocol
import hk.uwu.soundman.model.AppAudioRule
import hk.uwu.soundman.model.AudioDeviceIdentity
import hk.uwu.soundman.model.AudioOutputDevice
import hk.uwu.soundman.model.OutputTarget
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** system_server 音频业务宿主。HookHandle 由 Hook core 独占，本类只管理业务与 IPC 资源。 */
class SystemAudioRuntime(
    private val context: Context,
    private val outputDeviceMapper: OutputDeviceMapper,
    private val outputDeviceConsolidator: OutputDeviceConsolidator,
    private val playbackProbe: PlaybackProbe,
    private val playbackMerge: SnapshotPlaybackMerge,
    private val systemMediaDeviceProbe: SystemMediaDeviceProbe,
    private val log: (level: Int, message: String, throwable: Throwable?) -> Unit,
) {
    private val lock = Any()
    private val players = HashMap<Int, PlayerRecord>()
    private val rulesByUid = LinkedHashMap<Int, AppAudioRule>()
    /** callback 注册表与所有权索引必须由同一把业务锁保护，避免死亡回调观察到半完成注册。 */
    private val callbackOwners = HashMap<IBinder, Int>()
    private val callbacks = object : RemoteCallbackList<ISoundManClientCallback>() {
        override fun onCallbackDied(callback: ISoundManClientCallback, cookie: Any?) {
            synchronized(lock) {
                callbackOwners.remove(callback.asBinder())
            }
            log(LOG_INFO, "[ipc] client callback died uid=${cookie as? Int ?: -1}", null)
        }
    }
    private val callbackDispatchLock = Any()
    private val workerThread = HandlerThread("SoundMan.HostWorker").apply { start() }
    private val worker = Handler(workerThread.looper)
    private val pendingPlayerUpdates = AtomicInteger()
    private val ready = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val lastDuplicateSnapshotLogMillis = AtomicLong()
    private val lastPlayerQueueLogMillis = AtomicLong()
    private val lastPlayerProcessingLogMillis = AtomicLong()
    private val lastDeviceScanLogMillis = AtomicLong()
    private var snapshotRevision = 0L
    private var rulesRevision = 0L
    private var lastSnapshotSignature = ""

    val isSystemReady: Boolean
        get() = ready.get()

    private val hostService = object : ISoundManHostService.Stub() {
        override fun getProtocolVersion(): Int {
            authenticateBinderCaller("getProtocolVersion")
            requireAvailable("getProtocolVersion")
            return SoundManProtocol.VERSION
        }

        override fun registerClient(protocolVersion: Int, callback: ISoundManClientCallback?) {
            val uid = authenticateBinderCaller("registerClient")
            requireAvailable("registerClient")
            require(protocolVersion == SoundManProtocol.VERSION) {
                "Protocol version $protocolVersion is unsupported"
            }
            val requiredCallback = requireNotNull(callback) { "callback must not be null" }
            synchronized(lock) {
                check(callbacks.register(requiredCallback, uid)) { "callback is already registered or host is closed" }
                callbackOwners[requiredCallback.asBinder()] = uid
            }
            log(LOG_INFO, "[ipc] client registered uid=$uid", null)
            postCommand("registerClient") { sendSnapshot(playbackChanged = false, force = true) }
        }

        override fun unregisterClient(callback: ISoundManClientCallback?) {
            val uid = authenticateBinderCaller("unregisterClient")
            requireAvailable("unregisterClient")
            val requiredCallback = requireNotNull(callback) { "callback must not be null" }
            synchronized(lock) {
                val ownerUid = callbackOwners[requiredCallback.asBinder()]
                check(ownerUid == uid) { "callback is not owned by calling uid=$uid" }
                check(callbacks.unregister(requiredCallback)) { "callback was not registered" }
                callbackOwners.remove(requiredCallback.asBinder())
            }
            log(LOG_INFO, "[ipc] client unregistered uid=$uid", null)
        }

        override fun requestSnapshot(commandId: String?) {
            authenticateBinderCaller("requestSnapshot")
            val command = requireCommandId(commandId)
            postCommand("requestSnapshot") { sendSnapshot(playbackChanged = false, force = true) }
            log(LOG_DEBUG, "[ipc] snapshot requested command=${commandSummary(command)}", null)
        }

        override fun replaceRules(commandId: String?, revision: Long, rules: MutableList<Bundle>?) {
            authenticateBinderCaller("replaceRules")
            val command = requireCommandId(commandId)
            require(revision >= 0L) { "rules revision must be non-negative" }
            val copiedRules = requireNotNull(rules) { "rules must not be null" }.map(::Bundle)
            postCommand("replaceRules") { this@SystemAudioRuntime.applyReplaceRules(command, revision, copiedRules) }
        }

        override fun setVolume(commandId: String?, uid: Int, percent: Int) {
            authenticateBinderCaller("setVolume")
            val command = requireCommandId(commandId)
            require(uid >= 0) { "uid must be non-negative" }
            require(percent in 0..100) { "volume percent must be in 0..100" }
            postCommand("setVolume") { this@SystemAudioRuntime.applySetVolume(command, uid, percent) }
        }

        override fun setRoute(commandId: String?, uid: Int, target: Bundle?) {
            authenticateBinderCaller("setRoute")
            val command = requireCommandId(commandId)
            require(uid >= 0) { "uid must be non-negative" }
            val copiedTarget = Bundle(requireNotNull(target) { "target must not be null" })
            postCommand("setRoute") { this@SystemAudioRuntime.applySetRoute(command, uid, copiedTarget) }
        }
    }

    private val bootstrapReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (!isAvailable()) {
                log(LOG_WARN, "[ipc] ignored bootstrap for closed generation", null)
                return
            }
            val senderUid = sentFromUid
            val senderPackage =
                sentFromPackage
            if (senderUid < 0) {
                log(LOG_WARN, "[ipc] bootstrap sender UID unavailable", null)
            }
            try {
                HostOfferPublisher().offer(intent, hostService.asBinder())
                log(
                    LOG_INFO,
                    "[ipc] delivered host Binder senderUid=$senderUid senderPackage=$senderPackage",
                    null
                )
            } catch (throwable: Throwable) {
                log(LOG_ERROR, "[ipc] host offer failed senderUid=$senderUid", throwable)
            }
        }
    }

    fun onSystemReady() {
        if (!isAvailable()) return
        if (ready.compareAndSet(false, true)) {
            registerBootstrapReceiver()
            log(LOG_INFO, "AudioService systemReady observed; bootstrap enabled", null)
        } else if (!receiverRegistered.get()) {
            registerBootstrapReceiver()
        }
        sendSnapshot(playbackChanged = false, force = true)
    }

    fun onTrackPlayer(piid: Int, uid: Int, player: HiddenPlayer?) {
        postPlayerUpdate("trackPlayer") { recordTrackedPlayer(piid, uid, player) }
    }

    fun onPlayerEvent(piid: Int, state: Int) {
        postPlayerUpdate("playerEvent") { recordPlayerEvent(piid, state) }
    }

    fun onReleasePlayer(piid: Int) {
        postPlayerUpdate("releasePlayer") { recordReleasedPlayer(piid) }
    }

    fun close(reason: String): Boolean {
        if (!closed.compareAndSet(false, true)) return true
        var success = true
        notifyHostClosed(reason)
        callbacks.kill()
        synchronized(lock) {
            callbackOwners.clear()
        }
        if (!unregisterBootstrapReceiver()) success = false
        synchronized(lock) {
            players.values.forEach { unlinkPlayerDeathLocked(it) }
            players.clear()
            rulesByUid.clear()
        }
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        try {
            workerThread.join(WORKER_STOP_TIMEOUT_MILLIS)
            if (workerThread.isAlive) {
                success = false
                log(LOG_ERROR, "Worker did not stop within timeout during $reason", null)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            success = false
            log(LOG_ERROR, "Interrupted while stopping worker during $reason", error)
        }
        return success
    }

    private fun registerBootstrapReceiver() {
        check(ready.get()) { "bootstrap receiver cannot register before systemReady" }
        if (!receiverRegistered.compareAndSet(false, true)) return
        try {
            context.registerReceiver(
                bootstrapReceiver,
                IntentFilter(SoundManProtocol.ACTION_REQUEST_BINDER),
                null,
                worker,
                Context.RECEIVER_EXPORTED,
            )
            log(LOG_INFO, "[ipc] bootstrap receiver registered", null)
        } catch (throwable: Throwable) {
            receiverRegistered.set(false)
            log(LOG_ERROR, "[ipc] bootstrap receiver registration failed", throwable)
            throw throwable
        }
    }

    private fun unregisterBootstrapReceiver(): Boolean {
        if (!receiverRegistered.compareAndSet(true, false)) return true
        return try {
            context.unregisterReceiver(bootstrapReceiver)
            true
        } catch (throwable: Throwable) {
            log(LOG_ERROR, "[ipc] bootstrap receiver unregistration failed", throwable)
            false
        }
    }

    private fun authenticateBinderCaller(operation: String): Int {
        val uid = Binder.getCallingUid()
        log(LOG_DEBUG, "[ipc] accepted Binder call operation=$operation uid=$uid", null)
        return uid
    }

    private fun requireAvailable(operation: String) {
        check(isAvailable()) { "SoundMan host is closed: $operation" }
    }

    private fun isAvailable(): Boolean = !closed.get()

    private fun requireCommandId(commandId: String?): String = requireNotNull(commandId) {
        "commandId must not be null"
    }.also { require(it.isNotBlank()) { "commandId must not be blank" } }

    private fun postCommand(operation: String, command: () -> Unit) {
        requireAvailable(operation)
        val accepted = worker.post {
            if (!isAvailable()) return@post
            try {
                command()
            } catch (throwable: Throwable) {
                log(LOG_ERROR, "[ipc] command failed operation=$operation", throwable)
                notifyEvent(SoundManProtocol.Event.HostError(throwable.message ?: "$operation failed"))
            }
        }
        check(accepted) { "SoundMan worker rejected $operation" }
    }

    private fun postPlayerUpdate(operation: String, update: () -> Unit) {
        if (!isAvailable()) return
        val pending = pendingPlayerUpdates.incrementAndGet()
        val accepted = worker.post {
            try {
                if (isAvailable()) update()
                logRateLimited(lastPlayerProcessingLogMillis, "[player] processed $operation pending=${pendingPlayerUpdates.get()}")
            } finally {
                pendingPlayerUpdates.decrementAndGet()
            }
        }
        if (!accepted) {
            pendingPlayerUpdates.decrementAndGet()
            log(LOG_ERROR, "[player] worker rejected $operation", null)
        } else {
            logRateLimited(lastPlayerQueueLogMillis, "[player] queued $operation pending=$pending")
        }
    }

    private fun applyReplaceRules(commandId: String, revision: Long, bundles: List<Bundle>) {
        val decoded = SoundManProtocol.decodeRules(bundles)
        val snapshot = LinkedHashMap<Int, AppAudioRule>()
        decoded.sortedBy(AppAudioRule::packageName).forEach { rule -> snapshot.putIfAbsent(rule.uid, rule) }
        synchronized(lock) {
            rulesByUid.clear()
            rulesByUid.putAll(snapshot)
            rulesRevision = revision
            players.values.filter { it.state == PLAYER_STARTED }
                .forEach { applyPlayerVolumeLocked(it, rulesByUid[it.uid]) }
        }
        sendResult(
            SoundManProtocol.EVENT_RULES_RESULT,
            commandId,
            null,
            true,
            SoundManProtocol.RESULT_OK,
            null,
        )
        sendSnapshot(playbackChanged = true, force = true)
    }

    private fun applySetVolume(commandId: String, uid: Int, percent: Int) {
        val beforeCount: Int
        val afterCount: Int
        synchronized(lock) {
            val active = players.values.filter { it.uid == uid && it.state == PLAYER_STARTED }
            beforeCount = active.size
            active.forEach { applyPlayerVolumeLocked(it, null, percent / 100f) }
            afterCount = players.values.count { it.uid == uid && it.state == PLAYER_STARTED }
        }
        val success = afterCount > 0
        sendResult(
            SoundManProtocol.EVENT_VOLUME_RESULT,
            commandId,
            uid,
            success,
            if (success) SoundManProtocol.RESULT_OK else SoundManProtocol.RESULT_UID_NOT_ACTIVE,
            null,
        )
        if (beforeCount != afterCount && ready.get()) {
            sendSnapshot(playbackChanged = true, force = true)
        }
    }

    private fun applySetRoute(commandId: String, uid: Int, targetBundle: Bundle) {
        val target = SoundManProtocol.decodeTarget(targetBundle)
        synchronized(lock) {
            val existing = rulesByUid[uid]
            if (existing != null) {
                rulesByUid[uid] = existing.copy(
                    outputTarget = target,
                    followsSystemAfterDisconnect = false,
                )
            }
            val counts = playerCountsLocked(uid)
            log(
                LOG_INFO,
                "[route] setRoute command=${commandSummary(commandId)} uid=$uid " +
                        "${RouteDebug.describeTarget(target)} ${
                            RouteDebug.describePlayers(
                                counts.started,
                                counts.tracked
                            )
                        }",
                null,
            )
        }
        log(
            LOG_INFO,
            "[route] setRoute result command=${commandSummary(commandId)} uid=$uid " +
                "success=true code=${SoundManProtocol.RESULT_OK} effective=${RouteDebug.describeTarget(target)}",
            null,
        )
        sendResult(SoundManProtocol.EVENT_ROUTE_RESULT, commandId, uid, true, SoundManProtocol.RESULT_OK, target)
    }

    private fun recordTrackedPlayer(piid: Int, uid: Int, player: HiddenPlayer?) {
        synchronized(lock) {
            players[piid]?.let { old -> unlinkPlayerDeathLocked(old) }
            val recipient = linkPlayerDeathLocked(piid, player)
            players[piid] = PlayerRecord(uid, player, PLAYER_IDLE, recipient)
        }
        log(LOG_DEBUG, "[player] tracked piid=$piid uid=$uid hasIPlayer=${player != null}", null)
        if (ready.get()) sendSnapshot(playbackChanged = true)
    }

    private fun recordPlayerEvent(piid: Int, state: Int) {
        synchronized(lock) {
            val record = players[piid]
            if (record == null) {
                log(LOG_WARN, "[player] event for unknown piid=$piid state=$state", null)
            } else {
                record.state = state
                if (state == PLAYER_STARTED && ready.get()) {
                    val rule = rulesByUid[record.uid]
                    log(
                        LOG_INFO,
                        "[route] player STARTED piid=$piid uid=${record.uid} " +
                            "rule=${rule?.effectiveOutputTarget?.let(RouteDebug::describeTarget) ?: "none"}",
                        null,
                    )
                    applyPlayerVolumeLocked(record, rule)
                }
            }
        }
        if (ready.get()) sendSnapshot(playbackChanged = true)
    }

    private fun recordReleasedPlayer(piid: Int) {
        synchronized(lock) {
            val removed = players.remove(piid)
            if (removed == null) {
                log(LOG_WARN, "[player] release for unknown piid=$piid", null)
            } else {
                unlinkPlayerDeathLocked(removed)
                if (ready.get() && players.values.none { it.uid == removed.uid && it.state == PLAYER_STARTED }) {
                    val rule = rulesByUid[removed.uid]
                    log(
                        LOG_INFO,
                        "[route] last STARTED released piid=$piid uid=${removed.uid} " +
                                "rule=${rule?.effectiveOutputTarget?.let(RouteDebug::describeTarget) ?: "none"}",
                        null,
                    )
                }
            }
        }
        if (ready.get()) sendSnapshot(playbackChanged = true)
    }

    private fun applyPlayerVolumeLocked(record: PlayerRecord, rule: AppAudioRule?, override: Float? = null) {
        check(Thread.holdsLock(lock))
        val multiplier = override ?: rule?.multiplier ?: 1f
        try {
            record.player?.setVolume(multiplier)
                ?: log(LOG_WARN, "Player has no IPlayer uid=${record.uid}", null)
        } catch (dead: PlayerDeadException) {
            log(LOG_WARN, "[player] IPlayer dead during setVolume uid=${record.uid}", dead)
            removeDeadPlayerByUidLocked(record.uid, record)
        } catch (throwable: Throwable) {
            log(LOG_ERROR, "IPlayer.setVolume failed uid=${record.uid} multiplier=$multiplier", throwable)
        }
    }

    private fun playerCountsLocked(uid: Int): PlayerCounts {
        check(Thread.holdsLock(lock))
        var tracked = 0
        var started = 0
        players.values.forEach { record ->
            if (record.uid != uid) return@forEach
            tracked += 1
            if (record.state == PLAYER_STARTED) started += 1
        }
        return PlayerCounts(started, tracked)
    }

    private fun sendSnapshot(playbackChanged: Boolean, force: Boolean = false) {
        if (!ready.get() || callbacks.registeredCallbackCount == 0 || !isAvailable()) return
        val audioManager = context.getSystemService(AudioManager::class.java)
            ?: error("AudioManager unavailable in system_server")
        val probed = playbackProbe.probe(audioManager)
        val outputDevices = scanOutputDevices(audioManager)
        val systemMediaDevice = probeSystemMediaDevice(audioManager)
        val playback = synchronized(lock) {
            upsertProbedPlayersLocked(probed)
            val startedCounts = HashMap<Int, Int>()
            players.values.forEach { record ->
                if (record.state == PLAYER_STARTED) {
                    startedCounts[record.uid] = (startedCounts[record.uid] ?: 0) + 1
                }
            }
            val merged = playbackMerge.merge(startedCounts, probed.map(ProbedPlayback::uid).toSet())
            val entries = merged.mapNotNull { (uid, count) ->
                val packageName = context.packageManager.getPackagesForUid(uid).orEmpty().sorted().firstOrNull()
                if (packageName == null) {
                    log(LOG_WARN, "No package found for active uid=$uid", null)
                    null
                } else {
                    SoundManProtocol.PlaybackEntry(uid, packageName, count)
                }
            }
            log(
                LOG_INFO,
                "[snapshot] revision=${snapshotRevision + 1} tracked=${players.size} " +
                    "started=${startedCounts.values.sum()} probed=${probed.size} " +
                        "playback=${entries.size} devices=${outputDevices.size} " +
                        "systemDevice=${systemMediaDevice?.let { "${it.type.name}:${it.productName}" } ?: "null"}",
                null,
            )
            entries
        }
        val signature = playback.joinToString(";") { "${it.uid}:${it.packageName}:${it.count}" } +
                "|" + outputDevices.joinToString(";") { "${it.type}:${it.productName}:${it.candidates}" } +
                "|" + (systemMediaDevice?.let { "${it.type.name}:${it.productName}" } ?: "null")
        if (!force && signature == lastSnapshotSignature) {
            logDuplicateSnapshotSkip()
            return
        }
        lastSnapshotSignature = signature
        val event = SoundManProtocol.Event.SnapshotAvailable(
            SoundManProtocol.Snapshot(
                ++snapshotRevision,
                playback,
                outputDevices,
                systemMediaDevice
            ),
            playbackChanged,
        )
        notifyEvent(event)
    }

    private fun probeSystemMediaDevice(audioManager: AudioManager): AudioOutputDevice? {
        val spec = systemMediaDeviceProbe.probe(audioManager) ?: return null
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val matched = outputs.firstOrNull { device ->
            device.type == spec.publicType &&
                    PreferredDeviceSync.addressesMatch(device.address, spec.address)
        } ?: return null
        val productName = matched.productName?.toString().orEmpty()
        val type = PreferredDeviceSync.categoryOf(spec.publicType)
        val identity = AudioDeviceIdentity(matched.type, matched.address)
        return AudioOutputDevice(type, listOf(identity), productName)
    }

    private fun upsertProbedPlayersLocked(probed: List<ProbedPlayback>) {
        check(Thread.holdsLock(lock))
        probed.forEach { entry ->
            val piid = entry.piid ?: return@forEach
            val existing = players[piid]
            if (existing == null) {
                val recipient = linkPlayerDeathLocked(piid, entry.player)
                players[piid] = PlayerRecord(entry.uid, entry.player, PLAYER_STARTED, recipient)
                return@forEach
            }
            if (existing.uid != entry.uid) {
                log(
                    LOG_WARN,
                    "[snapshot] probed uid=${entry.uid} mismatches tracked uid=${existing.uid} piid=$piid",
                    null,
                )
            }
            val mergedPlayer = existing.player ?: entry.player
            if (existing.player == null && entry.player != null) {
                unlinkPlayerDeathLocked(existing)
                val recipient = linkPlayerDeathLocked(piid, mergedPlayer)
                players[piid] = PlayerRecord(existing.uid, mergedPlayer, PLAYER_STARTED, recipient)
            } else {
                players[piid] = PlayerRecord(
                    existing.uid,
                    mergedPlayer,
                    PLAYER_STARTED,
                    existing.deathRecipient
                )
            }
        }
    }

    private fun scanOutputDevices(audioManager: AudioManager): List<AudioOutputDevice> {
        return outputDeviceConsolidator.consolidate(
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { device ->
                val productName = device.productName?.toString().orEmpty()
                outputDeviceMapper.map(device.type, device.address, productName)
            },
        ).also {
            logRateLimited(lastDeviceScanLogMillis, "[snapshot] scanned output devices count=${it.size}")
        }
    }

    private fun sendResult(type: String, commandId: String, uid: Int?, success: Boolean, code: Int, target: OutputTarget?) {
        notifyEvent(
            SoundManProtocol.Event.ResultAvailable(
                type,
                SoundManProtocol.CommandResult(commandId, uid, success, code, null, target),
            ),
        )
    }

    private fun notifyHostClosed(reason: String) {
        notifyEvent(SoundManProtocol.Event.HostClosed(reason), allowClosed = true)
    }

    private fun notifyEvent(event: SoundManProtocol.Event, allowClosed: Boolean = false) {
        if ((!allowClosed && !isAvailable()) || callbacks.registeredCallbackCount == 0) return
        val encoded = SoundManProtocol.encodeEvent(event)
        synchronized(callbackDispatchLock) {
            val count = callbacks.beginBroadcast()
            try {
                for (index in 0 until count) {
                    try {
                        callbacks.getBroadcastItem(index).onEvent(encoded.type, Bundle(encoded.payload))
                    } catch (throwable: Throwable) {
                        log(LOG_ERROR, "[ipc] callback failed event=${encoded.type}", throwable)
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    private fun logDuplicateSnapshotSkip() {
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastDuplicateSnapshotLogMillis.get()
        if (now - previous >= DUPLICATE_LOG_INTERVAL_MILLIS && lastDuplicateSnapshotLogMillis.compareAndSet(previous, now)) {
            log(LOG_DEBUG, "[snapshot] duplicate skipped revision=$snapshotRevision", null)
        }
    }

    private fun logRateLimited(clock: AtomicLong, message: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = clock.get()
        if (now - previous >= PLAYER_LOG_INTERVAL_MILLIS && clock.compareAndSet(previous, now)) {
            log(LOG_DEBUG, message, null)
        }
    }

    private fun commandSummary(commandId: String): String = commandId.take(COMMAND_SUMMARY_LENGTH)

    /**
     * 为播放器创建死亡监听器。
     *
     * 动机：当播放器所在进程死亡时，binder 代理变为 dead。
     * DeathRecipient 在进程死亡的瞬间被回调，不必等到下次调用才发现。
     * 回调在 worker 线程上执行，因为 `linkToDeath` 的回调由 binder 库在任意线程触发，
     * 我们 post 到 worker 上以避免与业务锁竞争。
     *
     * @param piid 播放器 ID，用于在 map 中定位记录
     * @return 死亡监听器；如果无法创建则返回 null
     */
    private fun createPlayerDeathRecipient(piid: Int): IBinder.DeathRecipient {
        return IBinder.DeathRecipient {
            worker.post {
                if (!isAvailable()) return@post
                var removed = false
                synchronized(lock) {
                    val record = players[piid]
                    if (record != null) {
                        players.remove(piid)
                        removed = true
                        log(
                            LOG_INFO,
                            "[player] binder died piid=$piid uid=${record.uid} state=${record.state}",
                            null,
                        )
                    }
                }
                if (removed && ready.get()) {
                    sendSnapshot(playbackChanged = true, force = true)
                }
            }
        }
    }

    /**
     * 将播放器记录链接到 binder 死亡监听。
     *
     * 动机：在 trackPlayer 和 upsertProbed 时调用，确保进程死亡后能主动清理。
     *
     * @param piid 播放器 ID
     * @param player 播放器包装；为 null 时跳过
     * @return 死亡监听器；链接失败或无 binder 时返回 null
     */
    private fun linkPlayerDeathLocked(piid: Int, player: HiddenPlayer?): IBinder.DeathRecipient? {
        check(Thread.holdsLock(lock))
        val binder = player?.binder ?: return null
        val recipient = createPlayerDeathRecipient(piid)
        if (recipient == null) return null
        try {
            binder.linkToDeath(recipient, 0)
        } catch (throwable: Throwable) {
            log(LOG_WARN, "[player] linkToDeath failed piid=$piid", throwable)
            return null
        }
        return recipient
    }

    /**
     * 解除播放器的 binder 死亡监听。
     *
     * 动机：在 releasePlayer 和替换播放器时调用，避免回调悬空。
     *
     * @param record 被移除的播放器记录
     */
    private fun unlinkPlayerDeathLocked(record: PlayerRecord) {
        check(Thread.holdsLock(lock))
        val recipient = record.deathRecipient ?: return
        val binder = record.player?.binder ?: return
        try {
            binder.unlinkToDeath(recipient, 0)
        } catch (throwable: Throwable) {
            log(LOG_WARN, "[player] unlinkToDeath failed piid uid=${record.uid}", throwable)
        }
    }

    /**
     * 清理已死亡的播放器记录并发送快照。
     *
     * 动机：在 setVolume 或 kick 操作中收到 [PlayerDeadException] 时调用，
     * 将死亡的播放器从 map 中移除，确保不再被调用且不再出现在音量面板。
     *
     * @param piid 播放器 ID
     * @return 是否实际移除了记录
     */
    private fun removeDeadPlayerLocked(piid: Int): Boolean {
        check(Thread.holdsLock(lock))
        val removed = players.remove(piid)
        if (removed != null) {
            unlinkPlayerDeathLocked(removed)
            log(LOG_INFO, "[player] removed dead player piid=$piid uid=${removed.uid}", null)
        }
        return removed != null
    }

    /**
     * 从 map 中清理指定的死亡播放器记录。
     *
     * 动机：[applyPlayerVolumeLocked] 在持有锁的情况下收到 [PlayerDeadException]，
     * 需要通过 record 引用定位并移除该条目。由于 [PlayerRecord] 是 data class，
     * 直接 `players.entries.removeIf` 匹配引用即可。
     *
     * @param uid 播放器 uid（仅用于日志）
     * @param record 死亡的播放器记录
     */
    private fun removeDeadPlayerByUidLocked(uid: Int, record: PlayerRecord) {
        check(Thread.holdsLock(lock))
        val removed = players.entries.find { it.value === record }
        if (removed != null) {
            unlinkPlayerDeathLocked(record)
            players.remove(removed.key)
            log(LOG_INFO, "[player] removed dead player piid=${removed.key} uid=$uid", null)
        }
    }

    private data class PlayerRecord(
        val uid: Int,
        val player: HiddenPlayer?,
        var state: Int,
        /** 链接到该播放器 binder 的死亡监听器；player 为 null 或无 binder 时为 null。 */
        val deathRecipient: IBinder.DeathRecipient? = null,
    )
    private data class PlayerCounts(val started: Int, val tracked: Int)

    companion object {
        const val LOG_DEBUG = 0
        const val LOG_INFO = 1
        const val LOG_WARN = 2
        const val LOG_ERROR = 3
        private const val PLAYER_IDLE = 0
        private const val PLAYER_STARTED = 2
        private const val COMMAND_SUMMARY_LENGTH = 8
        private const val WORKER_STOP_TIMEOUT_MILLIS = 1_000L
        private const val DUPLICATE_LOG_INTERVAL_MILLIS = 5_000L
        private const val PLAYER_LOG_INTERVAL_MILLIS = 2_000L
    }
}
