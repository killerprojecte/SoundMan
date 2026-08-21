package hk.uwu.soundman.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import hk.uwu.soundman.log.AppLog

/**
 * 崩溃看门狗的跨进程契约。
 *
 * 三条通道各司其职：
 * - 镜像偏好（[MIRROR_REENABLE_AT]）：模块 App 写入，SystemUI 在禁用态下启动时读取，
 *   复用 YukiHook 跨进程偏好文件（与内置面板开关同一通道）。
 * - Provider 上报（[PROVIDER_METHOD]）：SystemUI 触发禁用后调用模块 RuleStoreBridge Provider，
 *   让 App 展示横幅；调用方必须校验为 SYSTEM_UID。
 * - App 本地记录（[CrashGuardStore]）：最近一次禁用事实，供主页横幅判断展示与“重新启用”。
 */
object CrashGuardContract {
    /** 镜像偏好键：用户最近一次点击“重新启用”的时间戳（epoch ms）。 */
    const val MIRROR_REENABLE_AT = "crash_guard_reenable_at"

    /** RuleStoreBridge Provider 方法名：SystemUI 上报禁用事实。 */
    const val PROVIDER_METHOD = "crashGuardReport"

    /** 上报 Bundle：禁用触发时刻。 */
    const val KEY_TRIPPED_AT = "crashGuardTrippedAtMs"

    /** 上报 Bundle：禁用原因代码。 */
    const val KEY_REASON = "crashGuardReason"

    /** 原因代码：启动期连续崩溃（防变砖主规则）。 */
    const val REASON_EARLY_CRASH_STREAK = "early_crash_streak"

    /** 原因代码：短时崩溃爆发（滚动窗口）。 */
    const val REASON_CRASH_BURST = "crash_burst"

    /** 原因代码：未知（本地记录缺原因时的兜底）。 */
    const val REASON_UNKNOWN = "unknown"

    /** 上报 URI，复用规则桥 Provider。 */
    val URI: Uri = Uri.parse("content://${RuleStoreBridgeContract.AUTHORITY}")
}

/** App 本地记录的最近一次看门狗禁用事实。 */
data class CrashGuardTripInfo(
    val trippedAtMs: Long,
    val reason: String,
    /** 用户最近一次“重新启用”操作时刻；0 表示从未重新启用。 */
    val reenableAtMs: Long,
) {
    /** 横幅是否应当展示：存在禁用记录，且发生在最近一次“重新启用”之后。 */
    val active: Boolean get() = trippedAtMs > reenableAtMs
}

/**
 * App 侧的看门狗本地记录。
 *
 * 真值在 SystemUI 进程的状态文件里，这里只保存“给用户看的副本”：
 * Provider 收到 SystemUI 上报时写入，用户点“重新启用”时补记 reenable 时间戳。
 * 全部操作失败只打日志不上抛——记录展示失败不应影响设置写入。
 */
object CrashGuardStore {
    private const val PREFERENCES_NAME = "soundman_crash_guard"
    private const val KEY_TRIPPED_AT = "last_trip_at_ms"
    private const val KEY_REASON = "last_trip_reason"
    private const val KEY_REENABLE_AT = "last_reenable_at_ms"

    fun readTrip(context: Context): CrashGuardTripInfo? = try {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val trippedAt = preferences.getLong(KEY_TRIPPED_AT, 0L)
        if (trippedAt <= 0L) {
            null
        } else {
            CrashGuardTripInfo(
                trippedAtMs = trippedAt,
                reason = preferences.getString(KEY_REASON, null)
                    ?: CrashGuardContract.REASON_UNKNOWN,
                reenableAtMs = preferences.getLong(KEY_REENABLE_AT, 0L),
            )
        }
    } catch (error: RuntimeException) {
        AppLog.error("Unable to read crash guard trip", error)
        null
    }

    /** Provider 收到 SystemUI 上报时调用。 */
    fun recordTrip(context: Context, trippedAtMs: Long, reason: String) {
        try {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit {
                    putLong(KEY_TRIPPED_AT, trippedAtMs)
                    putString(KEY_REASON, reason)
                }
            AppLog.info("Recorded crash guard trip at=$trippedAtMs reason=$reason")
        } catch (error: RuntimeException) {
            AppLog.error("Unable to record crash guard trip", error)
        }
    }

    /** 用户点击“重新启用”时调用，与镜像偏好写入配套。 */
    fun recordReenable(context: Context, reenableAtMs: Long) {
        try {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit { putLong(KEY_REENABLE_AT, reenableAtMs) }
        } catch (error: RuntimeException) {
            AppLog.error("Unable to record crash guard reenable", error)
        }
    }
}
