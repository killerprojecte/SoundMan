package hk.uwu.soundman.model

/**
 * SoundMan 侧栏入口圆钮的材质来源。
 *
 * 三档的取舍来自实测：HyperLight 在音量条（components 场景）上用的其实是官方
 * 高光材质，它的自研液态玻璃只用在 nc / cc / desktop / headsup / keyguard。
 * 所以「和音量条一致」与「用上 HyperLight 的液态玻璃」是两种不同的观感，
 * 交给用户自己挑，而不是替他假设。
 *
 * @param storedValue 落盘字符串；跨进程读写只认这个串，不认 ordinal，
 *                    避免以后插入新档位时把已有用户的选择悄悄改掉。
 */
enum class EntryMaterial(val storedValue: String) {
    /**
     * 跟随 HyperLight（默认）：复用它的液态玻璃渲染器与它自己的参数。
     *
     * 拿不到 HyperLight（未安装、未激活、或它关了液态玻璃）时，退回 [LIQUID] 的降级链，
     * 不会让入口变成没有材质的裸按钮。
     */
    HYPERLIGHT("hyperlight"),

    /**
     * SoundMan 自研液态玻璃：与内置展开面板同款，不受 HyperLight 影响。
     *
     * 想要「无论 HyperLight 怎么设、入口都要有玻璃」就选这一档。
     */
    LIQUID("liquid"),

    /** 官方 MIUIX 高光材质：与音量条 / 控制中心组件同源，没有玻璃感。 */
    COMPONENT("component"),
    ;

    companion object {
        /** 没有存储值时的默认材质。 */
        val DEFAULT: EntryMaterial = HYPERLIGHT

        /**
         * 从持久化字符串还原材质。
         *
         * 与 [EntryPosition.fromStored] 同一个口径：取值要穿到 SystemUI 进程，
         * 中途可能被别的写入者污染成任意字符串，未知值一律回退默认。
         *
         * @param value SharedPreferences 里读到的值，允许 null（首次安装）
         */
        fun fromStored(value: String?): EntryMaterial =
            entries.firstOrNull { it.storedValue == value } ?: DEFAULT
    }
}
