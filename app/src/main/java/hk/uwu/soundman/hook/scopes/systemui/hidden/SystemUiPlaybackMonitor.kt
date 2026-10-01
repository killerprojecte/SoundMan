package hk.uwu.soundman.hook.scopes.systemui.hidden

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper

/**
 * 播放配置变化监听：面板已经显示时，音频才开始/停止播放也要重算入口可见性。
 *
 * 动机：入口可见性原本只在 `onFinishInflate` / `onAttachedToWindow` / `updateExpandedH`
 * 这些官方回调里算，而音量面板一呼出就要停在那儿好几秒——这段时间里开始或结束播放
 * 完全没有触发点，入口会一直停在打开音量条那一刻的状态。
 * `AudioManager.registerAudioPlaybackCallback` 是唯一的补齐手段。
 *
 * 注册一次就够（SystemUI 进程生命期），重复调用直接返回；失败只打日志，
 * 退化为「只在官方回调时重算」，不影响入口本身。
 */
class SystemUiPlaybackMonitor(
    private val onChange: () -> Unit,
    private val log: (message: String, throwable: Throwable?) -> Unit,
) {
    // 延迟取主线程 Handler：只在真正注册回调时才会碰到 Looper，
    // 构造期碰 Looper 会让 JVM 单测（android.jar 未 mock）直接炸在 hooker 的静态初始化里。
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var registered = false

    private var callback: AudioManager.AudioPlaybackCallback? = null

    /** 注册播放回调；已经注册过或注册失败都静默返回。 */
    fun register(context: Context) {
        if (registered) return
        val audioManager = context.getSystemService(AudioManager::class.java)
        if (audioManager == null) {
            log("Audio playback callback skipped: AudioManager unavailable", null)
            return
        }
        val callback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                mainHandler.post { onChange() }
            }
        }
        try {
            audioManager.registerAudioPlaybackCallback(callback, mainHandler)
            this.callback = callback
            registered = true
            log("Registered AudioPlaybackCallback for volume entry presence", null)
        } catch (throwable: Throwable) {
            log("Unable to register AudioPlaybackCallback", throwable)
        }
    }

    /** 回调是否注册成功，供日志/诊断读取。 */
    val isRegistered: Boolean
        get() = registered
}
