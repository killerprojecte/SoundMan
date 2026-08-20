package hk.uwu.soundman.hook.scopes.system.hidden

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.makeAccessible
import com.highcapable.kavaref.extension.toClass
import hk.uwu.soundman.hook.core.YLog
import hk.uwu.soundman.hook.scopes.system.hidden.SystemMediaDeviceProbe.Companion.createForAppProcess
import hk.uwu.soundman.ipc.PreferredDeviceSync

/**
 * 探测系统当前 MEDIA 输出设备的公开身份。
 *
 * 动机：[hk.uwu.soundman.ipc.PreferredDeviceUsage.allocate] 需要知道 FollowSystem 的 app
 * 实际占用哪个设备，才能正确判断是否需要伪装 usage。
 *
 * **不使用设备优先级猜测。** 用户可以在系统设置中选择输出设备（如连接了蓝牙但选择扬声器），
 * 因此必须查询 `AudioSystem.getDevicesForStream` 获取 AudioPolicy 的实际路由结果。
 *
 * @param getDevicesForStream 调用 `AudioSystem.getDevicesForStream(streamType)` 返回
 *        `DEVICE_OUT_*` bitmask 的函数引用。system_server 通过 [HiddenAudioSystem] 提供，
 *        模块进程和被注入进程通过 [createForAppProcess] 反射 `AudioSystem` 提供。
 */
class SystemMediaDeviceProbe(
    private val getDevicesForStream: (Int) -> Int,
) {

    /**
     * 探测系统当前 MEDIA 输出设备的公开身份。
     *
     * 查询 `AudioSystem.getDevicesForStream(STREAM_MUSIC)` 得到实际路由 bitmask，
     * 再从 `getDevices()` 中匹配公开 [AudioDeviceInfo] 获取 type 和 address。
     *
     * @param audioManager system_server 或模块进程的 AudioManager
     * @return 系统当前 MEDIA 输出设备的公开身份，或 null
     */
    fun probe(audioManager: AudioManager): PreferredDeviceSync.DeviceSpec? {
        val bitmask = try {
            getDevicesForStream(AudioManager.STREAM_MUSIC)
        } catch (t: Throwable) {
            YLog.error("[probe] getDevicesForStream failed", t)
            return null
        }
        if (bitmask == 0) return null
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val matched = outputs.firstOrNull { device ->
            deviceTypeBit(device.type) and bitmask != 0
        } ?: return null
        return PreferredDeviceSync.DeviceSpec(matched.type, matched.address.orEmpty())
    }

    /**
     * 从 bitmask 中按设备优先级选取最高的内部设备类型。
     *
     * 优先级：蓝牙 A2DP/BLE > 有线 > 扬声器 > 听筒。
     * 这不是猜测系统路由（bitmask 已经是实际路由），只是在多个设备同时活跃时选一个代表。
     *
     * @param bitmask `AudioSystem.DEVICE_OUT_*` 的 bitmask
     * @return 优先级最高的 `DEVICE_OUT_*`，或 null
     */
    fun pickHighestPriorityInternalType(bitmask: Int): Int? {
        for (type in DEVICE_PRIORITY) {
            if (bitmask and type != 0) return type
        }
        return null
    }

    companion object {
        /** STREAM_MUSIC 的 legacy stream type 常量。 */
        const val STREAM_MUSIC = 3

        /**
         * 设备优先级表（`DEVICE_OUT_*` 值），用于从 bitmask 中选取代表设备。
         *
         * 这些值是 Android `AudioSystem` 中的稳定常量，不随版本变化。
         * 顺序：蓝牙 A2DP > BLE 系列 > 有线 > 扬声器 > 听筒。
         */
        private val DEVICE_PRIORITY = intArrayOf(
            0x80,   // DEVICE_OUT_BLUETOOTH_A2DP
            0x400,  // DEVICE_OUT_BLE_SPEAKER
            0x200,  // DEVICE_OUT_BLE_HEADSET
            0x1000, // DEVICE_OUT_BLE_BROADCAST
            0x10,   // DEVICE_OUT_WIRED_HEADSET
            0x20,   // DEVICE_OUT_WIRED_HEADPHONE
            0x2,    // DEVICE_OUT_SPEAKER
            0x1,    // DEVICE_OUT_EARPIECE
        )

        /**
         * 公开 [AudioDeviceInfo.type] 到 `DEVICE_OUT_*` bit 的映射。
         * 用于从 `getDevices()` 结果中匹配 bitmask 中的设备。
         */
        private val PUBLIC_TYPE_TO_BIT = mapOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE to 0x1,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to 0x2,
            AudioDeviceInfo.TYPE_WIRED_HEADSET to 0x10,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES to 0x20,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to 0x80,
            AudioDeviceInfo.TYPE_BLE_HEADSET to 0x200,
            AudioDeviceInfo.TYPE_BLE_SPEAKER to 0x400,
            AudioDeviceInfo.TYPE_BLE_BROADCAST to 0x1000,
        )

        /** 公开 type 对应的 bit；未映射返回 0。 */
        fun deviceTypeBit(publicType: Int): Int = PUBLIC_TYPE_TO_BIT[publicType] ?: 0

        /**
         * 为模块进程和被注入的 App 进程创建 [SystemMediaDeviceProbe]。
         *
         * 通过反射调用 `android.media.AudioSystem.getDevicesForStream(int)` 获取实际路由。
         * 在 LSPosed 注入环境下，hidden API 反射可以正常工作。
         *
         * @return 探测器实例，或 null（反射失败时）
         */
        fun createForAppProcess(): SystemMediaDeviceProbe? {
            val function = createGetDevicesForStreamFunction() ?: return null
            return SystemMediaDeviceProbe(function)
        }

        /**
         * 反射获取 `AudioSystem.getDevicesForStream(int)` 静态方法。
         *
         * @return 调用该方法的函数引用，或 null（方法不存在时）
         */
        private fun createGetDevicesForStreamFunction(): ((Int) -> Int)? {
            try {
                val audioSystemClass = "android.media.AudioSystem".toClass()
                val method = audioSystemClass.getDeclaredMethod(
                    "getDevicesForStream",
                    classOf<Int>(),
                )
                method.makeAccessible()
                return { streamType: Int -> method.invoke(null, streamType) as Int }
            } catch (t: Throwable) {
                YLog.error("[probe] Cannot reflect AudioSystem.getDevicesForStream", t)
                return null
            }
        }
    }
}
