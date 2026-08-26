package hk.uwu.soundman.data

import android.content.SharedPreferences
import hk.uwu.soundman.log.AppLog

internal const val APP_BLACKLIST_PREFERENCES_NAME = "soundman_app_blacklist"

/**
 * 应用黑名单持久化边界。
 *
 * 动机：部分场景需要将特定应用排除在音量面板或规则管理之外，
 * 以包名作为稳定主键持久化黑名单集合，供 UI 选择器和 hook 层共同读取。
 */
interface AppBlacklistStore {
    /** 读取当前黑名单中所有包名；存储异常会直接抛出。 */
    fun readAll(): Set<String>

    /** 判断指定包名是否在黑名单中。 */
    fun contains(packageName: String): Boolean

    /** 全量替换黑名单集合，并返回最新快照。 */
    fun replaceAll(packageNames: Set<String>): Set<String>

    /** 添加单个包名到黑名单，并返回最新快照。 */
    fun add(packageName: String): Set<String>

    /** 批量添加包名到黑名单，并返回最新快照。 */
    fun addAll(packageNames: Set<String>): Set<String>

    /** 从黑名单移除单个包名，并返回最新快照。 */
    fun remove(packageName: String): Set<String>

    /** 黑名单修订版本号，每次写入递增。 */
    fun revision(): Long
}

/**
 * 基于 SharedPreferences 的应用黑名单存储实现。
 *
 * 使用 StringSet 持久化包名集合，与 [SharedPreferencesRuleStore] 的模式一致。
 */
class SharedPreferencesAppBlacklistStore(
    private val preferences: SharedPreferences,
) : AppBlacklistStore {
    override fun readAll(): Set<String> = logged("read app blacklist") {
        preferences.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toSet()
    }

    override fun contains(packageName: String): Boolean = packageName in readAll()

    override fun replaceAll(packageNames: Set<String>): Set<String> =
        logged("replace app blacklist") {
            commit(packageNames)
            packageNames
        }

    override fun add(packageName: String): Set<String> =
        logged("add app blacklist package=$packageName") {
            val updated = readAll() + packageName
            commit(updated)
            updated
        }

    override fun addAll(packageNames: Set<String>): Set<String> =
        logged("add app blacklist batch size=${packageNames.size}") {
            val updated = readAll() + packageNames
            commit(updated)
            updated
        }

    override fun remove(packageName: String): Set<String> =
        logged("remove app blacklist package=$packageName") {
            val updated = readAll() - packageName
            commit(updated)
            updated
        }

    override fun revision(): Long = preferences.getLong(KEY_REVISION, 0L).coerceAtLeast(0L)

    private fun commit(packageNames: Set<String>) {
        val currentRevision = revision()
        check(currentRevision < Long.MAX_VALUE) { "App blacklist revision overflow" }
        val committed = preferences.edit()
            .putStringSet(KEY_PACKAGES, packageNames)
            .putLong(KEY_REVISION, currentRevision + 1L)
            .commit()
        check(committed) { "Failed to persist app blacklist" }
    }

    private inline fun <T> logged(operation: String, block: () -> T): T = try {
        block()
    } catch (error: RuntimeException) {
        AppLog.error("Unable to $operation", error)
        throw error
    }

    private companion object {
        const val KEY_PACKAGES = "packages"
        const val KEY_REVISION = "revision"
    }
}
