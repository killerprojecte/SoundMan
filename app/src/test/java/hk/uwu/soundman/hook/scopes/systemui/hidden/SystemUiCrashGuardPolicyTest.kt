package hk.uwu.soundman.hook.scopes.systemui.hidden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemUiCrashGuardPolicyTest {
    private val start = 1_000_000L

    private fun neverReenable(): Long = 0L

    @Test
    fun freshStateAdmitsFirstBoot() {
        val decision = SystemUiCrashGuardPolicy.onProcessStart(
            SystemUiCrashGuardState(),
            nowMs = start,
            reenableAtMs = ::neverReenable,
        )
        assertTrue(decision.admitted)
        assertFalse(decision.reportTrip)
        assertEquals(start, decision.state.lastStartAtMs)
        assertEquals(0, decision.state.earlyCrashStreak)
        assertNull(decision.state.trippedAtMs)
    }

    @Test
    fun singleEarlyCrashKeepsModuleAdmitted() {
        // 第一次启动 → 启动期崩溃 → 第二次启动：连击=1，仍放行（防误杀，给偶发崩溃留容错）。
        val first = SystemUiCrashGuardPolicy.onProcessStart(
            SystemUiCrashGuardState(),
            nowMs = start,
            reenableAtMs = ::neverReenable,
        ).state
        val crashed = SystemUiCrashGuardPolicy.onUncaughtCrash(
            first,
            nowMs = start + 30_000L,
        )
        assertTrue(crashed.lastCrashWasEarly)

        val second = SystemUiCrashGuardPolicy.onProcessStart(
            crashed,
            nowMs = start + 40_000L,
            reenableAtMs = ::neverReenable,
        )
        assertTrue(second.admitted)
        assertEquals(1, second.state.earlyCrashStreak)
    }

    @Test
    fun twoConsecutiveEarlyCrashesTripGuard() {
        var state = SystemUiCrashGuardPolicy.onProcessStart(
            SystemUiCrashGuardState(),
            nowMs = start,
            reenableAtMs = ::neverReenable,
        ).state
        // 第一个进程：启动 10s 后崩（早期），30s 处重启。
        state = SystemUiCrashGuardPolicy.onUncaughtCrash(state, nowMs = start + 10_000L)
        state = SystemUiCrashGuardPolicy.onProcessStart(
            state,
            nowMs = start + 30_000L,
            reenableAtMs = ::neverReenable,
        ).state
        // 第二个进程：启动 5s 后又崩（早期），60s 处重启 → 连击达到 2 → 禁用。
        state = SystemUiCrashGuardPolicy.onUncaughtCrash(state, nowMs = start + 35_000L)
        val decision = SystemUiCrashGuardPolicy.onProcessStart(
            state,
            nowMs = start + 60_000L,
            reenableAtMs = ::neverReenable,
        )

        assertFalse(decision.admitted)
        assertTrue(decision.reportTrip)
        assertEquals(SystemUiCrashGuardPolicy.REASON_EARLY_CRASH_STREAK, decision.state.trippedReason)
        assertEquals(start + 60_000L, decision.state.trippedAtMs)
    }

    @Test
    fun nonEarlyCrashDoesNotAdvanceStreak() {
        // 存活过启动窗口后的崩溃不算连击：连击必须清零重来。
        var state = SystemUiCrashGuardPolicy.onProcessStart(
            SystemUiCrashGuardState(),
            nowMs = start,
            reenableAtMs = ::neverReenable,
        ).state
        state = SystemUiCrashGuardPolicy.onUncaughtCrash(state, nowMs = start + 5_000L)
        state = SystemUiCrashGuardPolicy.onProcessStart(
            state,
            nowMs = start + 30_000L,
            reenableAtMs = ::neverReenable,
        ).state
        assertEquals(1, state.earlyCrashStreak)

        // 第二个进程存活 5 分钟后崩（非早期）。
        state = SystemUiCrashGuardPolicy.onUncaughtCrash(state, nowMs = start + 5 * 60_000L)
        assertFalse(state.lastCrashWasEarly)
        val decision = SystemUiCrashGuardPolicy.onProcessStart(
            state,
            nowMs = start + 6 * 60_000L,
            reenableAtMs = ::neverReenable,
        )
        assertTrue(decision.admitted)
        assertEquals(0, decision.state.earlyCrashStreak)
    }

    @Test
    fun stableUptimeClearsEarlyStreak() {
        var state = SystemUiCrashGuardPolicy.onProcessStart(
            SystemUiCrashGuardState(),
            nowMs = start,
            reenableAtMs = ::neverReenable,
        ).state
        state = SystemUiCrashGuardPolicy.onUncaughtCrash(state, nowMs = start + 10_000L)
        state = SystemUiCrashGuardPolicy.onProcessStart(
            state,
            nowMs = start + 30_000L,
            reenableAtMs = ::neverReenable,
        ).state
        assertEquals(1, state.earlyCrashStreak)

        // 进程稳定存活满 60s 后连击清零：其后即便再崩，也不是"启动期连击"。
        val stable = SystemUiCrashGuardPolicy.onStableUptime(state)
        assertEquals(0, stable.earlyCrashStreak)
        val crashedLate = SystemUiCrashGuardPolicy.onUncaughtCrash(
            stable,
            nowMs = start + 30_000L + 300_000L,
        )
        assertFalse(crashedLate.lastCrashWasEarly)
    }

    @Test
    fun crashBurstInsideRollingWindowTripsGuard() {
        // 三次崩溃都在 10 分钟窗口内（且非连续启动期崩溃）→ burst 禁用。
        var state = SystemUiCrashGuardPolicy.onProcessStart(
            SystemUiCrashGuardState(),
            nowMs = start,
            reenableAtMs = ::neverReenable,
        ).state
        var now = start
        repeat(2) { round ->
            // 每个进程存活 3 分钟（跨过稳定阈值）后崩溃。
            now += 3 * 60_000L
            state = SystemUiCrashGuardPolicy.onUncaughtCrash(state, nowMs = now)
            now += 5_000L
            state = SystemUiCrashGuardPolicy.onProcessStart(
                state,
                nowMs = now,
                reenableAtMs = ::neverReenable,
            ).state
        }
        // 第三个进程崩溃（此时窗口内已有 2 次记录）。
        now += 3 * 60_000L
        state = SystemUiCrashGuardPolicy.onUncaughtCrash(state, nowMs = now)
        now += 5_000L
        val decision = SystemUiCrashGuardPolicy.onProcessStart(
            state,
            nowMs = now,
            reenableAtMs = ::neverReenable,
        )

        assertFalse(decision.admitted)
        assertEquals(SystemUiCrashGuardPolicy.REASON_CRASH_BURST, decision.state.trippedReason)
    }

    @Test
    fun oldCrashesExpireOutOfRollingWindow() {
        // 两次崩溃彼此相隔超过 10 分钟，窗口内各自只算 1 次 → 不触发 burst。
        var state = SystemUiCrashGuardPolicy.onProcessStart(
            SystemUiCrashGuardState(),
            nowMs = start,
            reenableAtMs = ::neverReenable,
        ).state
        state = SystemUiCrashGuardPolicy.onUncaughtCrash(state, nowMs = start + 5 * 60_000L)
        state = SystemUiCrashGuardPolicy.onProcessStart(
            state,
            nowMs = start + 5 * 60_000L + 5_000L,
            reenableAtMs = ::neverReenable,
        ).state
        // 第二次崩溃距第一次 11 分钟：窗口外。
        state = SystemUiCrashGuardPolicy.onUncaughtCrash(
            state,
            nowMs = start + 16 * 60_000L + 5_000L,
        )
        val decision = SystemUiCrashGuardPolicy.onProcessStart(
            state,
            nowMs = start + 16 * 60_000L + 10_000L,
            reenableAtMs = ::neverReenable,
        )
        assertTrue(decision.admitted)
    }

    @Test
    fun crashHistoryCappedToMaxRecorded() {
        var state = SystemUiCrashGuardState(lastStartAtMs = start)
        repeat(20) { index ->
            state = SystemUiCrashGuardPolicy.onUncaughtCrash(
                state,
                nowMs = start + index * 1_000L,
            )
        }
        assertEquals(SystemUiCrashGuardPolicy.MAX_RECORDED_CRASHES, state.crashTimestampsMs.size)
        // 保留的是最近的记录。
        assertEquals(
            start + 19_000L,
            state.crashTimestampsMs.last(),
        )
    }

    @Test
    fun trippedStateDeniesUntilReenableNewerThanTrip() {
        val tripped = SystemUiCrashGuardState(
            lastStartAtMs = start,
            trippedAtMs = start + 100L,
            trippedReason = SystemUiCrashGuardPolicy.REASON_EARLY_CRASH_STREAK,
        )

        // 未请求重新启用：持续拒绝，且首次启动要求上报。
        val denied = SystemUiCrashGuardPolicy.onProcessStart(
            tripped,
            nowMs = start + 200L,
            reenableAtMs = ::neverReenable,
        )
        assertFalse(denied.admitted)
        assertTrue(denied.reportTrip)

        // 已上报过同一次禁用：继续拒绝但不再要求上报。
        val reported = SystemUiCrashGuardPolicy.onTripReported(denied.state)
        val deniedAgain = SystemUiCrashGuardPolicy.onProcessStart(
            reported,
            nowMs = start + 300L,
            reenableAtMs = ::neverReenable,
        )
        assertFalse(deniedAgain.admitted)
        assertFalse(deniedAgain.reportTrip)

        // 重新启用时间戳早于禁用时刻：仍拒绝（过期请求不算）。
        val staleReenable = SystemUiCrashGuardPolicy.onProcessStart(
            reported,
            nowMs = start + 400L,
            reenableAtMs = { start + 50L },
        )
        assertFalse(staleReenable.admitted)

        // 重新启用时间戳晚于禁用时刻：恢复放行并清空全部计数。
        val reenabled = SystemUiCrashGuardPolicy.onProcessStart(
            reported,
            nowMs = start + 500L,
            reenableAtMs = { start + 450L },
        )
        assertTrue(reenabled.admitted)
        assertNull(reenabled.state.trippedAtMs)
        assertEquals(0, reenabled.state.earlyCrashStreak)
        assertTrue(reenabled.state.crashTimestampsMs.isEmpty())
    }

    @Test
    fun lazyReenableReaderOnlyInvokedWhileTripped() {
        // 常规启动路径（未禁用）不应触发跨进程读取：lambda 抛错也不影响放行。
        val decision = SystemUiCrashGuardPolicy.onProcessStart(
            SystemUiCrashGuardState(),
            nowMs = start,
            reenableAtMs = { error("must not read reenable while not tripped") },
        )
        assertTrue(decision.admitted)
    }
}
