package hk.uwu.soundman.hook.scopes.systemui.hidden

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import hk.uwu.soundman.hook.scopes.system.hidden.MediaPlaybackAccess
import hk.uwu.soundman.hook.scopes.system.hidden.MediaPlaybackProbe
import hk.uwu.soundman.hook.scopes.system.hidden.PlaybackConfigurationAccess

/**
 * 一条正在播放的媒体应用。
 *
 * @param uid 客户端 uid
 * @param packageName uid 对应的包名；`PackageManager` 查不到时为 null（仍能算「在播放」）
 */
data class ActiveMediaApp(
    val uid: Int,
    val packageName: String?,
)

/**
 * SystemUI 里探测「当前有哪些媒体应用正在播放」。
 *
 * 过滤规则与 system_server 侧快照同源（[MediaPlaybackProbe]），也就是音质音效
 * `com.miui.misound.playervolume.f.a(Context)` 的那套：
 * - uid >= 10000；
 * - 排除 `com.miui.miwallpaper`；
 * - `getPlayerState() == 2`（PLAYER_STATE_STARTED）；
 * - `usage == USAGE_MEDIA(1)` 或 `volumeControlStream == STREAM_MUSIC(3)`。
 *
 * 复用同一个 probe 而不是另写一份：入口「显示」的条件必须和面板「列出来」的条件一致，
 * 否则会出现「按钮在、点开却是空的」或者反过来。
 *
 * 探测失败一律返回 null（未知），由 [hk.uwu.soundman.model.MediaPresence] 折叠成 UNKNOWN，
 * 调用方按「宁可多显示」处理 —— 反射在 ROM 上不可用时不能把入口永久藏掉。
 */
class SystemUiMediaPlayback(
    private val log: (message: String, throwable: Throwable?) -> Unit,
) {
    private val configurationAccess = PlaybackConfigurationAccess()
    private val mediaAccess = MediaPlaybackAccess()

    /**
     * 探测当前正在播放的媒体应用。
     *
     * @param context SystemUI（或插件）Context
     * @return 正在播放的应用；探测失败时为 null
     */
    fun probe(context: Context): List<ActiveMediaApp>? {
        val packageManager = context.packageManager ?: return null
        val audioManager = context.getSystemService(AudioManager::class.java)
        if (audioManager == null) {
            log("[presence] AudioManager unavailable in ${context.javaClass.name}", null)
            return null
        }
        var failures = 0
        val probe = MediaPlaybackProbe(
            configurationAccess,
            mediaAccess,
            // 与宿主快照一致：共享 uid 时取排序后的第一个包名。
            { uid -> runCatching { packageManager.getPackagesForUid(uid)?.sorted()?.firstOrNull() }.getOrNull() },
        ) { message, throwable ->
            failures += 1
            logError(message, throwable)
        }
        return try {
            val configurations = audioManager.activePlaybackConfigurations.orEmpty()
            val probed = probe.probeConfigurations(configurations)
            // 有播放配置却一条都没读出来 = 反射被拦（hidden API）或字段变了，不是「没在播放」。
            // 这种情况必须报 UNKNOWN 让入口继续显示，否则入口会在这些 ROM 上永远消失。
            if (probed.isEmpty() && configurations.isNotEmpty() && failures > 0) {
                logError(
                    "[presence] playback probe read nothing from ${configurations.size} configs " +
                        "($failures read failures); treating presence as unknown",
                    null,
                )
                null
            } else {
                probed.map { ActiveMediaApp(it.uid, packageNameOf(packageManager, it.uid)) }
            }
        } catch (throwable: Throwable) {
            logError("[presence] media playback probe failed", throwable)
            null
        }
    }

    private fun packageNameOf(packageManager: PackageManager, uid: Int): String? =
        runCatching { packageManager.getPackagesForUid(uid)?.sorted()?.firstOrNull() }.getOrNull()

    private fun logError(message: String, throwable: Throwable?) {
        log(message, throwable)
    }
}
