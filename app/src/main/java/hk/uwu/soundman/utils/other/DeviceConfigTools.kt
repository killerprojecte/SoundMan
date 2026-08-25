package hk.uwu.soundman.utils.other

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import hk.uwu.soundman.utils.RootHelper.executeRootCommand
import hk.uwu.soundman.utils.RootHelper.hasRootAccess

/**
 * 设备配置工具类。
 *
 * 动机：与 REAREye 的 DeviceConfigTools 一致，提供 MIUI/HyperOS 设备的详细信息获取。
 * 通过 SystemProperties 反射获取市场名称、Android 版本等设备信息。
 */
object DeviceConfigTools {

    val getdevice =
        if (hasRootAccess()) executeRootCommand("getprop persist.private.device_name") else Pair(
            20,
            "No Root Permission"
        )
    val deviceName = if (getdevice.first == 0) getdevice.second else "No Root Permission"


    val androidVersion: String = getSystemProperties("ro.build.version.release")

    val marketName by lazy {

        val marketName: String = getSystemProperties("ro.product.marketname")

        if (marketName.isNotEmpty()) titleFirstChar(marketName) else titleFirstChar(Build.BRAND) + " " + Build.MODEL

    }

    fun titleFirstChar(st: String): String {
        val formattedBrand = st.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase() else it.toString()
        }
        return formattedBrand
    }

    @SuppressLint("PrivateApi")

    fun getSystemProperties(key: String): String {
        val ret: String = try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("get", String::class.java).invoke(null, key) as String
        } catch (iAE: IllegalArgumentException) {
            throw iAE
        } catch (_: Exception) {
            ""
        }
        return ret
    }

    fun getSubScreenVersion(context: Context): String {
        val packageManager = context.packageManager
        return try {
            val packageInfo = packageManager.getPackageInfo("com.xiaomi.subscreencenter", 0)
            packageInfo.versionName.toString()
        } catch (_: Exception) {
            "UNKNOWN"
        }
    }
}
