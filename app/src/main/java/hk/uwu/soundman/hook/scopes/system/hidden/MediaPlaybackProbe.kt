package hk.uwu.soundman.hook.scopes.system.hidden

import android.media.AudioManager

/**
 * 按 MiSound 多应用音量规则探测正在播放的媒体应用。
 *
 * 动机：官方面板只列媒体 STARTED、应用 uid、排除壁纸，同一 uid 只留一条。
 * 快照仍在 system_server 生成，调音仍走 IPlayer。本类只替换「谁算正在播放」。
 * 单条配置的必需字段失败会打日志并跳过该条；可选 piid / IPlayer 失败时仍保留 uid。
 */
class MediaPlaybackProbe(
    private val access: PlaybackConfigurationAccess,
    private val mediaAccess: MediaPlaybackAccess,
    private val packageNameForUid: (uid: Int) -> String?,
    private val logError: (message: String, throwable: Throwable) -> Unit,
) : PlaybackProbe {
    /**
     * 读取公开 `getActivePlaybackConfigurations()`，按媒体规则过滤。
     *
     * @param audioManager system_server 内的 AudioManager
     */
    override fun probe(audioManager: AudioManager): List<ProbedPlayback> =
        probeConfigurations(audioManager.activePlaybackConfigurations)

    /**
     * 对任意配置列表走同一套过滤，供生产 `AudioPlaybackConfiguration` 与单测假类共用。
     *
     * @param configs `getActivePlaybackConfigurations()` 的元素，或等价假对象
     */
    fun probeConfigurations(configs: List<*>): List<ProbedPlayback> {
        val byUid = LinkedHashMap<Int, ProbedPlayback>()
        configs.forEach { config ->
            if (config == null) {
                logError(
                    "[snapshot] skipped null playback configuration",
                    NullPointerException("playback configuration is null"),
                )
                return@forEach
            }
            val uid =
                readRequired(config, "getClientUid") { access.clientUid(config) } ?: return@forEach
            val playerState =
                readRequired(config, "getPlayerState") { mediaAccess.playerState(config) }
                    ?: return@forEach
            val usage =
                readRequired(config, "getUsage") { mediaAccess.usage(config) } ?: return@forEach
            val stream = readRequired(config, "getVolumeControlStream") {
                mediaAccess.volumeControlStream(config)
            } ?: return@forEach
            if (!matchesMediaFilter(uid, playerState, usage, stream, packageNameForUid(uid))) {
                return@forEach
            }
            val piid =
                readOptional(config, "getPlayerInterfaceId") { access.playerInterfaceId(config) }
            val player = readOptional(config, "getPlayerProxy") { access.player(config) }
            byUid[uid] = ProbedPlayback(uid, piid, player)
        }
        return ArrayList(byUid.values)
    }

    private fun <T> readRequired(config: Any, methodName: String, read: () -> T): T? = try {
        read()
    } catch (throwable: Throwable) {
        logError(
            "[snapshot] failed to read $methodName from ${config.javaClass.name}",
            throwable,
        )
        null
    }

    private fun <T> readOptional(config: Any, methodName: String, read: () -> T): T? = try {
        read()
    } catch (throwable: Throwable) {
        logError(
            "[snapshot] failed to read optional $methodName from ${config.javaClass.name}",
            throwable,
        )
        null
    }

    private companion object {
        const val FIRST_APPLICATION_UID = 10000
        const val PLAYER_STATE_STARTED = 2
        const val USAGE_MEDIA = 1
        const val USAGE_NOTIFICATION_RINGTONE = 6
        const val USAGE_ALARM = 4
        const val STREAM_MUSIC = 3
        const val WALLPAPER_PACKAGE = "com.miui.miwallpaper"

        fun matchesMediaFilter(
            uid: Int,
            playerState: Int,
            usage: Int,
            volumeControlStream: Int,
            packageName: String?,
        ): Boolean {
            if (uid < FIRST_APPLICATION_UID) return false
            if (playerState != PLAYER_STATE_STARTED) return false
            if (!isAllowedPackage(packageName)) return false
            return usage == USAGE_MEDIA ||
                    usage == USAGE_NOTIFICATION_RINGTONE ||
                    usage == USAGE_ALARM ||
                    volumeControlStream == STREAM_MUSIC
        }

        fun isAllowedPackage(packageName: String?): Boolean =
            packageName.isNullOrEmpty() || packageName != WALLPAPER_PACKAGE
    }
}
