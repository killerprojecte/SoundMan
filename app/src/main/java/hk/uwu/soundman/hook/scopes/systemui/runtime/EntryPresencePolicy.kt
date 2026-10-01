package hk.uwu.soundman.hook.scopes.systemui.runtime

import android.view.View
import hk.uwu.soundman.model.MediaPresence

/**
 * 侧栏入口可见性的纯策略：展开态 + 播放态两个条件合流。
 *
 * 动机：入口只应该在「有媒体应用正在播放」时出现，和音质音效那颗官方入口同一个前提；
 * 展开态本来就要藏（[SystemUiVolumeEntryLayout.entryVisibility]），两个条件都要满足才显示。
 * 探测不可用（[MediaPresence.UNKNOWN]）时保持显示：少显示一颗按钮只是少个入口，
 * 但如果因为 ROM 上反射不可用而永远不显示，用户会以为模块坏了。
 */
object EntryPresencePolicy {
    /**
     * 计算入口可见性。
     *
     * @param expanded 面板是否展开
     * @param presence 当前媒体播放判定
     * @return [View.VISIBLE] 或 [View.GONE]
     */
    fun visibility(expanded: Boolean, presence: MediaPresence): Int {
        val byExpanded = SystemUiVolumeEntryLayout.entryVisibility(expanded)
        if (byExpanded == View.GONE) return View.GONE
        return if (presence.hidesEntry) View.GONE else View.VISIBLE
    }

    /** [visibility] 的布尔形态，供日志与单测读。 */
    fun isVisible(expanded: Boolean, presence: MediaPresence): Boolean =
        visibility(expanded, presence) == View.VISIBLE
}
