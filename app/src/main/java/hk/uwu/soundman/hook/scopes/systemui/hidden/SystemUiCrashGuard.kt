package hk.uwu.soundman.hook.scopes.systemui.hidden

import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import hk.uwu.soundman.data.CrashGuardContract
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * SystemUI 进程内的崩溃看门狗运行时（宿主侧）。
 *
 * 与纯策略层 [SystemUiCrashGuardPolicy] 配合：
 * 策略层只做状态机推演，本类负责全部副作用——
 *
 * - 状态文件持久化在 SystemUI 自己的 DE 存储目录（`/data/user_de/0/com.android.systemui/files`）。
 *   选 DE 而不是 CE：SystemUI 开机即起、可能早于用户解锁，CE 目录在解锁前不可读，
 *   而看门狗恰恰必须在最早的启动路径上工作。
 * - 进程启动时调用 [admit] 裁决本进程是否安装 Hook；未裁决通过则调用方应放弃安装。
 * - 放行时安装 UncaughtExceptionHandler（链回原 handler，不吞崩溃），崩溃发生即落盘记录。
 * - 放行且存活满 [SystemUiCrashGuardPolicy.STABLE_UPTIME_RESET_MS] 后清零启动期连击。
 * - 触发禁用时通过模块 RuleStoreBridge Provider 上报给 App 展示横幅（尽力而为，失败下次启动重试）。
 *
 * 全部 I/O 与异常都被本类吞掉并记日志——看门狗自身故障永远放行（fail-open），
 * 不能让保护机制反而把模块卡死。
 *
 * 只在 SystemUI 主进程（uid=1000 且 cmdline 为包名）参与：
 * 分身/子用户 SystemUI 与 `:xxx` 子进程不写状态文件，避免多写者竞争。
 *
 * @param clock 当前时间提供者，测试可注入，生产为 [System.currentTimeMillis]
 * @param reenableAtMs 读取模块 App 写入的“重新启用”时间戳；仅在禁用态下启动才会被调用
 * @param log 日志出口，与 hooker 的 writeLog 同构
 */
class SystemUiCrashGuard(
    private val clock: () -> Long = System::currentTimeMillis,
    private val reenableAtMs: () -> Long,
    private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
) {
    // 惰性创建：构造函数保持 JVM 可实例化（既有 hooker 单测会触发对象初始化），
    // Handler 只有在真实 SystemUI 进程里安排稳定重置时才需要主线程 Looper。
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val crashRecorded = AtomicBoolean(false)
    private val tripReporter = SystemUiCrashGuardTripReporter(log)

    /**
     * 进程启动裁决。
     *
     * @return null 表示看门狗不适用（非主进程）或自身故障放行，调用方应照常安装 Hook；
     *   非 null 时以 [SystemUiCrashGuardDecision.admitted] 为准
     */
    fun admit(): SystemUiCrashGuardDecision? = try {
        admitInternal()
    } catch (error: Throwable) {
        log(Log.ERROR, TAG, "Crash guard failed open after internal error", error)
        null
    }

    private fun admitInternal(): SystemUiCrashGuardDecision? {
        if (!isPrimarySystemUiProcess()) {
            log(
                Log.INFO, TAG,
                "Skip crash guard: not the primary SystemUI process (uid=${Process.myUid()})",
                null,
            )
            return null
        }
        val now = clock()
        val decision = SystemUiCrashGuardPolicy.onProcessStart(loadState(), now, reenableAtMs)
        saveState(decision.state)
        if (decision.admitted) {
            installCrashRecorder()
            scheduleStableReset(decision.state.lastStartAtMs)
            log(
                Log.INFO, TAG,
                "Crash guard admitted: streak=${decision.state.earlyCrashStreak} " +
                        "recordedCrashes=${decision.state.crashTimestampsMs.size}",
                null,
            )
        } else {
            log(
                Log.WARN, TAG,
                "Crash guard tripped: reason=${decision.state.trippedReason} " +
                        "trippedAt=${decision.state.trippedAtMs}; SoundMan hooks stay off this boot",
                null,
            )
            if (decision.reportTrip) {
                reportTrip(decision.state)
            }
        }
        return decision
    }

    /**
     * 记录一次未捕获崩溃（由异常处理器调用，任意线程）。
     *
     * 只做事实追加（阈值判定收敛在下次启动的 [admit]），进程即将死亡，
     * 因此用原子标记保证只记一次，任何后续异常都被吞掉。
     */
    fun recordCrash(thread: Thread, throwable: Throwable) {
        if (!crashRecorded.compareAndSet(false, true)) return
        try {
            saveState(SystemUiCrashGuardPolicy.onUncaughtCrash(loadState(), clock()))
            log(
                Log.ERROR, TAG,
                "Recorded SystemUI uncaught crash on thread=${thread.name}: " +
                        "${throwable.javaClass.name}",
                throwable,
            )
        } catch (error: Throwable) {
            log(Log.WARN, TAG, "Unable to record SystemUI crash", error)
        }
    }

    /** 进程存活过稳定阈值后清零启动期连击（滚动窗口按时间自然过期）。 */
    private fun scheduleStableReset(startedAtMs: Long) {
        val delay = (SystemUiCrashGuardPolicy.STABLE_UPTIME_RESET_MS - (clock() - startedAtMs))
            .coerceIn(0L, SystemUiCrashGuardPolicy.STABLE_UPTIME_RESET_MS)
        mainHandler.postDelayed(
            {
                try {
                    val latest = loadState()
                    if (latest.lastStartAtMs == startedAtMs) {
                        saveState(SystemUiCrashGuardPolicy.onStableUptime(latest))
                        log(Log.INFO, TAG, "SystemUI stable uptime reached; early-crash streak cleared", null)
                    }
                } catch (error: Throwable) {
                    log(Log.WARN, TAG, "Stable uptime reset failed", error)
                }
            },
            delay,
        )
    }

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is SystemUiCrashGuardHandler) {
            // LSPosed 热重载会再次进入 onHook，复用已有 handler 避免自链。
            return
        }
        Thread.setDefaultUncaughtExceptionHandler(SystemUiCrashGuardHandler(previous, this))
        log(Log.INFO, TAG, "Crash recorder installed (chained to ${previous?.javaClass?.name})", null)
    }

    private fun reportTrip(state: SystemUiCrashGuardState) {
        val trippedAtMs = state.trippedAtMs ?: return
        val reason = state.trippedReason ?: CrashGuardContract.REASON_UNKNOWN
        tripReporter.report(trippedAtMs, reason) {
            try {
                saveState(SystemUiCrashGuardPolicy.onTripReported(loadState()))
            } catch (error: Throwable) {
                log(Log.WARN, TAG, "Unable to mark crash guard trip reported", error)
            }
        }
    }

    private fun isPrimarySystemUiProcess(): Boolean {
        if (Process.myUid() != Process.SYSTEM_UID) return false
        val cmdline = runCatching {
            File("/proc/self/cmdline").readText().substringBefore('\u0000')
        }.getOrNull()?.trim() ?: return false
        return cmdline == PROCESS_NAME
    }

    private fun resolveStateFile(): File? = STATE_DIR_CANDIDATES
        .asSequence()
        .map { relative -> File(Environment.getDataDirectory(), relative) }
        .map { directory -> directory.takeIf { it.isDirectory || it.mkdirs() } }
        .filterNotNull()
        .map { directory -> File(directory, STATE_FILE_NAME) }
        .firstOrNull()

    private fun loadState(): SystemUiCrashGuardState = try {
        val file = resolveStateFile()
        if (file == null || !file.isFile) {
            SystemUiCrashGuardState()
        } else {
            Properties().let { props ->
                FileInputStream(file).use { props.load(it) }
                SystemUiCrashGuardState(
                    lastStartAtMs = props.longProperty(KEY_LAST_START_AT),
                    earlyCrashStreak = props.longProperty(KEY_EARLY_STREAK).toInt(),
                    crashTimestampsMs = props.getProperty(KEY_CRASH_TIMESTAMPS, "")
                        .split(',')
                        .mapNotNull(String::toLongOrNull),
                    lastCrashAtMs = props.longProperty(KEY_LAST_CRASH_AT),
                    lastCrashWasEarly = props.boolProperty(KEY_LAST_CRASH_EARLY),
                    trippedAtMs = props.optionalLongProperty(KEY_TRIPPED_AT),
                    trippedReason = props.getProperty(KEY_TRIPPED_REASON),
                    lastReportedTripAtMs = props.optionalLongProperty(KEY_LAST_REPORTED_AT),
                )
            }
        }
    } catch (error: Throwable) {
        log(Log.WARN, TAG, "Unable to load crash guard state; starting fresh", error)
        SystemUiCrashGuardState()
    }

    private fun saveState(state: SystemUiCrashGuardState) {
        try {
            val file = resolveStateFile() ?: run {
                log(Log.WARN, TAG, "Crash guard state directory unavailable", null)
                return
            }
            val props = Properties().apply {
                setProperty(KEY_LAST_START_AT, state.lastStartAtMs.toString())
                setProperty(KEY_EARLY_STREAK, state.earlyCrashStreak.toString())
                setProperty(KEY_CRASH_TIMESTAMPS, state.crashTimestampsMs.joinToString(","))
                setProperty(KEY_LAST_CRASH_AT, state.lastCrashAtMs.toString())
                setProperty(KEY_LAST_CRASH_EARLY, state.lastCrashWasEarly.toString())
                state.trippedAtMs?.let { setProperty(KEY_TRIPPED_AT, it.toString()) }
                state.trippedReason?.let { setProperty(KEY_TRIPPED_REASON, it) }
                state.lastReportedTripAtMs?.let { setProperty(KEY_LAST_REPORTED_AT, it.toString()) }
            }
            // 先写临时文件再原子 rename，崩溃中途也不会留下半截状态。
            val temp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(temp).use { props.store(it, null) }
            if (!temp.renameTo(file)) {
                FileOutputStream(file).use { props.store(it, null) }
            }
        } catch (error: Throwable) {
            log(Log.WARN, TAG, "Unable to save crash guard state", error)
        }
    }

    private fun Properties.longProperty(key: String): Long =
        getProperty(key, null)?.toLongOrNull() ?: 0L

    private fun Properties.optionalLongProperty(key: String): Long? =
        getProperty(key, null)?.toLongOrNull()

    private fun Properties.boolProperty(key: String): Boolean =
        getProperty(key, null)?.toBoolean() ?: false

    private class SystemUiCrashGuardHandler(
        private val chained: Thread.UncaughtExceptionHandler?,
        private val owner: SystemUiCrashGuard,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            owner.recordCrash(thread, throwable)
            // 链回原 handler，让系统照常终止进程——看门狗只观察，不接管。
            chained?.uncaughtException(thread, throwable)
        }
    }

    private companion object {
        const val TAG = "SoundMan.CrashGuard"
        const val PROCESS_NAME = "com.android.systemui"
        const val STATE_FILE_NAME = "soundman_crash_guard.properties"
        const val KEY_LAST_START_AT = "last_start_at_ms"
        const val KEY_EARLY_STREAK = "early_crash_streak"
        const val KEY_CRASH_TIMESTAMPS = "crash_timestamps_ms"
        const val KEY_LAST_CRASH_AT = "last_crash_at_ms"
        const val KEY_LAST_CRASH_EARLY = "last_crash_was_early"
        const val KEY_TRIPPED_AT = "tripped_at_ms"
        const val KEY_TRIPPED_REASON = "tripped_reason"
        const val KEY_LAST_REPORTED_AT = "last_reported_trip_at_ms"

        /**
         * 状态文件目录候选（相对 `/data`）。
         *
         * 首选 `user_de/0/...`（FBE 设备常规入口，多为指向 `system_de/0/...` 的符号链接）；
         * 链接缺失的设备上直接落 `system_de/0/...`。目录属主是 uid 1000，SystemUI 可直接写。
         */
        val STATE_DIR_CANDIDATES = listOf(
            "user_de/0/com.android.systemui/files",
            "system_de/0/com.android.systemui/files",
        )
    }
}

/**
 * 禁用事实上报器：在独立守护线程尽力把禁用事件送进模块 App 的 Provider。
 *
 * 上报发生在 SystemUI 启动早期，模块 App 进程多半还没起：
 * `contentResolver.call` 会按需拉起对方 Provider，因此先重试等待
 * [android.app.ActivityThread.currentApplication] 可用，再带退避地投递。
 * 全部失败不重试本轮——下次 SystemUI 启动仍处于禁用态且未标记已上报，会再次触发。
 */
internal class SystemUiCrashGuardTripReporter(
    private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
) {
    fun report(trippedAtMs: Long, reason: String, onReported: () -> Unit) {
        thread(name = THREAD_NAME, isDaemon = true) {
            RETRY_DELAYS_MS.forEachIndexed { index, delayMs ->
                if (delayMs > 0L) {
                    runCatching { Thread.sleep(delayMs) }
                }
                val context = currentApplication()
                if (context == null) {
                    log(Log.WARN, TAG, "Trip report attempt=${index + 1} has no application yet", null)
                    return@forEachIndexed
                }
                val result = runCatching {
                    val extras = Bundle().apply {
                        putLong(CrashGuardContract.KEY_TRIPPED_AT, trippedAtMs)
                        putString(CrashGuardContract.KEY_REASON, reason)
                    }
                    context.contentResolver.call(
                        CrashGuardContract.URI,
                        CrashGuardContract.PROVIDER_METHOD,
                        null,
                        extras,
                    )
                }
                result.exceptionOrNull()?.let { error ->
                    log(Log.WARN, TAG, "Trip report attempt=${index + 1} failed", error)
                }
                if (result.getOrNull() != null) {
                    log(Log.INFO, TAG, "Trip report delivered: at=$trippedAtMs reason=$reason", null)
                    runCatching(onReported).onFailure { error ->
                        log(Log.WARN, TAG, "Trip report callback failed", error)
                    }
                    return@thread
                }
            }
            log(
                Log.ERROR, TAG,
                "Trip report gave up after ${RETRY_DELAYS_MS.size} attempts; retrying on next SystemUI start",
                null,
            )
        }
    }

    private fun currentApplication(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        activityThread.getMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()

    private companion object {
        const val TAG = "SoundMan.CrashGuard"
        const val THREAD_NAME = "soundman-crash-report"

        /** 首投在启动后立即进行，随后 5s / 15s 各补一次（等 App 进程就绪）。 */
        val RETRY_DELAYS_MS = longArrayOf(0L, 5_000L, 15_000L)
    }
}
