package hk.uwu.soundman.hook.scopes.systemui.runtime

import hk.uwu.soundman.model.EntryPosition

/**
 * 入口圆钮相对音量条锚点的落位算术，纯函数，JVM 单测直接验证。
 *
 * 只负责「下标 / 间距」这类能在纸面上算对的量：
 * 锚点如何查找、视图如何插入仍归 [SystemUiVolumeEntryRuntime]。
 */
object EntryPlacementPolicy {
    /**
     * 入口在锚点容器里的插入下标。
     *
     * 上方：占锚点自己的位置，把音量条顶下去。
     * 下方：紧跟在锚点之后。下标越界到 `childCount` 是合法的，
     * `ViewGroup.addView` 会当成追加处理。
     *
     * @param anchorIndex 锚点当前在父容器里的下标
     */
    fun insertIndex(anchorIndex: Int, position: EntryPosition): Int =
        if (position == EntryPosition.BELOW) anchorIndex + 1 else anchorIndex

    /**
     * 线性布局下入口的上下 margin。
     *
     * 官方是「音量条和相邻圆钮之间留一条 gap」，所以入口落在哪一侧就把 gap 留在哪一侧，
     * 另一侧贴 0 —— 不能两侧都留，否则会把面板整体撑高一个 gap。
     *
     * @param gap 官方音量条与相邻圆钮之间的空隙
     */
    fun verticalMargins(gap: Int, position: EntryPosition): EntryMargins =
        if (position == EntryPosition.BELOW) {
            EntryMargins(top = gap, bottom = 0)
        } else {
            EntryMargins(top = 0, bottom = gap)
        }

    /**
     * 官方「音量条 ↔ 静音/免打扰」间距的取值顺序。
     *
     * 背景（踩过的坑）：这个间距一旦用**实测距离**兜底就极易形成正反馈 ——
     * 入口插进去之后，静音/免打扰（或音量条）的位置已经被入口撑开了，
     * 此时再实测只会得到 `入口高度 + 上一轮间距`，下一次再撑开一点，
     * 于是每呼出一次音量条，按钮离音量条主体就远一截。
     *
     * 所以取值有严格优先级：
     * 1. 官方 margin（不受入口影响，永远可信）；
     * 2. 之前在「入口还没插入」时缓存下来的实测值；
     * 3. 以上都没有就退回 [fallback]，**绝不采信入口已插入 / 展开态时的实测值**。
     *
     * @param officialMargin 官方 layoutParams 上的 margin；`<= 0` 视为没有
     * @param cached 本轮之前缓存的实测间距
     * @param measured 本轮实测间距；布局未完成时为 null
     * @param entryPresent 入口此刻是否已经插在视图树里
     * @param collapsed 面板是否处于折叠态；展开态的排布与折叠态不是一回事
     * @param fallback 所有可信来源都缺失时的兜底间距
     * @param maxPx 实测值的合理上限。首次测量有可能撞上呼出动画（官方内容带
     *              scale/translation），量出来的距离会被放大；超过上限就按上限截断，
     *              宁可略小也不要把动画位移当成官方间距存下来
     * @return 解析结果；[OfficialGapResolution.cacheable] 为真时调用方应把它存进缓存
     */
    fun resolveGap(
        officialMargin: Int,
        cached: Int?,
        measured: Int?,
        entryPresent: Boolean,
        collapsed: Boolean,
        fallback: Int,
        maxPx: Int,
    ): OfficialGapResolution = when {
        officialMargin > 0 ->
            OfficialGapResolution(officialMargin, cacheable = false, source = OfficialGapSource.MARGIN)
        cached != null ->
            OfficialGapResolution(cached, cacheable = false, source = OfficialGapSource.CACHED)
        entryPresent ->
            OfficialGapResolution(fallback, cacheable = false, source = OfficialGapSource.FALLBACK)
        !collapsed ->
            OfficialGapResolution(fallback, cacheable = false, source = OfficialGapSource.FALLBACK)
        measured == null || measured <= 0 ->
            OfficialGapResolution(fallback, cacheable = false, source = OfficialGapSource.FALLBACK)
        else ->
            OfficialGapResolution(
                measured.coerceAtMost(maxPx),
                cacheable = true,
                source = OfficialGapSource.MEASURED,
            )
    }

    /**
     * 绝对定位的入口是否放得进父容器，**不会把父容器撑高**。
     *
     * 这是另一条漂移回路的开关（实测复现：每呼出一次竖条高 +150 = 入口 120 + 间距 30）：
     * 音量条 `volume_dialog_columns` 是 `MATCH_PARENT`，把 wrap_content 的 FrameLayout
     * 竖条整个填满；入口若被放到 `anchor.bottom + gap`（竖条**外面**），父容器就被撑高，
     * 锚点跟着被撑高，下一轮再按新的 `anchor.bottom` 定位 —— 无限累加。
     * 所以放不下时必须放弃绝对定位，改到外层纵向容器里插成一整行。
     *
     * @param topMargin 绝对定位的 topMargin
     * @param entryHeight 入口高度
     * @param parentHeight 父容器当前高度；`<= 0` 表示还没量过，先放行
     */
    fun fitsInsideParent(topMargin: Int, entryHeight: Int, parentHeight: Int): Boolean =
        parentHeight <= 0 || topMargin + entryHeight <= parentHeight

    /**
     * FrameLayout 落位的绝对 topMargin。
     *
     * FrameLayout 分支里锚点是按边界绝对定位的（无法用相邻子视图的下标推算）：
     * 上方要反推 `anchor.top - gap - 入口高度`，下方直接 `anchor.bottom + gap`。
     *
     * @param anchorTop 锚点 top（父容器坐标系）
     * @param anchorBottom 锚点 bottom（父容器坐标系）
     * @param entryHeight 入口高度
     * @param gap 官方间距
     * @return 目标 topMargin；允许负数，调用方负责判定放不下并降级
     */
    fun frameTopMargin(
        anchorTop: Int,
        anchorBottom: Int,
        entryHeight: Int,
        gap: Int,
        position: EntryPosition,
    ): Int = if (position == EntryPosition.BELOW) {
        anchorBottom + gap
    } else {
        anchorTop - gap - entryHeight
    }
}

/**
 * 入口在垂直方向上的两侧间距。
 *
 * @param top 上 margin 像素
 * @param bottom 下 margin 像素
 */
data class EntryMargins(
    val top: Int,
    val bottom: Int,
)

/** 官方间距的取值来源，只用于日志/诊断。 */
enum class OfficialGapSource {
    /** 官方 layoutParams 上的 margin，永远可信。 */
    MARGIN,

    /** 之前在「入口未插入」时缓存下来的实测值。 */
    CACHED,

    /** 本轮刚在干净状态下实测到的值。 */
    MEASURED,

    /** 没有可信来源，用了兜底常量。 */
    FALLBACK,
}

/**
 * 官方间距的解析结果。
 *
 * @param px 最终采用的间距像素
 * @param cacheable 是否值得缓存；只有「入口未插入 + 折叠态 + 实测有效」才为真
 * @param source 取值来源，写进日志便于定位漂移
 */
data class OfficialGapResolution(
    val px: Int,
    val cacheable: Boolean,
    val source: OfficialGapSource,
)
