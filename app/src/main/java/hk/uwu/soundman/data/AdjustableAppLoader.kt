package hk.uwu.soundman.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import hk.uwu.soundman.R
import hk.uwu.soundman.log.AppLog
import hk.uwu.soundman.model.AdjustableApp

/**
 * 由「包名 + uid」解析出面板要显示的 [AdjustableApp]。
 *
 * 动机：宿主快照和侧栏带过来的种子都要走同一套解析与兜底 ——
 * 没装、没权限、查不到包时统一显示「未知应用(uid)」而不是各写一份，
 * 免得同一个应用在面板里换两次脸。
 *
 * @param context 用于 PackageManager 与兜底文案
 * @param packageName 目标包名
 * @param uid 目标 uid
 * @param allowPackageLookup 是否允许查包信息；没拿 installed-apps 权限时为 false
 */
internal object AdjustableAppLoader {
    fun load(
        context: Context,
        packageName: String,
        uid: Int,
        allowPackageLookup: Boolean,
    ): AdjustableApp {
        if (!allowPackageLookup) {
            AppLog.warn("Skipping package lookup for uid=$uid package=$packageName without installed-apps access")
            return unknownApp(context, packageName, uid)
        }
        val packageManager = context.packageManager
        val info = try {
            packageManager.getApplicationInfo(packageName, 0)
        } catch (error: PackageManager.NameNotFoundException) {
            AppLog.warn("Active uid=$uid package=$packageName is no longer installed", error)
            return unknownApp(context, packageName, uid)
        }
        return AdjustableApp(
            packageName = packageName,
            label = info.loadLabel(packageManager).toString(),
            uid = uid,
            icon = info.loadIcon(packageManager),
            isSystemApp = info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0,
        )
    }

    private fun unknownApp(context: Context, packageName: String, uid: Int): AdjustableApp {
        return AdjustableApp(
            packageName = packageName,
            label = context.getString(R.string.unknown_app, uid),
            uid = uid,
            icon = defaultIcon(context),
        )
    }

    private fun defaultIcon(context: Context): Drawable =
        runCatching { context.packageManager.defaultActivityIcon }
            .getOrElse { error ->
                AppLog.error("Default activity icon unavailable", error)
                android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            }
}
