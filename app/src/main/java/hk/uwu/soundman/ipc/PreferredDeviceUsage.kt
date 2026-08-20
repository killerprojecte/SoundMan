package hk.uwu.soundman.ipc

import hk.uwu.soundman.ipc.PreferredDeviceUsage.USAGE_MEDIA
import hk.uwu.soundman.ipc.PreferredDeviceUsage.allocate


/**
 * 按占用的输出设备动态分配最多三条独立播放链路。
 *
 * 1. 只有一台设备占用：全部 [USAGE_MEDIA]，不伪装，走正常媒体通路。
 * 2. 两台：外放（蓝牙/USB）保持 MEDIA，新增占用伪装成铃声。
 * 3. 三台：第三条伪装成闹钟。
 *
 * Mix 拆开后各链路再 `setPreferredDevice` 钉到所选硬件。
 * FollowSystem 始终 MEDIA。contentType 不改，小米 ignore-focus 仍把 MUSIC 当可并播。
 *
 * **系统设备参与占用计算：** [allocate] 接收 [systemDevice] 参数，表示系统当前 MEDIA 输出设备。
 * FollowSystem 的 app 实际占用此设备，因此 [systemDevice] 被纳入 `occupiedKeys` 计算。
 * 当 FollowSystem 占用的系统设备与 forced 设备不同时，触发伪装，使各链路获得独立 mix。
 */
object PreferredDeviceUsage {
    const val USAGE_MEDIA = 1
    const val USAGE_ALARM = 4
    const val USAGE_NOTIFICATION_RINGTONE = 6

    const val STREAM_RING = 2
    const val STREAM_MUSIC = 3
    const val STREAM_ALARM = 4

    const val MAX_INDEPENDENT_DEVICES = 3

    private const val TYPE_BUILTIN_EARPIECE = 1
    private const val TYPE_BUILTIN_SPEAKER = 2

    /** 第 2、第 3 条链路：铃声、闹钟。 */
    private val POOL = intArrayOf(
        USAGE_NOTIFICATION_RINGTONE,
        USAGE_ALARM,
    )

    /**
     * 按占用的输出设备动态分配 usage。
     *
     * @param hints 当前全部 uid 的改道提示
     * @param systemDevice 系统当前 MEDIA 输出设备的公开身份；FollowSystem 的 app 占用此设备。
     *        为 null 时回退到只看 forced 设备的原行为。
     * @return uid → usage
     */
    fun allocate(
        hints: List<PreferredDeviceSync.RouteHint>,
        systemDevice: PreferredDeviceSync.DeviceSpec? = null,
    ): Map<Int, Int> {
        val systemKey = systemDevice?.let(::deviceKey)
        val occupiedKeys = hints.map { hint ->
            if (hint.followSystem) systemKey else deviceKey(hint)
        }.filterNotNull().distinct()
        val disguised = LinkedHashMap<String, Int>()
        if (occupiedKeys.size > 1) {
            occupiedDeviceKeys(hints, systemKey).drop(1).forEachIndexed { index, key ->
                check(index < POOL.size) {
                    "too many distinct occupied devices: ${index + 2}, max=$MAX_INDEPENDENT_DEVICES"
                }
                disguised[key] = POOL[index]
            }
        }
        return hints.associate { hint ->
            val usage = if (hint.followSystem) {
                USAGE_MEDIA
            } else {
                disguised[deviceKey(hint)] ?: USAGE_MEDIA
            }
            hint.uid to usage
        }
    }

    /** 把分配结果写回 hint。 */
    fun withAllocatedUsages(
        hints: List<PreferredDeviceSync.RouteHint>,
        systemDevice: PreferredDeviceSync.DeviceSpec? = null,
    ): List<PreferredDeviceSync.RouteHint> {
        val usages = allocate(hints, systemDevice)
        return hints.map { hint ->
            hint.copy(usage = usages[hint.uid] ?: USAGE_MEDIA)
        }
    }

    /** 是否需要在构造 Track 时改写 attributes / streamType。 */
    fun shouldRewrite(usage: Int): Boolean = usage != USAGE_MEDIA

    /** 伪装 usage 对应的 legacy stream，给旧 `AudioTrack(streamType, …)` 构造用。 */
    fun streamType(usage: Int): Int = when (usage) {
        USAGE_NOTIFICATION_RINGTONE -> STREAM_RING
        USAGE_ALARM -> STREAM_ALARM
        else -> STREAM_MUSIC
    }

    /** 把 usage 编成 logcat 可读名称。未知值保留数字。 */
    fun name(usage: Int): String = when (usage) {
        USAGE_MEDIA -> "MEDIA"
        USAGE_ALARM -> "ALARM"
        USAGE_NOTIFICATION_RINGTONE -> "RINGTONE"
        else -> usage.toString()
    }

    /** 描述一次全量分配，供动态路径对照。 */
    fun describe(hints: List<PreferredDeviceSync.RouteHint>): String =
        hints.joinToString(prefix = "[", postfix = "]") { hint ->
            "uid=${hint.uid} follow=${hint.followSystem} type=${hint.publicType} " +
                    "address=${hint.address.ifEmpty { "<empty>" }} usage=${name(hint.usage)}"
        }

    /**
     * 多设备时排序：系统设备排第一保持 MEDIA，其余外放（蓝牙/USB/有线）优先，本机最后才伪装。
     *
     * @param hints 全部 hint（含 FollowSystem）
     * @param systemKey 系统设备的 deviceKey（可能为 null）
     */
    private fun occupiedDeviceKeys(
        hints: List<PreferredDeviceSync.RouteHint>,
        systemKey: String?,
    ): List<String> =
        hints.map { hint ->
            if (hint.followSystem) {
                systemKey?.let { it to 0 }
            } else {
                deviceKey(hint) to hint.publicType
            }
        }.filterNotNull()
            .distinctBy { it.first }
            .sortedWith(
                compareBy(
                    { !isSystem(it.first, systemKey) },
                    { isBuiltin(it.second) },
                    { it.second },
                    { it.first })
            )
            .map { it.first }

    private fun deviceKey(hint: PreferredDeviceSync.RouteHint): String =
        "${hint.publicType}|${hint.address}"

    private fun deviceKey(spec: PreferredDeviceSync.DeviceSpec): String =
        "${spec.publicType}|${spec.address}"

    private fun isSystem(key: String, systemKey: String?): Boolean =
        systemKey != null && key == systemKey

    private fun isBuiltin(publicType: Int): Boolean =
        publicType == TYPE_BUILTIN_EARPIECE || publicType == TYPE_BUILTIN_SPEAKER
}
