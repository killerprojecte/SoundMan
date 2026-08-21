package hk.uwu.soundman.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.os.Process
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import hk.uwu.soundman.ipc.PreferredDeviceSync
import hk.uwu.soundman.ipc.SoundManProtocol
import hk.uwu.soundman.log.AppLog
import hk.uwu.soundman.model.AppAudioRule
import hk.uwu.soundman.model.AudioOutputDevice
import hk.uwu.soundman.model.OutputTarget
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Provider 暴露给 SystemUI 独立页的播放连接状态。 */
enum class PanelPlaybackStatus { CONNECTING, AVAILABLE, HOST_UNAVAILABLE }

data class PanelPlaybackRow(
    val packageName: String,
    val uid: Int,
    val volumePercent: Int,
    val outputTarget: OutputTarget,
    val followsSystemAfterDisconnect: Boolean = false,
    val label: String? = null,
    val iconPng: ByteArray? = null,
    val isSystemApp: Boolean = false,
) {
    init {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(uid >= 0) { "uid must be non-negative" }
        require(volumePercent in 0..100) { "volumePercent must be in 0..100" }
        require(label == null || label.isNotBlank()) { "label must be null or non-blank" }
        require(iconPng == null || iconPng.isNotEmpty()) { "iconPng must be null or non-empty" }
        require(!followsSystemAfterDisconnect || outputTarget is OutputTarget.Device) {
            "disconnect fallback requires a fixed device target"
        }
    }

    fun asRule(): AppAudioRule = AppAudioRule(
        packageName = packageName,
        uid = uid,
        volumePercent = volumePercent,
        outputTarget = outputTarget,
        revision = 0L,
        followsSystemAfterDisconnect = followsSystemAfterDisconnect,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PanelPlaybackRow

        if (uid != other.uid) return false
        if (volumePercent != other.volumePercent) return false
        if (followsSystemAfterDisconnect != other.followsSystemAfterDisconnect) return false
        if (packageName != other.packageName) return false
        if (outputTarget != other.outputTarget) return false
        if (label != other.label) return false
        if (!iconPng.contentEquals(other.iconPng)) return false
        if (isSystemApp != other.isSystemApp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = uid
        result = 31 * result + volumePercent
        result = 31 * result + followsSystemAfterDisconnect.hashCode()
        result = 31 * result + packageName.hashCode()
        result = 31 * result + outputTarget.hashCode()
        result = 31 * result + (label?.hashCode() ?: 0)
        result = 31 * result + (iconPng?.contentHashCode() ?: 0)
        result = 31 * result + isSystemApp.hashCode()
        return result
    }
}

data class PanelPlaybackSnapshot(
    val status: PanelPlaybackStatus,
    val rows: List<PanelPlaybackRow>,
    val devices: List<AudioOutputDevice>,
)

object RuleStoreBridgeContract {
    const val AUTHORITY = "hk.uwu.soundman.rule-store"
    const val METHOD_READ_ALL = "readAll"
    const val METHOD_READ_OR_DEFAULT = "readOrDefault"
    const val METHOD_SAVE = "save"
    const val METHOD_UPDATE_VOLUME = "updateVolume"
    const val METHOD_FALLBACK_TO_SYSTEM = "fallbackToSystem"
    const val METHOD_REVISION = "revision"
    const val METHOD_PANEL_SNAPSHOT = "panelSnapshot"
    const val METHOD_PANEL_SET_VOLUME = "panelSetVolume"
    const val METHOD_PANEL_SET_ROUTE = "panelSetRoute"
    const val KEY_RULE = "rule"
    const val KEY_RULES = "rules"
    const val KEY_PANEL_ROWS = "panelRows"
    const val KEY_PANEL_DEVICES = "panelDevices"
    const val KEY_PANEL_STATUS = "panelStatus"
    const val KEY_FOLLOWS_SYSTEM_AFTER_DISCONNECT = "followsSystemAfterDisconnect"
    const val KEY_PACKAGE_NAME = "packageName"
    const val KEY_LABEL = "label"
    const val KEY_ICON_PNG = "iconPng"
    const val KEY_UID = "uid"
    const val KEY_VOLUME_PERCENT = "volumePercent"
    const val KEY_OUTPUT_TARGET = "outputTarget"
    const val KEY_REVISION = "revision"
    const val KEY_IS_SYSTEM_APP = "isSystemApp"
    val URI: Uri = "content://$AUTHORITY".toUri()
}

/**
 * 模块进程中的规则桥接 Provider。
 *
 * SystemUI 注入页不能直接读取模块私有 SharedPreferences，因此通过受调用 UID 校验的 call 协议执行真实 RuleStore 操作。
 */
class RuleStoreBridgeProvider : ContentProvider() {
    private lateinit var store: RuleStore
    private lateinit var playbackSource: HostPlaybackSource

    @Volatile
    private var panelSnapshot =
        PanelPlaybackSnapshot(PanelPlaybackStatus.CONNECTING, emptyList(), emptyList())

    override fun onCreate(): Boolean {
        val context = context ?: error("RuleStoreBridgeProvider has no context")
        store = SharedPreferencesRuleStore(
            context.getSharedPreferences(
                RULE_PREFERENCES_NAME,
                Context.MODE_PRIVATE
            )
        )
        playbackSource = HostPlaybackSource(
            context,
            store,
            InstalledAppsAccess(PermissionCatalog(context)),
        )
        playbackSource.observe { state ->
            try {
                panelSnapshot = when (state) {
                    is ActiveMediaAppsState.Available -> PanelPlaybackSnapshot(
                        status = PanelPlaybackStatus.AVAILABLE,
                        rows = state.apps.map { app ->
                            val rule = store.readOrDefault(app.packageName, app.uid)
                            PanelPlaybackRow(
                                packageName = app.packageName,
                                uid = app.uid,
                                volumePercent = rule.volumePercent,
                                outputTarget = rule.outputTarget,
                                followsSystemAfterDisconnect = rule.followsSystemAfterDisconnect,
                                label = app.label.takeIf(String::isNotBlank),
                                iconPng = encodePanelIcon(app.icon, app.packageName, app.uid),
                                isSystemApp = app.isSystemApp,
                            )
                        },
                        devices = playbackSource.currentDeviceScan().devices,
                    )

                    is ActiveMediaAppsState.Error -> PanelPlaybackSnapshot(
                        status = PanelPlaybackStatus.HOST_UNAVAILABLE,
                        rows = emptyList(),
                        devices = emptyList(),
                    )
                }
            } catch (error: RuntimeException) {
                panelSnapshot = PanelPlaybackSnapshot(
                    PanelPlaybackStatus.HOST_UNAVAILABLE,
                    emptyList(),
                    emptyList()
                )
                AppLog.error("Unable to publish panel playback snapshot", error)
            }
        }
        playbackSource.observeDevices { scan ->
            try {
                val current = panelSnapshot
                panelSnapshot = current.copy(devices = scan.devices)
            } catch (error: RuntimeException) {
                AppLog.error("Unable to publish panel device snapshot", error)
            }
        }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return try {
            when (method) {
                RuleStoreBridgeContract.METHOD_READ_ALL -> Bundle().apply {
                    putParcelableArrayList(
                        RuleStoreBridgeContract.KEY_RULES,
                        ArrayList(SoundManProtocol.encodeRules(store.readAll().values.toList())),
                    )
                }

                RuleStoreBridgeContract.METHOD_READ_OR_DEFAULT -> result(
                    store.readOrDefault(requiredPackage(extras), requiredUid(extras)),
                )

                RuleStoreBridgeContract.METHOD_SAVE -> result(
                    store.save(
                        requiredPackage(extras),
                        requiredUid(extras),
                        requiredVolume(extras),
                        SoundManProtocol.decodeTarget(
                            requiredBundle(
                                extras,
                                RuleStoreBridgeContract.KEY_OUTPUT_TARGET
                            )
                        ),
                    ),
                )

                RuleStoreBridgeContract.METHOD_UPDATE_VOLUME -> result(
                    store.updateVolume(
                        requiredPackage(extras),
                        requiredUid(extras),
                        requiredVolume(extras)
                    ),
                )

                RuleStoreBridgeContract.METHOD_FALLBACK_TO_SYSTEM -> {
                    val target = SoundManProtocol.decodeTarget(
                        requiredBundle(extras, RuleStoreBridgeContract.KEY_OUTPUT_TARGET),
                    ) as? OutputTarget.Device ?: error("fallback target must be a device")
                    result(
                        store.fallbackToSystem(
                            requiredPackage(extras),
                            requiredUid(extras),
                            target
                        )
                    )
                }

                RuleStoreBridgeContract.METHOD_REVISION -> Bundle().apply {
                    putLong(RuleStoreBridgeContract.KEY_REVISION, store.revision())
                }

                RuleStoreBridgeContract.METHOD_PANEL_SNAPSHOT -> encodePanelSnapshot(
                    currentPanelSnapshot()
                )

                RuleStoreBridgeContract.METHOD_PANEL_SET_VOLUME -> {
                    val packageName = requiredPackage(extras)
                    val uid = requiredUid(extras)
                    val volumePercent = requiredVolume(extras)
                    store.updateVolume(packageName, uid, volumePercent)
                    playbackSource.setVolume(uid, volumePercent)
                    Bundle.EMPTY
                }

                RuleStoreBridgeContract.METHOD_PANEL_SET_ROUTE -> {
                    val packageName = requiredPackage(extras)
                    val uid = requiredUid(extras)
                    val target = SoundManProtocol.decodeTarget(
                        requiredBundle(extras, RuleStoreBridgeContract.KEY_OUTPUT_TARGET),
                    )
                    setPanelRoute(packageName, uid, target)
                    Bundle.EMPTY
                }

                CrashGuardContract.PROVIDER_METHOD -> {
                    // 本 Provider 无权限导出，崩溃上报必须限定 SystemUI（SYSTEM_UID），
                    // 否则任意第三方都能伪造"模块把系统界面搞崩了"的横幅吓唬用户。
                    val callingUid = Binder.getCallingUid()
                    if (callingUid != Process.SYSTEM_UID) {
                        throw SecurityException(
                            "${CrashGuardContract.PROVIDER_METHOD} requires SYSTEM_UID, got uid=$callingUid",
                        )
                    }
                    val trippedAtMs = extras
                        ?.takeIf { it.containsKey(CrashGuardContract.KEY_TRIPPED_AT) }
                        ?.getLong(CrashGuardContract.KEY_TRIPPED_AT)
                        ?.also { require(it > 0L) { "trippedAtMs must be positive" } }
                        ?: error("Missing ${CrashGuardContract.KEY_TRIPPED_AT}")
                    val reason = extras.getString(CrashGuardContract.KEY_REASON)
                        ?.takeIf(String::isNotBlank)
                        ?: CrashGuardContract.REASON_UNKNOWN
                    CrashGuardStore.recordTrip(
                        context ?: error("RuleStoreBridgeProvider has no context"),
                        trippedAtMs,
                        reason,
                    )
                    Bundle.EMPTY
                }

                else -> error("Unsupported rule bridge method=$method")
            }
        } catch (error: RuntimeException) {
            AppLog.error("Rule bridge call failed method=$method", error)
            throw error
        }
    }

    private fun result(rule: AppAudioRule): Bundle = Bundle().apply {
        putBundle(RuleStoreBridgeContract.KEY_RULE, SoundManProtocol.encodeRule(rule))
    }

    private fun currentPanelSnapshot(): PanelPlaybackSnapshot {
        val snapshot = panelSnapshot
        return snapshot.copy(
            rows = snapshot.rows.map { row ->
                val rule = store.readOrDefault(row.packageName, row.uid)
                row.copy(
                    volumePercent = rule.volumePercent,
                    outputTarget = rule.outputTarget,
                    followsSystemAfterDisconnect = rule.followsSystemAfterDisconnect,
                )
            },
            devices = playbackSource.currentDeviceScan().devices,
        )
    }

    private fun setPanelRoute(packageName: String, uid: Int, target: OutputTarget) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "panelSetRoute must not block the main thread" }
        if (target is OutputTarget.Device) {
            val connected = playbackSource.currentDeviceScan().devices.any { device ->
                target.candidates.any(device.candidates::contains)
            }
            require(connected) { "panel route target is disconnected" }
        }
        val resultRef = AtomicReference<HostCommandResult?>()
        val earlyResultRef = AtomicReference<HostCommandResult?>()
        val expectedCommandId = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        val removeObserver = playbackSource.observeResults { result ->
            val expected = expectedCommandId.get()
            if (expected == null) {
                if (result.uid == uid) earlyResultRef.set(result)
            } else if (result.commandId == expected) {
                resultRef.set(result)
                latch.countDown()
            }
        }
        try {
            val commandId = playbackSource.setRoute(uid, target)
            expectedCommandId.set(commandId)
            earlyResultRef.get()?.takeIf { it.commandId == commandId }?.let { result ->
                resultRef.set(result)
                latch.countDown()
            }
            check(latch.await(PANEL_ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "Timed out waiting for panel route result"
            }
            val result = checkNotNull(resultRef.get()) { "Panel route completed without a result" }
            check(result.success) { "Panel route failed resultCode=${result.resultCode}" }
            val existing = store.readOrDefault(packageName, uid)
            store.save(packageName, uid, existing.volumePercent, target)
            // 规则保存后必须广播通知被注入进程同步设备，否则 SystemUI 面板改了设备
            // 不会实时同步，要等到打开 App 音量管理时才会触发 publishAll。
            PreferredDeviceSync.publish(
                context!!,
                uid,
                target,
                playbackSource.currentSystemDevice()
            )
        } finally {
            removeObserver()
        }
    }

    private fun encodePanelSnapshot(snapshot: PanelPlaybackSnapshot): Bundle = Bundle().apply {
        putString(RuleStoreBridgeContract.KEY_PANEL_STATUS, snapshot.status.name)
        putParcelableArrayList(
            RuleStoreBridgeContract.KEY_PANEL_ROWS,
            ArrayList(snapshot.rows.map { row ->
                Bundle().apply {
                    putString(RuleStoreBridgeContract.KEY_PACKAGE_NAME, row.packageName)
                    putString(RuleStoreBridgeContract.KEY_LABEL, row.label)
                    putByteArray(RuleStoreBridgeContract.KEY_ICON_PNG, row.iconPng)
                    putInt(RuleStoreBridgeContract.KEY_UID, row.uid)
                    putInt(RuleStoreBridgeContract.KEY_VOLUME_PERCENT, row.volumePercent)
                    putBundle(
                        RuleStoreBridgeContract.KEY_OUTPUT_TARGET,
                        SoundManProtocol.encodeTarget(row.outputTarget)
                    )
                    putBoolean(
                        RuleStoreBridgeContract.KEY_FOLLOWS_SYSTEM_AFTER_DISCONNECT,
                        row.followsSystemAfterDisconnect,
                    )
                    putBoolean(
                        RuleStoreBridgeContract.KEY_IS_SYSTEM_APP,
                        row.isSystemApp,
                    )
                }
            }),
        )
        putParcelableArrayList(
            RuleStoreBridgeContract.KEY_PANEL_DEVICES,
            ArrayList(snapshot.devices.map(SoundManProtocol::encodeDevice)),
        )
    }

    private fun encodePanelIcon(icon: Drawable, packageName: String, uid: Int): ByteArray? = try {
        val bitmap = icon.toBitmap(PANEL_ICON_SIZE_PX, PANEL_ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Drawable compression returned false"
            }
            output.toByteArray().also { bytes ->
                check(bytes.isNotEmpty()) { "Drawable compression produced no bytes" }
            }
        }
    } catch (error: RuntimeException) {
        AppLog.warn("Unable to encode panel icon uid=$uid package=$packageName", error)
        null
    }

    private fun requiredPackage(extras: Bundle?): String =
        extras?.getString(RuleStoreBridgeContract.KEY_PACKAGE_NAME)?.takeIf(String::isNotBlank)
            ?: error("Missing packageName")

    private fun requiredUid(extras: Bundle?): Int =
        extras?.takeIf { it.containsKey(RuleStoreBridgeContract.KEY_UID) }
            ?.getInt(RuleStoreBridgeContract.KEY_UID)
            ?.also { require(it >= 0) { "uid must be non-negative" } }
            ?: error("Missing uid")

    private fun requiredVolume(extras: Bundle?): Int =
        extras?.takeIf { it.containsKey(RuleStoreBridgeContract.KEY_VOLUME_PERCENT) }
            ?.getInt(RuleStoreBridgeContract.KEY_VOLUME_PERCENT)
            ?.also { require(it in 0..100) { "volumePercent must be in 0..100" } }
            ?: error("Missing volumePercent")

    private fun requiredBundle(extras: Bundle?, key: String): Bundle =
        extras?.getBundle(key) ?: error("Missing $key")

    private companion object {
        const val PANEL_ROUTE_TIMEOUT_MILLIS = 4_000L
        const val PANEL_ICON_SIZE_PX = 96
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor =
        error("RuleStoreBridgeProvider supports call only")

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri =
        error("RuleStoreBridgeProvider supports call only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        error("RuleStoreBridgeProvider supports call only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = error("RuleStoreBridgeProvider supports call only")
}

/** SystemUI 进程中的 RuleStore 客户端，所有持久化操作均跨进程落到模块 Provider。 */
class ProviderPanelPlayback(private val systemUiContext: Context) {
    fun snapshot(): PanelPlaybackSnapshot {
        val result = call(RuleStoreBridgeContract.METHOD_PANEL_SNAPSHOT)
        val statusName = result.getString(RuleStoreBridgeContract.KEY_PANEL_STATUS)
            ?: error("Panel bridge returned no status")
        val status = PanelPlaybackStatus.valueOf(statusName)

        @Suppress("DEPRECATION")
        val bundles = result.getParcelableArrayList<Bundle>(RuleStoreBridgeContract.KEY_PANEL_ROWS)
            ?: error("Panel bridge returned no rows")

        @Suppress("DEPRECATION")
        val devices =
            result.getParcelableArrayList<Bundle>(RuleStoreBridgeContract.KEY_PANEL_DEVICES)
                ?: error("Panel bridge returned no devices")
        return PanelPlaybackSnapshot(
            status = status,
            rows = bundles.map { row ->
                PanelPlaybackRow(
                    packageName = row.getString(RuleStoreBridgeContract.KEY_PACKAGE_NAME)
                        ?: error("Panel row returned no packageName"),
                    uid = row.getInt(RuleStoreBridgeContract.KEY_UID),
                    volumePercent = row.getInt(RuleStoreBridgeContract.KEY_VOLUME_PERCENT),
                    outputTarget = SoundManProtocol.decodeTarget(
                        row.getBundle(RuleStoreBridgeContract.KEY_OUTPUT_TARGET)
                            ?: error("Panel row returned no outputTarget"),
                    ),
                    followsSystemAfterDisconnect = row.getBoolean(
                        RuleStoreBridgeContract.KEY_FOLLOWS_SYSTEM_AFTER_DISCONNECT,
                    ),
                    label = row.getString(RuleStoreBridgeContract.KEY_LABEL)
                        ?.takeIf(String::isNotBlank),
                    iconPng = row.getByteArray(RuleStoreBridgeContract.KEY_ICON_PNG)
                        ?.takeIf(ByteArray::isNotEmpty),
                    isSystemApp = row.getBoolean(
                        RuleStoreBridgeContract.KEY_IS_SYSTEM_APP,
                    ),
                )
            },
            devices = devices.map(SoundManProtocol::decodeDevice),
        )
    }

    fun setVolume(packageName: String, uid: Int, volumePercent: Int) {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(uid >= 0) { "uid must be non-negative" }
        require(volumePercent in 0..100) { "volumePercent must be in 0..100" }
        call(RuleStoreBridgeContract.METHOD_PANEL_SET_VOLUME, Bundle().apply {
            putString(RuleStoreBridgeContract.KEY_PACKAGE_NAME, packageName)
            putInt(RuleStoreBridgeContract.KEY_UID, uid)
            putInt(RuleStoreBridgeContract.KEY_VOLUME_PERCENT, volumePercent)
        })
    }

    fun setRoute(packageName: String, uid: Int, target: OutputTarget) {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(uid >= 0) { "uid must be non-negative" }
        call(RuleStoreBridgeContract.METHOD_PANEL_SET_ROUTE, Bundle().apply {
            putString(RuleStoreBridgeContract.KEY_PACKAGE_NAME, packageName)
            putInt(RuleStoreBridgeContract.KEY_UID, uid)
            putBundle(
                RuleStoreBridgeContract.KEY_OUTPUT_TARGET,
                SoundManProtocol.encodeTarget(target)
            )
        })
    }

    private fun call(method: String, extras: Bundle? = null): Bundle = try {
        systemUiContext.contentResolver.call(RuleStoreBridgeContract.URI, method, null, extras)
            ?: error("Panel bridge returned null method=$method")
    } catch (error: RuntimeException) {
        AppLog.error("Unable to call panel bridge method=$method", error)
        throw error
    }
}