package com.agui.calibration.root

import android.content.Context
import java.io.File

internal object RootDaemonScriptInstaller {
    private const val SCRIPT_NAME = "start_root_daemon.sh"

    fun scriptFile(context: Context): File = File(context.filesDir, SCRIPT_NAME)

    fun scriptPath(context: Context): String = scriptFile(context).absolutePath

    fun manualCommands(context: Context): List<String> = listOf(
        "adb root",
        "adb shell sh ${scriptPath(context)}"
    )

    fun ensureInstalled(context: Context): File {
        val scriptFile = scriptFile(context)
        val content = buildString {
            appendLine("#!/system/bin/sh")
            appendLine("set -eu")
            appendLine()
            appendLine("current_uid=\"\$(id -u)\"")
            appendLine("if [ \"\$current_uid\" != \"0\" ]; then")
            appendLine("  echo \"adbd is not root. Run 'adb root' first, then rerun this script.\"")
            appendLine("  exit 1")
            appendLine("fi")
            appendLine()
            appendLine("pkg_path=\"\$(pm path com.agui.calibration | head -n 1 | cut -d: -f2 | tr -d '\\r')\"")
            appendLine("if [ -z \"\$pkg_path\" ]; then")
            appendLine("  echo \"Package path not found. Install the app first.\"")
            appendLine("  exit 1")
            appendLine("fi")
            appendLine()
            appendLine("echo \"Starting root daemon in foreground. Keep this shell open if you need logs.\"")
            appendLine("echo \"APK: \$pkg_path\"")
            appendLine("exec env CLASSPATH=\"\$pkg_path\" /system/bin/app_process / com.agui.calibration.root.RootCalibrationDaemon")
        }
        if (!scriptFile.exists() || scriptFile.readText() != content) {
            scriptFile.writeText(content)
        }
        scriptFile.setReadable(true, false)
        scriptFile.setExecutable(true, false)
        scriptFile.setWritable(true, true)
        return scriptFile
    }
}
