package hk.uwu.soundman.model

/**
 * 当前「有没有正在播放的媒体应用」的判定结果。
 *
 * 三态而不是布尔：SystemUI 里的探测走反射读 `AudioPlaybackConfiguration`，
 * 任何一环失败（类变了、hidden API 被拦、binder 抛错）都不能当成「没在播放」——
 * 那会把入口永久藏掉。探测失败一律是 [UNKNOWN]，由策略按「宁可多显示」处理。
 */
enum class MediaPresence {
    /** 至少有一个媒体应用正在播放。 */
    PLAYING,

    /** 探测成功，当前确实没有媒体播放。 */
    IDLE,

    /** 探测不可用，结果未知。 */
    UNKNOWN,
    ;

    /** 是否应当把侧栏入口藏起来。 */
    val hidesEntry: Boolean
        get() = this == IDLE

    companion object {
        /**
         * 把探测结果折叠成三态。
         *
         * @param apps 探测到的正在播放的应用；`null` 表示探测本身失败了
         */
        fun from(apps: List<*>?): MediaPresence = when {
            apps == null -> UNKNOWN
            apps.isEmpty() -> IDLE
            else -> PLAYING
        }
    }
}
