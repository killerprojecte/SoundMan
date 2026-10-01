package hk.uwu.soundman.model

/**
 * SoundMan 侧栏入口圆钮相对 HyperOS 音量条的落位。
 *
 * 默认沿用「音量条上方」这一原始行为（`volume_dialog_columns` 锚点之前），
 * 用户可选择放到音量条下方，即 [BELOW]。
 *
 * @param storedValue 落盘字符串；跨进程读写只认这个串，不认 ordinal，
 *                    避免以后插入新档位时把已有用户的位置悄悄改掉。
 */
enum class EntryPosition(val storedValue: String) {
    /** 音量条上方（默认）：插在音量条锚点之前。 */
    ABOVE("above"),

    /** 音量条下方：插在音量条锚点之后，与静音/免打扰之间。 */
    BELOW("below"),
    ;

    companion object {
        /** 没有存储值时的默认落位。 */
        val DEFAULT: EntryPosition = ABOVE

        /**
         * 从持久化字符串还原落位。
         *
         * 这里刻意做「未知一律回退默认」而不是抛错：取值要穿到 SystemUI 进程，
         * 中途可能被别的写入者污染成任意类型/任意字符串，落位失败顶多回到官方默认外观，
         * 不该因为一个脏值让整颗入口按钮消失。
         *
         * @param value SharedPreferences 里读到的值，允许 null（首次安装）
         */
        fun fromStored(value: String?): EntryPosition =
            values().firstOrNull { it.storedValue == value } ?: DEFAULT
    }
}
