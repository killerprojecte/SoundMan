package hk.uwu.soundman.ipc

import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import hk.uwu.soundman.ipc.SoundManProtocol.MAX_ROUTE_CANDIDATES
import hk.uwu.soundman.ipc.SoundManProtocol.VERSION
import hk.uwu.soundman.ipc.SoundManProtocol.requestBinderIntent
import hk.uwu.soundman.model.AppAudioRule
import hk.uwu.soundman.model.AudioDeviceIdentity
import hk.uwu.soundman.model.AudioOutputDevice
import hk.uwu.soundman.model.OutputDeviceType
import hk.uwu.soundman.model.OutputTarget

/** Versioned Binder protocol shared by the app and the system_server host. */
object SoundManProtocol {
    const val VERSION = 4
    const val PACKAGE_NAME = "hk.uwu.soundman"
    const val HOST_PACKAGE_NAME = "android"
    const val CONTROL_PERMISSION = "$PACKAGE_NAME.permission.CONTROL_HOST"

    /** The only broadcast in protocol v3. It carries a oneway offer mailbox Binder, never business data. */
    const val ACTION_REQUEST_BINDER = "$PACKAGE_NAME.ipc.REQUEST_BINDER"
    const val EXTRA_PROTOCOL_VERSION = "protocolVersion"
    const val EXTRA_HOST_OFFER = "hostOffer"

    const val EVENT_SNAPSHOT = "snapshot"
    const val EVENT_PLAYBACK_CHANGED = "playback_changed"
    const val EVENT_ROUTE_RESULT = "route_result"
    const val EVENT_VOLUME_RESULT = "volume_result"
    const val EVENT_RULES_RESULT = "rules_result"
    const val EVENT_HOST_ERROR = "host_error"
    const val EVENT_HOST_CLOSED = "host_closed"

    const val RESULT_OK = 0
    const val RESULT_INVALID_REQUEST = 1
    const val RESULT_UID_NOT_ACTIVE = 2
    const val RESULT_ALL_CANDIDATES_FAILED = 3
    const val RESULT_INTERNAL_ERROR = 4
    const val RESULT_AFFINITY_REMOVE_FAILED = 5

    private const val KEY_COMMAND_ID = "commandId"
    private const val KEY_REVISION = "revision"
    private const val KEY_UID = "uid"
    private const val KEY_PACKAGE_NAME = "packageName"
    private const val KEY_VOLUME_PERCENT = "volumePercent"
    private const val KEY_FALLBACK = "fallback"
    private const val KEY_SUCCESS = "success"
    private const val KEY_RESULT_CODE = "resultCode"
    private const val KEY_MESSAGE = "message"
    private const val KEY_EFFECTIVE_TARGET = "effectiveTarget"
    private const val KEY_RULES = "rules"
    private const val KEY_PLAYBACK_UIDS = "playbackUids"
    private const val KEY_PLAYBACK_PACKAGES = "playbackPackages"
    private const val KEY_PLAYBACK_COUNTS = "playbackCounts"
    private const val KEY_OUTPUT_DEVICES = "outputDevices"
    private const val KEY_CANDIDATE_TYPES = "candidateTypes"
    private const val KEY_CANDIDATE_ADDRESSES = "candidateAddresses"
    private const val KEY_DEVICE_CATEGORY = "deviceCategory"
    private const val KEY_DEVICE_NAME = "deviceName"
    private const val KEY_FOLLOW_SYSTEM = "followSystem"
    private const val KEY_SYSTEM_MEDIA_DEVICE = "systemMediaDevice"
    private const val MAX_ROUTE_CANDIDATES = 32

    data class PlaybackEntry(val uid: Int, val packageName: String, val count: Int) {
        init {
            require(uid >= 0) { "playback uid must be non-negative" }
            require(packageName.isNotBlank()) { "playback packageName must not be blank" }
            require(count > 0) { "playback count must be positive" }
        }
    }

    data class Snapshot(
        val revision: Long,
        val playback: List<PlaybackEntry>,
        val outputDevices: List<AudioOutputDevice>,
        val systemMediaDevice: AudioOutputDevice? = null,
    ) {
        init {
            require(revision >= 0L) { "snapshot revision must not be negative" }
        }
    }

    data class CommandResult(
        val commandId: String,
        val uid: Int?,
        val success: Boolean,
        val resultCode: Int,
        val message: String?,
        val effectiveTarget: OutputTarget?,
    ) {
        init {
            require(commandId.isNotBlank()) { "commandId must not be blank" }
            require(uid == null || uid >= 0) { "result uid must be non-negative when present" }
            require(resultCode in RESULT_OK..RESULT_AFFINITY_REMOVE_FAILED) { "unknown resultCode: $resultCode" }
            require(success == (resultCode == RESULT_OK)) { "success must agree with resultCode" }
        }
    }

    sealed interface Event {
        data class SnapshotAvailable(val snapshot: Snapshot, val playbackChanged: Boolean) : Event
        data class ResultAvailable(val type: String, val result: CommandResult) : Event
        data class HostError(val message: String) : Event
        data class HostClosed(val reason: String) : Event
    }

    data class EncodedEvent(val type: String, val payload: Bundle)

    /**
     * REQUEST_BINDER 广播要投递的 Host 会话。
     *
     * 动机：握手只交换 version + offer Binder，不能再套娃 bootstrap payload。
     *
     * @param protocolVersion 协议版本，必须等于 [VERSION]
     * @param offerBinder App 侧 oneway 邮箱 Binder
     */
    data class RequestBinder(
        val protocolVersion: Int,
        val offerBinder: IBinder,
    )

    /**
     * 编码 REQUEST_BINDER extras。
     *
     * 动机：JVM 单测里 `Intent.putExtra(IBinder)` 不在公开 SDK，`Bundle` stub 也不存值，
     * extras 契约必须是可直接断言的纯函数。生产路径再用 [requestBinderIntent] 写入 Intent extras。
     */
    fun requestBinderExtras(offerBinder: IBinder): Map<String, Any> = linkedMapOf(
        EXTRA_PROTOCOL_VERSION to VERSION,
        EXTRA_HOST_OFFER to offerBinder,
    )

    /**
     * 从 extras 解析 REQUEST_BINDER。缺字段或版本不匹配立即失败，消息带字段名或版本号。
     */
    fun decodeRequestBinder(extras: Map<String, Any?>): RequestBinder {
        if (!extras.containsKey(EXTRA_PROTOCOL_VERSION)) {
            throw IllegalArgumentException("missing extra: $EXTRA_PROTOCOL_VERSION")
        }
        val version = extras[EXTRA_PROTOCOL_VERSION] as? Int
            ?: throw IllegalArgumentException("invalid extra: $EXTRA_PROTOCOL_VERSION")
        require(version == VERSION) { "protocol version mismatch: $version != $VERSION" }
        if (!extras.containsKey(EXTRA_HOST_OFFER)) {
            throw IllegalArgumentException("missing extra: $EXTRA_HOST_OFFER")
        }
        val offerBinder = extras[EXTRA_HOST_OFFER] as? IBinder
            ?: throw IllegalArgumentException("null extra: $EXTRA_HOST_OFFER")
        return RequestBinder(version, offerBinder)
    }

    /**
     * 从广播 Intent 解析 REQUEST_BINDER。
     *
     * 动机：Host 只读 Intent 自己的 extras，不再解一层 payload Bundle。
     */
    fun decodeRequestBinder(intent: Intent): RequestBinder = decodeRequestBinder(readRequestBinderValues(intent))

    fun requestBinderIntent(offerBinder: IBinder): Intent {
        val extras = Bundle()
        val encoded = requestBinderExtras(offerBinder)
        extras.putInt(EXTRA_PROTOCOL_VERSION, encoded.getValue(EXTRA_PROTOCOL_VERSION) as Int)
        extras.putBinder(EXTRA_HOST_OFFER, encoded.getValue(EXTRA_HOST_OFFER) as IBinder)
        return Intent(ACTION_REQUEST_BINDER)
            .setPackage(HOST_PACKAGE_NAME)
            .putExtras(extras)
    }

    private fun readRequestBinderValues(intent: Intent): Map<String, Any?> {
        val extras = intent.extras ?: return emptyMap()
        val values = LinkedHashMap<String, Any?>()
        if (extras.containsKey(EXTRA_PROTOCOL_VERSION)) {
            values[EXTRA_PROTOCOL_VERSION] = extras.getInt(EXTRA_PROTOCOL_VERSION)
        }
        if (extras.containsKey(EXTRA_HOST_OFFER)) {
            values[EXTRA_HOST_OFFER] = extras.getBinder(EXTRA_HOST_OFFER)
        }
        return values
    }

    fun encodeRule(rule: AppAudioRule): Bundle = Bundle().apply {
        putInt(KEY_UID, rule.uid)
        putString(KEY_PACKAGE_NAME, rule.packageName)
        putInt(KEY_VOLUME_PERCENT, rule.volumePercent)
        putLong(KEY_REVISION, rule.revision)
        putBoolean(KEY_FALLBACK, rule.followsSystemAfterDisconnect)
        putBundle(KEY_EFFECTIVE_TARGET, encodeTarget(rule.outputTarget))
    }

    fun decodeRule(bundle: Bundle): AppAudioRule = AppAudioRule(
        packageName = bundle.requiredString(KEY_PACKAGE_NAME),
        uid = bundle.requiredInt(KEY_UID).also { require(it >= 0) { "rule uid must be non-negative" } },
        volumePercent = bundle.requiredInt(KEY_VOLUME_PERCENT).also {
            require(it in 0..100) { "volumePercent must be in 0..100" }
        },
        outputTarget = decodeTarget(bundle.requiredBundle(KEY_EFFECTIVE_TARGET)),
        revision = bundle.requiredLong(KEY_REVISION).also { require(it >= 0L) { "rule revision must not be negative" } },
        followsSystemAfterDisconnect = bundle.requiredBoolean(KEY_FALLBACK),
    )

    fun encodeRules(rules: List<AppAudioRule>): List<Bundle> = rules.map(::encodeRule)

    fun decodeRules(rules: List<Bundle>): List<AppAudioRule> = rules.map(::decodeRule).also { decoded ->
        require(decoded.map(AppAudioRule::packageName).distinct().size == decoded.size) {
            "rules contain duplicate package names"
        }
    }

    fun encodeTarget(target: OutputTarget): Bundle = Bundle().apply {
        when (target) {
            OutputTarget.FollowSystem -> putBoolean(KEY_FOLLOW_SYSTEM, true)
            is OutputTarget.Device -> {
                putBoolean(KEY_FOLLOW_SYSTEM, false)
                putString(KEY_DEVICE_CATEGORY, target.type.name)
                putString(KEY_DEVICE_NAME, target.productName)
                putCandidates(target.candidates)
            }
        }
    }

    fun decodeTarget(bundle: Bundle): OutputTarget {
        val followsSystem = bundle.requiredBoolean(KEY_FOLLOW_SYSTEM)
        if (followsSystem) {
            require(bundle.keySet() == setOf(KEY_FOLLOW_SYSTEM)) { "follow-system target contains device fields" }
            return OutputTarget.FollowSystem
        }
        return OutputTarget.Device(
            type = decodeDeviceType(bundle.requiredString(KEY_DEVICE_CATEGORY)),
            candidates = bundle.requiredCandidates(),
            productName = bundle.requiredString(KEY_DEVICE_NAME),
        )
    }

    fun encodeDevice(device: AudioOutputDevice): Bundle = Bundle().apply {
        putString(KEY_DEVICE_CATEGORY, device.type.name)
        putString(KEY_DEVICE_NAME, device.productName)
        putCandidates(device.candidates)
    }

    fun encodeTargetIdentity(target: OutputTarget): String = when (target) {
        OutputTarget.FollowSystem -> "follow-system"
        is OutputTarget.Device -> "${target.type.name}:${target.productName}:" +
                target.candidates.joinToString(";") { "${it.internalType}@${it.address}" }
    }

    fun encodeDeviceIdentity(device: AudioOutputDevice): String =
        "${device.type.name}:${device.productName}:" +
                device.candidates.joinToString(";") { "${it.internalType}@${it.address}" }

    fun decodeDevice(bundle: Bundle): AudioOutputDevice = AudioOutputDevice(
        type = decodeDeviceType(bundle.requiredString(KEY_DEVICE_CATEGORY)),
        candidates = bundle.requiredCandidates(),
        productName = bundle.requiredString(KEY_DEVICE_NAME),
    )

    /**
     * candidateTypes / candidateAddresses 的纯数据形态。
     *
     * 动机：JVM 单测里 Bundle stub 不存值，设备身份编解码必须能脱离 Bundle 往返断言。
     *
     * @param types AudioSystem internal type，与 [addresses] 等长
     * @param addresses 设备地址；内置扬声器/听筒合法为 ""
     */
    class CandidateArrays(
        val types: IntArray,
        val addresses: Array<String>,
    )

    /**
     * 把设备身份编码成等长的 type/address 数组。
     *
     * 动机：内置扬声器/听筒 address 为 ""，编码必须原样保留，不能当成缺失。
     *
     * @param candidates 至少 1 个、至多 [MAX_ROUTE_CANDIDATES] 个身份
     */
    fun candidateArraysFromIdentities(candidates: List<AudioDeviceIdentity>): CandidateArrays {
        require(candidates.size in 1..MAX_ROUTE_CANDIDATES) { "invalid route candidate count: ${candidates.size}" }
        return CandidateArrays(
            types = candidates.map(AudioDeviceIdentity::internalType).toIntArray(),
            addresses = candidates.map(AudioDeviceIdentity::address).toTypedArray(),
        )
    }

    /**
     * 从 candidateTypes / candidateAddresses 还原设备身份。
     *
     * 动机：Host 对内置扬声器/听筒会发出 address=""；不能走会拒绝 blank 的包名数组校验。
     * 缺数组或 null 元素立即失败，消息带字段名。空字符串地址合法。
     *
     * @param types AudioSystem internal type 数组
     * @param addresses 与 types 等长；允许 ""，禁止 null 数组与 null 元素
     */
    fun identitiesFromCandidateArrays(
        types: IntArray,
        addresses: Array<out String?>?,
    ): List<AudioDeviceIdentity> {
        requireNotNull(addresses) { "null StringArray field: $KEY_CANDIDATE_ADDRESSES" }
        require(types.size == addresses.size) { "route candidate arrays have different lengths" }
        require(types.size in 1..MAX_ROUTE_CANDIDATES) { "invalid route candidate count: ${types.size}" }
        return types.indices.map { index ->
            val address = addresses[index]
            require(address != null) { "StringArray field contains null value: $KEY_CANDIDATE_ADDRESSES" }
            AudioDeviceIdentity(types[index], address)
        }
    }

    fun encodeSnapshot(snapshot: Snapshot): Bundle = versionedBundle().apply {
        putLong(KEY_REVISION, snapshot.revision)
        putIntArray(KEY_PLAYBACK_UIDS, snapshot.playback.map(PlaybackEntry::uid).toIntArray())
        putStringArray(KEY_PLAYBACK_PACKAGES, snapshot.playback.map(PlaybackEntry::packageName).toTypedArray())
        putIntArray(KEY_PLAYBACK_COUNTS, snapshot.playback.map(PlaybackEntry::count).toIntArray())
        putParcelableArrayList(KEY_OUTPUT_DEVICES, ArrayList(snapshot.outputDevices.map(::encodeDevice)))
        if (snapshot.systemMediaDevice != null) {
            putBundle(KEY_SYSTEM_MEDIA_DEVICE, encodeDevice(snapshot.systemMediaDevice))
        }
    }

    fun decodeSnapshot(bundle: Bundle): Snapshot {
        bundle.requireVersion()
        val uids = bundle.requiredIntArray(KEY_PLAYBACK_UIDS)
        val packages = bundle.requiredStringArray(KEY_PLAYBACK_PACKAGES)
        val counts = bundle.requiredIntArray(KEY_PLAYBACK_COUNTS)
        require(uids.size == packages.size && uids.size == counts.size) { "playback arrays have different lengths" }
        val devices = bundle.requiredBundleList(KEY_OUTPUT_DEVICES).map(::decodeDevice)
        val systemDevice = bundle.getBundle(KEY_SYSTEM_MEDIA_DEVICE)?.let(::decodeDevice)
        return Snapshot(
            revision = bundle.requiredLong(KEY_REVISION),
            playback = uids.indices.map { PlaybackEntry(uids[it], packages[it], counts[it]) },
            outputDevices = devices,
            systemMediaDevice = systemDevice,
        )
    }

    fun encodeResult(result: CommandResult): Bundle = versionedBundle().apply {
        putString(KEY_COMMAND_ID, result.commandId)
        if (result.uid != null) putInt(KEY_UID, result.uid)
        putBoolean(KEY_SUCCESS, result.success)
        putInt(KEY_RESULT_CODE, result.resultCode)
        if (result.message != null) putString(KEY_MESSAGE, result.message)
        if (result.effectiveTarget != null) putBundle(KEY_EFFECTIVE_TARGET, encodeTarget(result.effectiveTarget))
    }

    fun decodeResult(bundle: Bundle): CommandResult {
        bundle.requireVersion()
        return CommandResult(
            commandId = bundle.requiredString(KEY_COMMAND_ID),
            uid = if (bundle.containsKey(KEY_UID)) bundle.requiredInt(KEY_UID) else null,
            success = bundle.requiredBoolean(KEY_SUCCESS),
            resultCode = bundle.requiredInt(KEY_RESULT_CODE),
            message = bundle.getString(KEY_MESSAGE),
            effectiveTarget = bundle.getBundle(KEY_EFFECTIVE_TARGET)?.let(::decodeTarget),
        )
    }

    fun encodeEvent(event: Event): EncodedEvent = when (event) {
        is Event.SnapshotAvailable -> EncodedEvent(
            if (event.playbackChanged) EVENT_PLAYBACK_CHANGED else EVENT_SNAPSHOT,
            encodeSnapshot(event.snapshot),
        )
        is Event.ResultAvailable -> {
            require(event.type in resultEventTypes) { "unsupported result event: ${event.type}" }
            EncodedEvent(event.type, encodeResult(event.result))
        }
        is Event.HostError -> EncodedEvent(EVENT_HOST_ERROR, versionedBundle().apply {
            putString(KEY_MESSAGE, event.message.requireNotBlank("host error message"))
        })
        is Event.HostClosed -> EncodedEvent(EVENT_HOST_CLOSED, versionedBundle().apply {
            putString(KEY_MESSAGE, event.reason.requireNotBlank("host closed reason"))
        })
    }

    fun decodeEvent(type: String, payload: Bundle): Event {
        payload.requireVersion()
        return when (type) {
            EVENT_SNAPSHOT -> Event.SnapshotAvailable(decodeSnapshot(payload), playbackChanged = false)
            EVENT_PLAYBACK_CHANGED -> Event.SnapshotAvailable(decodeSnapshot(payload), playbackChanged = true)
            in resultEventTypes -> Event.ResultAvailable(type, decodeResult(payload))
            EVENT_HOST_ERROR -> Event.HostError(payload.requiredString(KEY_MESSAGE))
            EVENT_HOST_CLOSED -> Event.HostClosed(payload.requiredString(KEY_MESSAGE))
            else -> error("unknown SoundMan event: $type")
        }
    }

    private val resultEventTypes = setOf(EVENT_ROUTE_RESULT, EVENT_VOLUME_RESULT, EVENT_RULES_RESULT)

    private fun versionedBundle(): Bundle = Bundle().apply { putInt(EXTRA_PROTOCOL_VERSION, VERSION) }

    private fun Bundle.requireVersion() {
        require(requiredInt(EXTRA_PROTOCOL_VERSION) == VERSION) {
            "protocol version mismatch: ${getInt(EXTRA_PROTOCOL_VERSION)} != $VERSION"
        }
    }

    private fun Bundle.putCandidates(candidates: List<AudioDeviceIdentity>) {
        val encoded = candidateArraysFromIdentities(candidates)
        putIntArray(KEY_CANDIDATE_TYPES, encoded.types)
        putStringArray(KEY_CANDIDATE_ADDRESSES, encoded.addresses)
    }

    private fun Bundle.requiredCandidates(): List<AudioDeviceIdentity> {
        val types = requiredIntArray(KEY_CANDIDATE_TYPES)
        require(containsKey(KEY_CANDIDATE_ADDRESSES)) { "missing StringArray field: $KEY_CANDIDATE_ADDRESSES" }
        return identitiesFromCandidateArrays(types, getStringArray(KEY_CANDIDATE_ADDRESSES))
    }

    private fun decodeDeviceType(value: String): OutputDeviceType = try {
        OutputDeviceType.valueOf(value)
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("unknown output device type: $value", error)
    }

    private fun Bundle.requiredString(key: String): String {
        require(containsKey(key)) { "missing String field: $key" }
        return requireNotNull(getString(key)) { "null String field: $key" }
            .requireNotBlank(key)
    }

    private fun String.requireNotBlank(label: String): String = also {
        require(it.isNotBlank()) { "$label must not be blank" }
    }

    private fun Bundle.requiredInt(key: String): Int {
        require(containsKey(key)) { "missing Int field: $key" }
        return getInt(key)
    }

    private fun Bundle.requiredLong(key: String): Long {
        require(containsKey(key)) { "missing Long field: $key" }
        return getLong(key)
    }

    private fun Bundle.requiredBoolean(key: String): Boolean {
        require(containsKey(key)) { "missing Boolean field: $key" }
        return getBoolean(key)
    }

    private fun Bundle.requiredBundle(key: String): Bundle {
        require(containsKey(key)) { "missing Bundle field: $key" }
        return requireNotNull(getBundle(key)) { "null Bundle field: $key" }
    }

    private fun Bundle.requiredIntArray(key: String): IntArray {
        require(containsKey(key)) { "missing IntArray field: $key" }
        return requireNotNull(getIntArray(key)) { "null IntArray field: $key" }
    }

    private fun Bundle.requiredStringArray(key: String): Array<String> {
        require(containsKey(key)) { "missing StringArray field: $key" }
        return requireNotNull(getStringArray(key)) { "null StringArray field: $key" }.also { values ->
            require(values.none(String::isBlank)) { "StringArray field contains blank value: $key" }
        }
    }

    private fun Bundle.requiredBundleList(key: String): List<Bundle> {
        require(containsKey(key)) { "missing Bundle list field: $key" }
        return requireNotNull(getParcelableArrayList(key, Bundle::class.java)) { "null Bundle list field: $key" }
    }
}
