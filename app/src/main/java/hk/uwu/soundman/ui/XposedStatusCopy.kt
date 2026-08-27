package hk.uwu.soundman.ui

/**
 * Presentation copy helpers for the Xposed status card.
 *
 * Kept as a plain JVM object so the formatting rules stay unit-testable without Compose.
 */
object XposedStatusCopy {
    private const val FALLBACK_EXECUTOR_NAME = "Xposed"

    fun showExecutor(active: Boolean): Boolean = active

    fun executorName(rawName: String): String {
        val name = rawName.trim()
        return if (name.isEmpty()) FALLBACK_EXECUTOR_NAME else name
    }

    fun executorLine(rawName: String, apiLevel: Int): String {
        val name = executorName(rawName)
        return if (apiLevel > 0) "$name · API $apiLevel" else name
    }
}
