package hk.uwu.soundman.utils

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Utility for detecting whether the device has root access and executing root commands.
 */
object RootHelper {

    private const val TAG = "RootHelper"

    /**
     * Check if the device has root access by attempting to run `su`.
     * Returns true if root is available, false otherwise.
     */
    fun hasRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = process.outputStream
            os.write("exit\n".toByteArray())
            os.flush()
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.w(TAG, "Root access check failed: ${e.message}")
            false
        }
    }

    /**
     * Execute a root command and return the exit code and output.
     *
     * 动机：DeviceConfigTools 需要通过 root 获取设备名称等属性。
     */
    fun executeRootCommand(command: String): Pair<Int, String> {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = process.outputStream
            val writer = os.writer()

            writer.write("$command\n")
            writer.write("exit\n")
            writer.flush()
            writer.close()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = StringBuilder()
            val errorOutput = StringBuilder()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                errorOutput.append(line).append("\n")
            }

            reader.close()
            errorReader.close()

            val exitCode = process.waitFor()

            if (errorOutput.isNotEmpty()) {
                Log.w(TAG, "Command stderr: ${errorOutput.toString().trim()}")
            }

            Pair(exitCode, output.toString().trim())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute root command: $command", e)
            Pair(-1, "")
        }
    }
}
