package hk.uwu.soundman.ipc

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.highcapable.kavaref.extension.classOf
import com.highcapable.yukihookapi.hook.factory.prefs
import hk.uwu.soundman.log.AppLog
import hk.uwu.soundman.model.AppAudioRule
import hk.uwu.soundman.model.OutputDeviceType
import hk.uwu.soundman.model.OutputTarget

/**
 * 模块 App 与被注入进程之间的强制输出设备同步通道。
 *
 * 动机：system_server 不再挑选扬声器/蓝牙候选，也不再写 Settings.Global。
 * 用户选设备后由模块进程解析公开 `AudioDeviceInfo.type` + address，
 * 再通过广播和模块 prefs 告诉各应用进程：单设备保持 MEDIA，多设备才把新增占用伪装成铃声/闹钟。
 */
object PreferredDeviceSync {
    /** 强制设备广播 Action。 */
    const val ACTION = "hk.uwu.soundman.action.PREFERRED_DEVICE"

    /** 目标应用 uid。 */
    const val EXTRA_UID = "uid"

    /** 是否跟随系统；为 true 时不伪装 usage，保持 MEDIA。 */
    const val EXTRA_FOLLOW_SYSTEM = "followSystem"

    /** 公开 `AudioDeviceInfo.type`。FollowSystem 时为 0。 */
    const val EXTRA_PUBLIC_TYPE = "publicType"

    /** 设备地址；本机扬声器可为空串。 */
    const val EXTRA_ADDRESS = "address"

    /** 动态分配的 AudioAttributes.usage。FollowSystem / 默认 MUSIC 为 1。 */
    const val EXTRA_USAGE = "usage"

    /** 模块侧冷启动提示的 SharedPreferences 名。 */
    const val PREFS_NAME = "soundman_route_hints"

    /** prefs 值分隔符。格式为 `publicType|address`。 */
    const val VALUE_SEPARATOR = '|'

    /**
     * 一条按 uid 下发的强制改道提示。
     *
     * @param uid 目标应用 uid
     * @param followSystem 跟随系统时不强制设备
     * @param publicType 公开 `AudioDeviceInfo.type`；跟随系统时为 0
     * @param address 设备地址；跟随系统时为空串
     */
    data class RouteHint(
        val uid: Int,
        val followSystem: Boolean,
        val publicType: Int,
        val address: String,
        val usage: Int = PreferredDeviceUsage.USAGE_MEDIA,
    ) {
        init {
            require(uid >= 0) { "uid must be non-negative" }
            if (followSystem) {
                require(publicType == 0) { "follow-system publicType must be 0" }
                require(address.isEmpty()) { "follow-system address must be empty" }
            } else {
                require(publicType > 0) { "publicType must be a concrete AudioDeviceInfo.type" }
            }
            require(usage > 0) { "usage must be a concrete AudioAttributes.usage" }
        }

        /** 强制设备规格；跟随系统时为 null。 */
        val spec: DeviceSpec?
            get() = if (followSystem) null else DeviceSpec(publicType, address)
    }

    /**
     * 公开设备身份。应用进程用 type + address 在 `getDevices` 里查找。
     *
     * @param publicType 公开 `AudioDeviceInfo.type`
     * @param address 设备地址；本机可空
     */
    data class DeviceSpec(
        val publicType: Int,
        val address: String,
    ) {
        init {
            require(publicType > 0) { "publicType must be a concrete AudioDeviceInfo.type" }
        }
    }

    /**
     * 模块进程里用于匹配的公开输出设备快照。
     *
     * 动机：单测不能依赖真实 `AudioDeviceInfo`，只断言 type / address / 产品名。
     *
     * @param publicType 公开 `AudioDeviceInfo.type`
     * @param address 设备地址
     * @param productName 产品名，不参与身份持久化
     */
    data class PublicDevice(
        val publicType: Int,
        val address: String,
        val productName: String,
    )

    /** 跟随系统提示。 */
    fun followSystem(uid: Int): RouteHint = RouteHint(uid, followSystem = true, publicType = 0, address = "")

    /** 强制到指定公开设备。 */
    fun forced(uid: Int, publicType: Int, address: String): RouteHint =
        RouteHint(uid, followSystem = false, publicType = publicType, address = address)

    /** prefs 里该 uid 的键。 */
    fun prefsKey(uid: Int): String {
        require(uid >= 0) { "uid must be non-negative" }
        return uid.toString()
    }

    /**
     * 把公开类型和地址编成 prefs 值。
     *
     * @param publicType 公开 `AudioDeviceInfo.type`
     * @param address 设备地址；本机扬声器为空串
     */
    fun encodePrefsValue(publicType: Int, address: String): String {
        require(publicType > 0) { "publicType must be a concrete AudioDeviceInfo.type" }
        return "$publicType$VALUE_SEPARATOR$address"
    }

    /** 把提示编成 prefs 值。FollowSystem 写空串。 */
    fun encodePrefsValue(hint: RouteHint): String =
        if (hint.followSystem) "" else encodePrefsValue(hint.publicType, hint.address)

    /**
     * 解析 prefs 值。空串或 null 表示没有强制设备。
     *
     * @return 公开类型与地址；无规则或跟随系统时为 null
     */
    fun decodePrefsValue(value: String?): DeviceSpec? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val separator = raw.indexOf(VALUE_SEPARATOR)
        require(separator > 0) { "preferred device prefs must be publicType|address: $raw" }
        val publicType = raw.substring(0, separator).toIntOrNull()
            ?: error("preferred device publicType is not an Int: $raw")
        require(publicType > 0) { "publicType must be a concrete AudioDeviceInfo.type: $raw" }
        return DeviceSpec(publicType, raw.substring(separator + 1))
    }

    /**
     * 广播 extras 的纯数据形态。
     *
     * 动机：JVM 单测里 Intent extras 不便直接断言，编解码必须是可往返的纯函数。
     */
    fun extras(hint: RouteHint): Map<String, Any> = linkedMapOf(
        EXTRA_UID to hint.uid,
        EXTRA_FOLLOW_SYSTEM to hint.followSystem,
        EXTRA_PUBLIC_TYPE to hint.publicType,
        EXTRA_ADDRESS to hint.address,
        EXTRA_USAGE to hint.usage,
    )

    /**
     * 从 extras 解析强制设备广播。缺字段或类型不对立即失败。
     */
    fun decodeExtras(values: Map<String, Any?>): RouteHint {
        if (!values.containsKey(EXTRA_UID)) error("missing extra: $EXTRA_UID")
        val uid = values[EXTRA_UID] as? Int ?: error("invalid extra: $EXTRA_UID")
        if (!values.containsKey(EXTRA_FOLLOW_SYSTEM)) error("missing extra: $EXTRA_FOLLOW_SYSTEM")
        val followSystem = values[EXTRA_FOLLOW_SYSTEM] as? Boolean ?: error("invalid extra: $EXTRA_FOLLOW_SYSTEM")
        if (!values.containsKey(EXTRA_PUBLIC_TYPE)) error("missing extra: $EXTRA_PUBLIC_TYPE")
        val publicType = values[EXTRA_PUBLIC_TYPE] as? Int ?: error("invalid extra: $EXTRA_PUBLIC_TYPE")
        if (!values.containsKey(EXTRA_ADDRESS)) error("missing extra: $EXTRA_ADDRESS")
        val address = values[EXTRA_ADDRESS] as? String ?: error("invalid extra: $EXTRA_ADDRESS")
        val usage = if (values.containsKey(EXTRA_USAGE)) {
            values[EXTRA_USAGE] as? Int ?: error("invalid extra: $EXTRA_USAGE")
        } else {
            PreferredDeviceUsage.USAGE_MEDIA
        }
        return RouteHint(uid, followSystem, publicType, address, usage)
    }

    /**
     * 组装要发给被注入进程的广播。不指定包名，由各进程自己按 uid 过滤。
     */
    fun intent(hint: RouteHint): Intent {
        val encoded = extras(hint)
        return Intent(ACTION)
            .putExtra(EXTRA_UID, encoded.getValue(EXTRA_UID) as Int)
            .putExtra(EXTRA_FOLLOW_SYSTEM, encoded.getValue(EXTRA_FOLLOW_SYSTEM) as Boolean)
            .putExtra(EXTRA_PUBLIC_TYPE, encoded.getValue(EXTRA_PUBLIC_TYPE) as Int)
            .putExtra(EXTRA_ADDRESS, encoded.getValue(EXTRA_ADDRESS) as String)
            .putExtra(EXTRA_USAGE, encoded.getValue(EXTRA_USAGE) as Int)
    }

    /**
     * 从广播 Intent 解析提示。
     */
    fun decodeIntent(intent: Intent): RouteHint {
        val extras = intent.extras ?: error("preferred device broadcast missing extras")
        val values = LinkedHashMap<String, Any?>()
        if (extras.containsKey(EXTRA_UID)) values[EXTRA_UID] = extras.getInt(EXTRA_UID)
        if (extras.containsKey(EXTRA_FOLLOW_SYSTEM)) values[EXTRA_FOLLOW_SYSTEM] = extras.getBoolean(EXTRA_FOLLOW_SYSTEM)
        if (extras.containsKey(EXTRA_PUBLIC_TYPE)) values[EXTRA_PUBLIC_TYPE] = extras.getInt(EXTRA_PUBLIC_TYPE)
        if (extras.containsKey(EXTRA_ADDRESS)) values[EXTRA_ADDRESS] = extras.getString(EXTRA_ADDRESS)
        if (extras.containsKey(EXTRA_USAGE)) values[EXTRA_USAGE] = extras.getInt(EXTRA_USAGE)
        return decodeExtras(values)
    }

    /**
     * 按公开 type + address 从输出设备列表里找出 [AudioDeviceInfo]。
     */
    fun findDevice(devices: Array<AudioDeviceInfo>, spec: DeviceSpec): AudioDeviceInfo? =
        devices.firstOrNull { device ->
            matches(device.type, device.address, spec)
        }

    /**
     * 判断公开设备是否就是这条强制规格。
     *
     * 蓝牙 MAC 在没有附近设备权限的进程里会被匿名成 `XX:XX:XX:XX:AB:CD`，
     * 只比最后两段，避免钉设备时对不上。
     */
    fun matches(publicType: Int, address: String, spec: DeviceSpec): Boolean =
        publicType == spec.publicType && addressesMatch(address, spec.address)

    /**
     * 比较设备地址。完全一致，或一方为匿名 MAC 且后缀相同，都算命中。
     */
    fun addressesMatch(left: String, right: String): Boolean {
        if (left == right) return true
        val a = normalizeAddress(left)
        val b = normalizeAddress(right)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (a.length < 4 || b.length < 4) return false
        val anonymized = isAnonymizedAddress(left) || isAnonymizedAddress(right)
        return anonymized && a.takeLast(4) == b.takeLast(4)
    }

    /**
     * 把规则目标解析成公开 type + address。
     *
     * 匹配顺序：候选 address → 产品名 → 输出类型。匹配失败立即失败，不得改成 FollowSystem。
     *
     * @param devices 模块进程 `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` 的快照
     * @param target 用户选中的固定设备
     */
    fun resolvePublicDevice(devices: List<PublicDevice>, target: OutputTarget.Device): DeviceSpec {
        val addresses = target.candidates.map { candidate -> candidate.address }
            .filter { address -> address.isNotEmpty() && !isDummyAddress(address) }
            .toSet()
        val byAddress = if (addresses.isEmpty()) {
            emptyList()
        } else {
            devices.filter { device -> device.address in addresses }
        }
        val byName = if (target.productName.isBlank()) {
            emptyList()
        } else {
            devices.filter { device -> device.productName == target.productName }
        }
        val byType = devices.filter { device -> categoryOf(device.publicType) == target.type }
        val pool = when {
            byAddress.isNotEmpty() -> byAddress
            byName.isNotEmpty() -> byName
            byType.isNotEmpty() -> byType
            else -> emptyList()
        }
        if (pool.isEmpty()) {
            error(
                "Failed to resolve public device type=${target.type} " +
                    "name=${target.productName} addresses=$addresses",
            )
        }
        val picked = pickPreferred(pool, target.type)
        return DeviceSpec(picked.publicType, picked.address)
    }

    /**
     * 把规则编成广播提示。FollowSystem 不查设备；固定设备必须在当前输出列表里解析到公开 type。
     */
    fun hintFor(devices: List<PublicDevice>, uid: Int, target: OutputTarget): RouteHint = when (target) {
        OutputTarget.FollowSystem -> followSystem(uid)
        is OutputTarget.Device -> {
            val spec = resolvePublicDevice(devices, target)
            forced(uid, spec.publicType, spec.address)
        }
    }

    /**
     * 解析、写入模块 prefs，并广播给被注入进程。
     *
     * 匹配失败打日志并抛出，不得默默写成 FollowSystem。
     *
     * @param systemDevice 系统当前 MEDIA 输出设备，用于 allocate 伪装判断
     */
    fun publish(
        context: Context,
        uid: Int,
        target: OutputTarget,
        systemDevice: DeviceSpec? = null
    ) {
        val audioManager = context.getSystemService(classOf<AudioManager>())
            ?: error("AudioManager unavailable while publishing preferred device uid=$uid")
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device ->
            PublicDevice(
                publicType = device.type,
                address = device.address,
                productName = device.productName?.toString().orEmpty(),
            )
        }
        val hint = try {
            hintFor(devices, uid, target)
        } catch (error: Throwable) {
            AppLog.error("[route] failed to resolve public device uid=$uid target=$target", error)
            throw error
        }
        AppLog.info(
            "[route] publish uid=${hint.uid} follow=${hint.followSystem} publicType=${hint.publicType} " +
                    "address=${hint.address.ifEmpty { "<empty>" }} target=$target",
        )
        persist(context, hint)
        rebroadcastAllocated(context, systemDevice)
    }

    /**
     * 把已持久化规则按设备重新分配 usage 后广播。单条失败只打日志。
     *
     * @param systemDevice 系统当前 MEDIA 输出设备，用于 allocate 伪装判断
     */
    fun publishAll(
        context: Context,
        rules: Collection<AppAudioRule>,
        systemDevice: DeviceSpec? = null
    ) {
        val audioManager = context.getSystemService(classOf<AudioManager>())
            ?: error("AudioManager unavailable while publishing preferred devices")
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device ->
            PublicDevice(
                publicType = device.type,
                address = device.address,
                productName = device.productName?.toString().orEmpty(),
            )
        }
        AppLog.info(
            "[route] publishAll rules=${rules.size} devices=${devices.size} " +
                    devices.joinToString(prefix = "[", postfix = "]") { device ->
                        "type=${device.publicType} address=${device.address.ifEmpty { "<empty>" }} name=${device.productName}"
                    },
        )
        rules.forEach { rule ->
            try {
                val hint = hintFor(devices, rule.uid, rule.effectiveOutputTarget)
                AppLog.info(
                    "[route] publishAll persist uid=${hint.uid} follow=${hint.followSystem} " +
                            "publicType=${hint.publicType} address=${hint.address.ifEmpty { "<empty>" }}",
                )
                persist(context, hint)
            } catch (error: Throwable) {
                AppLog.error("[route] persist failed uid=${rule.uid}", error)
            }
        }
        rebroadcastAllocated(context, systemDevice)
    }

    /**
     * 读取当前全部 uid 提示，按设备分配 usage 后全部重播。
     *
     * @param systemDevice 系统当前 MEDIA 输出设备，用于 allocate 伪装判断
     */
    fun rebroadcastAllocated(context: Context, systemDevice: DeviceSpec? = null) {
        val allocated =
            PreferredDeviceUsage.withAllocatedUsages(loadStoredHints(context), systemDevice)
        AppLog.info(
            "[route] rebroadcast count=${allocated.size} ${PreferredDeviceUsage.describe(allocated)}",
        )
        allocated.forEach { hint ->
            AppLog.info("[route] broadcast ${describe(hint)}")
            context.sendBroadcast(intent(hint).addFlags(Intent.FLAG_RECEIVER_FOREGROUND))
        }
    }

    /** 从模块 prefs 还原全部 uid 提示，供动态分配 usage。 */
    fun loadStoredHints(context: Context): List<RouteHint> =
        hintsFromEntries(context.prefs(PREFS_NAME).all())

    /**
     * 把 prefs 键值还原成 hint。模块进程和宿主冷启动都走 YukiHook prefs。
     *
     * @param entries `uid -> publicType|address`；空串表示跟随系统
     */
    fun hintsFromEntries(entries: Map<String, *>): List<RouteHint> =
        entries.mapNotNull { (key, value) ->
            val uid = key.toIntOrNull() ?: return@mapNotNull null
            val spec = decodePrefsValue(value as? String)
            if (spec == null) followSystem(uid) else forced(uid, spec.publicType, spec.address)
        }

    /**
     * 按当前全量 prefs 给某个 uid 分配 usage。没有该 uid 的记录时返回 null。
     *
     * 动机：开机后模块 App 不会自动起来，广播到不了。各进程必须自己读 prefs、
     * 按全量 hint 重算 usage，才能在第一支 Track 构造前改掉 MUSIC Mix。
     */
    fun allocatedHintForUid(
        entries: Map<String, *>,
        uid: Int,
        systemDevice: DeviceSpec? = null
    ): RouteHint? {
        require(uid >= 0) { "uid must be non-negative" }
        return PreferredDeviceUsage.withAllocatedUsages(hintsFromEntries(entries), systemDevice)
            .firstOrNull { hint -> hint.uid == uid }
    }

    /** 单条 hint 的 logcat 文案。 */
    fun describe(hint: RouteHint): String =
        "uid=${hint.uid} follow=${hint.followSystem} publicType=${hint.publicType} " +
                "address=${hint.address.ifEmpty { "<empty>" }} usage=${
                    PreferredDeviceUsage.name(
                        hint.usage
                    )
                }"

    /**
     * 把提示写进模块 `soundman_route_hints`。FollowSystem 写空串，给冷启动读。
     *
     * 模块进程和宿主都用 YukiHook prefs。
     */
    fun persist(context: Context, hint: RouteHint) {
        val key = prefsKey(hint.uid)
        val value = encodePrefsValue(hint)
        val prefs = context.prefs(PREFS_NAME)
        prefs.edit {
            putString(key, value)
        }
        AppLog.info(
            "[route] persist uid=${hint.uid} value=${value.ifEmpty { "<follow>" }} " +
                    "available=${prefs.isPreferencesAvailable}",
        )
    }

    /**
     * 公开 `TYPE_*` 对应的规则分类。
     */
    fun categoryOf(publicType: Int): OutputDeviceType = when (publicType) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputDeviceType.BUILT_IN
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> OutputDeviceType.WIRED_HEADSET
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputDeviceType.BLUETOOTH
        AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET -> OutputDeviceType.USB
        else -> OutputDeviceType.OTHER
    }

    private fun pickPreferred(devices: List<PublicDevice>, type: OutputDeviceType): PublicDevice {
        val preferredType = when (type) {
            OutputDeviceType.BUILT_IN -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            OutputDeviceType.BLUETOOTH -> AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            OutputDeviceType.WIRED_HEADSET -> AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            OutputDeviceType.USB -> AudioDeviceInfo.TYPE_USB_HEADSET
            OutputDeviceType.OTHER -> null
        }
        val preferred = preferredType?.let { expected ->
            devices.firstOrNull { device -> device.publicType == expected }
        }
        if (preferred != null) return preferred
        if (type == OutputDeviceType.BLUETOOTH) {
            val ble = devices.firstOrNull { device -> device.publicType == AudioDeviceInfo.TYPE_BLE_SPEAKER }
                ?: devices.firstOrNull { device -> device.publicType == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: devices.firstOrNull { device -> device.publicType == AudioDeviceInfo.TYPE_BLE_BROADCAST }
            if (ble != null) return ble
        }
        return devices.first()
    }

    private fun isDummyAddress(address: String): Boolean {
        val hex = normalizeAddress(address)
        return hex.isEmpty() || hex.all { digit -> digit == '0' }
    }

    private fun isAnonymizedAddress(address: String): Boolean =
        address.contains("XX", ignoreCase = true)

    private fun normalizeAddress(address: String): String =
        address.filter(Char::isLetterOrDigit).uppercase()
}
