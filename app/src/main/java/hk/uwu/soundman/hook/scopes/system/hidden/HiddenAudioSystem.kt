package hk.uwu.soundman.hook.scopes.system.hidden

import android.media.AudioDeviceInfo
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import com.highcapable.kavaref.resolver.MethodResolver
import java.lang.reflect.InvocationTargetException

/**
 * 隐藏 `android.media.AudioSystem` 的反射访问面。
 *
 * 动机：framework 的设备连接状态与内部类型映射不在公开 SDK 里。
 * SystemAudioRuntime 用本类查询输出设备内部类型和连接状态，再把改道目标写进 Settings.Global。
 * 构造期就必须拿到全部当前调用面字段和方法；缺类、缺字段、缺方法立即失败。
 *
 * 亲和性 API 仍保留给 HiddenAudioSystem 单测和诊断，不再作为改道主路径。
 */
class HiddenAudioSystem(
    classLoader: ClassLoader,
    className: String = AUDIO_SYSTEM_CLASS,
) {
    private val audioSystemClass: Class<*> = loadClass(classLoader, className)
    private val getDeviceConnectionState: MethodResolver<Any>
    private val getDevicesForStream: MethodResolver<Any>
    private val setUidDeviceAffinities: MethodResolver<Any>
    private val removeUidDeviceAffinities: MethodResolver<Any>
    private val adapterGetDefault: MethodResolver<Any>?
    private val adapterSetUidDeviceAffinities: MethodResolver<Any>?
    private val adapterRemoveUidDeviceAffinities: MethodResolver<Any>?
    private val deviceOutByPublicType: Map<Int, Int>

    /**
     * 对应 `AudioSystem.DEVICE_STATE_AVAILABLE`。
     *
     * 动机：路由前必须用同一套隐藏常量判断候选设备是否已连接。
     */
    val deviceStateAvailable: Int

    /**
     * 亲和性是否走 `AudioSystemAdapter`（会先 `invalidateRoutingCache`）。
     *
     * 动机：诊断 ROM 是否暴露 adapter；改道主路径已不再调用亲和性。
     */
    val routesThroughAdapter: Boolean
        get() = adapterGetDefault != null && adapterSetUidDeviceAffinities != null

    init {
        deviceStateAvailable = staticInt(audioSystemClass, FIELD_DEVICE_STATE_AVAILABLE)
        deviceOutByPublicType = linkedMapOf(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE to staticInt(audioSystemClass, FIELD_DEVICE_OUT_EARPIECE),
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to staticInt(audioSystemClass, FIELD_DEVICE_OUT_SPEAKER),
            AudioDeviceInfo.TYPE_WIRED_HEADSET to staticInt(audioSystemClass, FIELD_DEVICE_OUT_WIRED_HEADSET),
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES to staticInt(audioSystemClass, FIELD_DEVICE_OUT_WIRED_HEADPHONE),
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO to staticInt(audioSystemClass, FIELD_DEVICE_OUT_BLUETOOTH_SCO),
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to staticInt(audioSystemClass, FIELD_DEVICE_OUT_BLUETOOTH_A2DP),
            AudioDeviceInfo.TYPE_USB_ACCESSORY to staticInt(audioSystemClass, FIELD_DEVICE_OUT_USB_ACCESSORY),
            AudioDeviceInfo.TYPE_USB_DEVICE to staticInt(audioSystemClass, FIELD_DEVICE_OUT_USB_DEVICE),
            AudioDeviceInfo.TYPE_USB_HEADSET to staticInt(audioSystemClass, FIELD_DEVICE_OUT_USB_HEADSET),
            AudioDeviceInfo.TYPE_BLE_HEADSET to staticInt(audioSystemClass, FIELD_DEVICE_OUT_BLE_HEADSET),
            AudioDeviceInfo.TYPE_BLE_SPEAKER to staticInt(audioSystemClass, FIELD_DEVICE_OUT_BLE_SPEAKER),
            AudioDeviceInfo.TYPE_BLE_BROADCAST to staticInt(audioSystemClass, FIELD_DEVICE_OUT_BLE_BROADCAST),
        )
        getDeviceConnectionState = resolveMethod(
            audioSystemClass,
            METHOD_GET_DEVICE_CONNECTION_STATE,
            Int::class.javaPrimitiveType!!,
            String::class.java,
        )
        getDevicesForStream = resolveMethod(
            audioSystemClass,
            METHOD_GET_DEVICES_FOR_STREAM,
            Int::class.javaPrimitiveType!!,
        )
        setUidDeviceAffinities = resolveMethod(
            audioSystemClass,
            METHOD_SET_UID_DEVICE_AFFINITIES,
            Int::class.javaPrimitiveType!!,
            IntArray::class.java,
            Array<String>::class.java,
        )
        removeUidDeviceAffinities = resolveMethod(
            audioSystemClass,
            METHOD_REMOVE_UID_DEVICE_AFFINITIES,
            Int::class.javaPrimitiveType!!,
        )
        val adapterClass = runCatching { classLoader.loadClass(ADAPTER_CLASS) }.getOrNull()
        if (adapterClass != null) {
            adapterGetDefault = resolveMethod(adapterClass, METHOD_GET_DEFAULT_ADAPTER)
            adapterSetUidDeviceAffinities = resolveMethod(
                adapterClass,
                METHOD_SET_UID_DEVICE_AFFINITIES,
                Int::class.javaPrimitiveType!!,
                IntArray::class.java,
                Array<String>::class.java,
            )
            adapterRemoveUidDeviceAffinities = resolveMethod(
                adapterClass,
                METHOD_REMOVE_UID_DEVICE_AFFINITIES,
                Int::class.javaPrimitiveType!!,
            )
        } else {
            adapterGetDefault = null
            adapterSetUidDeviceAffinities = null
            adapterRemoveUidDeviceAffinities = null
        }
    }

    /**
     * 把公开 [AudioDeviceInfo] 的 `TYPE_*` 映射为隐藏 `DEVICE_OUT_*`。
     *
     * @param publicType 公开 `AudioDeviceInfo.TYPE_*`
     * @return 已反射到的 `DEVICE_OUT_*`，无法映射时为 null
     */
    fun outputDeviceType(publicType: Int): Int? = deviceOutByPublicType[publicType]

    /**
     * 把隐藏 `DEVICE_OUT_*` 映射回公开 `AudioDeviceInfo.TYPE_*`。
     *
     * 动机：Zygote hook 用公开 TYPE + address 在 [AudioManager.getDevices] 里找设备。
     *
     * @param internalType 隐藏 `DEVICE_OUT_*`
     * @return 公开 `TYPE_*`，没有反向映射时为 null
     */
    fun publicOutputType(internalType: Int): Int? =
        deviceOutByPublicType.entries.firstOrNull { entry -> entry.value == internalType }?.key

    /**
     * 查询指定内部输出设备是否已连接。
     *
     * @param device 隐藏 `DEVICE_OUT_*`
     * @param deviceAddress 设备地址
     */
    fun getDeviceConnectionState(device: Int, deviceAddress: String): Int =
        invokeStaticInt(getDeviceConnectionState, device, deviceAddress)

    /**
     * 查询指定 stream 当前实际路由到的设备 bitmask。
     *
     * 动机：用户可以在系统设置中选择输出设备（如连接了蓝牙但选择扬声器），
     * 不能假设优先级。必须查询 AudioPolicy 的实际路由结果。
     *
     * @param streamType legacy stream type，如 [STREAM_MUSIC]
     * @return `DEVICE_OUT_*` 的 bitmask
     */
    fun getDevicesForStream(streamType: Int): Int =
        invokeStaticInt(getDevicesForStream, streamType)

    /**
     * 为 uid 绑定一组内部输出设备。仅供诊断/单测，不是改道主路径。
     */
    fun setUidDeviceAffinities(uid: Int, deviceIds: IntArray, deviceAddresses: Array<String>): Int {
        val adapter = adapterInstance()
        val adapterMethod = adapterSetUidDeviceAffinities
        return if (adapter != null && adapterMethod != null) {
            invokeInstanceInt(adapterMethod, adapter, uid, deviceIds, deviceAddresses)
        } else {
            invokeStaticInt(setUidDeviceAffinities, uid, deviceIds, deviceAddresses)
        }
    }

    /**
     * 清除 uid 上已设置的设备亲和性。仅供诊断/单测，不是改道主路径。
     */
    fun removeUidDeviceAffinities(uid: Int): Int {
        val adapter = adapterInstance()
        val adapterMethod = adapterRemoveUidDeviceAffinities
        return if (adapter != null && adapterMethod != null) {
            invokeInstanceInt(adapterMethod, adapter, uid)
        } else {
            invokeStaticInt(removeUidDeviceAffinities, uid)
        }
    }

    private fun adapterInstance(): Any? {
        val getter = adapterGetDefault ?: return null
        return invokeStatic(getter)
    }

    private companion object {
        const val AUDIO_SYSTEM_CLASS = "android.media.AudioSystem"
        const val ADAPTER_CLASS = "com.android.server.audio.AudioSystemAdapter"
        const val METHOD_GET_DEFAULT_ADAPTER = "getDefaultAdapter"
        const val FIELD_DEVICE_STATE_AVAILABLE = "DEVICE_STATE_AVAILABLE"
        const val FIELD_DEVICE_OUT_EARPIECE = "DEVICE_OUT_EARPIECE"
        const val FIELD_DEVICE_OUT_SPEAKER = "DEVICE_OUT_SPEAKER"
        const val FIELD_DEVICE_OUT_WIRED_HEADSET = "DEVICE_OUT_WIRED_HEADSET"
        const val FIELD_DEVICE_OUT_WIRED_HEADPHONE = "DEVICE_OUT_WIRED_HEADPHONE"
        const val FIELD_DEVICE_OUT_BLUETOOTH_SCO = "DEVICE_OUT_BLUETOOTH_SCO"
        const val FIELD_DEVICE_OUT_BLUETOOTH_A2DP = "DEVICE_OUT_BLUETOOTH_A2DP"
        const val FIELD_DEVICE_OUT_USB_ACCESSORY = "DEVICE_OUT_USB_ACCESSORY"
        const val FIELD_DEVICE_OUT_USB_DEVICE = "DEVICE_OUT_USB_DEVICE"
        const val FIELD_DEVICE_OUT_USB_HEADSET = "DEVICE_OUT_USB_HEADSET"
        const val FIELD_DEVICE_OUT_BLE_HEADSET = "DEVICE_OUT_BLE_HEADSET"
        const val FIELD_DEVICE_OUT_BLE_SPEAKER = "DEVICE_OUT_BLE_SPEAKER"
        const val FIELD_DEVICE_OUT_BLE_BROADCAST = "DEVICE_OUT_BLE_BROADCAST"
        const val METHOD_GET_DEVICE_CONNECTION_STATE = "getDeviceConnectionState"
        const val METHOD_GET_DEVICES_FOR_STREAM = "getDevicesForStream"
        const val METHOD_SET_UID_DEVICE_AFFINITIES = "setUidDeviceAffinities"
        const val METHOD_REMOVE_UID_DEVICE_AFFINITIES = "removeUidDeviceAffinities"

        fun loadClass(classLoader: ClassLoader, className: String): Class<*> = try {
            className.toClass(classLoader, initialize = true)
        } catch (error: Throwable) {
            throw IllegalStateException("Missing class $className", error)
        }

        fun staticInt(clazz: Class<*>, name: String): Int {
            @Suppress("UNCHECKED_CAST")
            val resolved = (clazz as Class<Any>).resolve().optional(silent = true)
            val field = resolved.firstFieldOrNull { name(name) }
                ?: error("Missing field $name on ${clazz.name}")
            val value = field.getQuietly<Int>()
            return value
                ?: error("Field ${clazz.name}.$name is not Int: ${value?.javaClass?.name}")
        }

        fun resolveMethod(
            clazz: Class<*>,
            name: String,
            vararg parameterTypes: Class<*>
        ): MethodResolver<Any> {
            @Suppress("UNCHECKED_CAST")
            val resolved = (clazz as Class<Any>).resolve().optional(silent = true)
            val method = resolved.firstMethodOrNull {
                name(name)
                parameters(*parameterTypes)
            } ?: resolved.firstMethodOrNull {
                name(name)
                parameters(*parameterTypes)
                superclass()
            } ?: error("Missing method ${formatMethod(name, parameterTypes)} on ${clazz.name}")
            return method
        }

        fun invokeStatic(method: MethodResolver<Any>, vararg args: Any?): Any? = try {
            method.invoke(*args)
        } catch (error: InvocationTargetException) {
            throw error.targetException ?: error
        }

        fun invokeStaticInt(method: MethodResolver<Any>, vararg args: Any?): Int {
            val result = invokeStatic(method, *args)
            return result as? Int
                ?: error("${method.self.declaringClass.name}.${method.self.name} returned non-Int: ${result?.javaClass?.name}")
        }

        fun invokeInstanceInt(method: MethodResolver<Any>, instance: Any, vararg args: Any?): Int {
            val result = try {
                method.copy().of(instance).invoke(*args)
            } catch (error: InvocationTargetException) {
                throw error.targetException ?: error
            }
            return result as? Int
                ?: error("${method.self.declaringClass.name}.${method.self.name} returned non-Int: ${result?.javaClass?.name}")
        }

        fun formatMethod(name: String, parameterTypes: Array<out Class<*>>): String =
            parameterTypes.joinToString(prefix = "$name(", postfix = ")") { it.typeName }
    }
}
