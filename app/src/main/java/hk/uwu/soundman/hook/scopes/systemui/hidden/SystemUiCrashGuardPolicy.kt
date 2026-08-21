package hk.uwu.soundman.hook.scopes.systemui.hidden

import hk.uwu.soundman.data.CrashGuardContract

/**
 * SystemUI 崩溃看门狗的纯判定逻辑。
 *
 * 只做状态机推演，不做任何 I/O，方便 JVM 单测覆盖全部触发路径。
 *
 * 两类触发规则（对齐“激进但可自愈”的目标）：
 * - 启动期连击：进程启动后 [EARLY_WINDOW_MS] 内崩溃视为“启动期崩溃”，
 *   连续 [EARLY_CRASH_STREAK_TRIP] 次启动期崩溃 → 禁用。这是防“变砖”的主规则——
 *   SystemUI 若因模块在启动路径上崩溃，第二次重启就进入无模块的安全模式。
 * - 短时爆发：[ROLLING_WINDOW_MS] 内累计 [ROLLING_CRASH_TRIP] 次崩溃 → 禁用。
 *   兜住“不在启动期、但用户每次展开面板都崩”的成年级崩溃循环。
 *
 * 崩溃只认未捕获异常（由宿主侧 UncaughtExceptionHandler 记录）；
 * 正常的重启（主题切换、插件更新、LSPosed 重载）不会经过异常处理器，不计入。
 *
 * 自愈规则：进程存活超过 [STABLE_UPTIME_RESET_MS] 视为稳定，启动期连击清零；
 * 滚动窗口按时间自然过期。真正的禁用只能通过模块 App 的“重新启用”清除，
 * 清除时间戳晚于触发时间戳时，下次 SystemUI 启动恢复完整功能。
 */
data class SystemUiCrashGuardState(
    /** 本进程（或上一进程）启动时刻；0 表示尚未记录。 */
    val lastStartAtMs: Long = 0L,
    /** 连续以“启动期崩溃”收尾的进程数。 */
    val earlyCrashStreak: Int = 0,
    /** 最近 [MAX_RECORDED_CRASHES] 次崩溃时刻，跨进程保留，用于滚动窗口判定。 */
    val crashTimestampsMs: List<Long> = emptyList(),
    /** 上一进程崩溃时刻；0 表示上一进程正常退出或未崩溃。 */
    val lastCrashAtMs: Long = 0L,
    /** 上一进程的崩溃是否发生在启动窗口内。 */
    val lastCrashWasEarly: Boolean = false,
    /** 禁用触发时刻；null 表示当前未禁用。 */
    val trippedAtMs: Long? = null,
    /** 禁用原因代码（early_crash_streak / crash_burst）。 */
    val trippedReason: String? = null,
    /** 最近一次已成功上报给模块 App 的禁用时刻，避免每次启动重复上报。 */
    val lastReportedTripAtMs: Long? = null,
)

/** 进程启动时的看门狗裁决。 */
data class SystemUiCrashGuardDecision(
    /** true 表示放行，本次进程应安装全部 Hook。 */
    val admitted: Boolean,
    /** 需要持久化的最新状态。 */
    val state: SystemUiCrashGuardState,
    /** true 表示处于禁用态且该次禁用尚未上报过（宿主侧应尽力通知模块 App）。 */
    val reportTrip: Boolean,
)

object SystemUiCrashGuardPolicy {
    const val EARLY_WINDOW_MS = 60_000L
    const val EARLY_CRASH_STREAK_TRIP = 2
    const val ROLLING_WINDOW_MS = 10 * 60_000L
    const val ROLLING_CRASH_TRIP = 3
    const val STABLE_UPTIME_RESET_MS = 60_000L
    const val MAX_RECORDED_CRASHES = 8

    // 原因代码的单一事实来源在 CrashGuardContract；此处以 const 引用编译期内联，
    // JVM 单测断言不会触发 CrashGuardContract 的类初始化（URI 属性不在常量折叠路径上）。
    const val REASON_EARLY_CRASH_STREAK = CrashGuardContract.REASON_EARLY_CRASH_STREAK
    const val REASON_CRASH_BURST = CrashGuardContract.REASON_CRASH_BURST

    /**
     * 进程启动裁决。
     *
     * 评估顺序：先看“重新启用”时间戳是否晚于禁用时刻（晚于则解除禁用并按全新状态放行），
     * 再结算上一进程是否以崩溃收尾并推进连击计数，最后做滚动窗口与连击阈值判定。
     *
     * @param state 上次持久化的状态
     * @param nowMs 当前时间
     * @param reenableAtMs 模块 App 写入的“重新启用”时间戳提供者；仅在已处于禁用态时才会
     *   被调用（未禁用的常规启动路径零跨进程开销），返回 0 表示从未请求
     */
    fun onProcessStart(
        state: SystemUiCrashGuardState,
        nowMs: Long,
        reenableAtMs: () -> Long,
    ): SystemUiCrashGuardDecision {
        val tripped = state.trippedAtMs
        if (tripped != null) {
            return if (reenableAtMs() > tripped) {
                // 用户已确认重新启用：清空全部计数，按全新进程放行。
                val fresh = state.copy(
                    lastStartAtMs = nowMs,
                    earlyCrashStreak = 0,
                    crashTimestampsMs = emptyList(),
                    lastCrashAtMs = 0L,
                    lastCrashWasEarly = false,
                    trippedAtMs = null,
                    trippedReason = null,
                    lastReportedTripAtMs = null,
                )
                SystemUiCrashGuardDecision(admitted = true, state = fresh, reportTrip = false)
            } else {
                SystemUiCrashGuardDecision(
                    admitted = false,
                    state = state,
                    reportTrip = state.lastReportedTripAtMs != tripped,
                )
            }
        }

        val previousRunCrashed = state.lastCrashAtMs > state.lastStartAtMs && state.lastStartAtMs > 0L
        val streak = when {
            previousRunCrashed && state.lastCrashWasEarly -> state.earlyCrashStreak + 1
            previousRunCrashed -> 0
            else -> 0
        }
        val windowCrashes = state.crashTimestampsMs.count { nowMs - it <= ROLLING_WINDOW_MS }
        val burstTripped = windowCrashes >= ROLLING_CRASH_TRIP
        val streakTripped = streak >= EARLY_CRASH_STREAK_TRIP

        return if (burstTripped || streakTripped) {
            val reason = when {
                streakTripped -> REASON_EARLY_CRASH_STREAK
                else -> REASON_CRASH_BURST
            }
            val trippedState = state.copy(
                lastStartAtMs = nowMs,
                earlyCrashStreak = streak,
                lastCrashAtMs = 0L,
                lastCrashWasEarly = false,
                trippedAtMs = nowMs,
                trippedReason = reason,
            )
            SystemUiCrashGuardDecision(
                admitted = false,
                state = trippedState,
                reportTrip = true,
            )
        } else {
            val admitted = state.copy(
                lastStartAtMs = nowMs,
                earlyCrashStreak = streak,
                lastCrashAtMs = 0L,
                lastCrashWasEarly = false,
            )
            SystemUiCrashGuardDecision(admitted = true, state = admitted, reportTrip = false)
        }
    }

    /**
     * 未捕获异常记录：只追加事实，不做阈值判定。
     *
     * 判定统一收敛在 [onProcessStart]，一次崩溃（哪怕在启动期）不会立即禁用，
     * 给偶发崩溃留出容错；阈值在下次进程启动时结算。
     */
    fun onUncaughtCrash(state: SystemUiCrashGuardState, nowMs: Long): SystemUiCrashGuardState {
        val wasEarly = state.lastStartAtMs > 0L && nowMs - state.lastStartAtMs < EARLY_WINDOW_MS
        val crashes = (state.crashTimestampsMs + nowMs).takeLast(MAX_RECORDED_CRASHES)
        return state.copy(
            crashTimestampsMs = crashes,
            lastCrashAtMs = nowMs,
            lastCrashWasEarly = wasEarly,
        )
    }

    /** 进程稳定存活后清零启动期连击；滚动窗口按时间自然过期，无需清理。 */
    fun onStableUptime(state: SystemUiCrashGuardState): SystemUiCrashGuardState =
        state.copy(earlyCrashStreak = 0)

    /** 上报成功后标记，避免每个 SystemUI 进程重复上报同一次禁用。 */
    fun onTripReported(state: SystemUiCrashGuardState): SystemUiCrashGuardState =
        state.copy(lastReportedTripAtMs = state.trippedAtMs)
}
