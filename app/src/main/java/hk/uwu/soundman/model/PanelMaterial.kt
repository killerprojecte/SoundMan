package hk.uwu.soundman.model

/**
 * 内置多应用音量面板的材质来源。
 *
 * 与 [EntryMaterial] 的三档一一对应，但**不新增持久化键**：面板材质是从
 * 「跟随 HyperLight」与「自研液态玻璃」两个既有开关推导出来的（见
 * `AppSettings.panelMaterial`），这样老用户升级后原来的选择不会丢，也不需要迁移。
 *
 * 三档互斥的原因和入口那边一样：HyperLight 在展开面板上用的是它自己的玻璃，
 * 与 SoundMan 自研玻璃是两种观感，交给用户挑而不是替他假设。
 */
enum class PanelMaterial {
    /**
     * 跟随 HyperLight（默认）：走它 hook 展开面板用的同一个入口，观感与系统展开面板一致。
     *
     * 拿不到 HyperLight（未安装、未激活、或它自己关了液态玻璃）时，退回官方展开材质。
     */
    HYPERLIGHT,

    /** SoundMan 自研液态玻璃：不受 HyperLight 影响，此时才开放折射 / 模糊 / 混色参数。 */
    LIQUID,

    /** 官方展开材质：与系统面板同源，没有玻璃感。 */
    OFFICIAL,
    ;

    companion object {
        /** 没有存储值时的默认材质。 */
        val DEFAULT: PanelMaterial = HYPERLIGHT
    }
}
