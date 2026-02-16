package com.agui.calibration.root

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class RootCalibrationProxy(private val context: Context) {

    companion object {
        private const val TAG = "RootCalibrationProxy"
    }

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val combined: String
            get() = listOf(stdout.trim(), stderr.trim()).filter { it.isNotEmpty() }.joinToString("\n")
    }

    data class AlspsStatus(
        val currentPs: Int? = null,
        val minValue: Int? = null,
        val maxValue: Int? = null,
        val thresholdHigh: Int? = null,
        val thresholdLow: Int? = null,
        val standard: String? = null
    )

    fun probe(): CommandResult = runHelper("probe")

    fun stopDaemon(): CommandResult = runHelper("exit")

    fun calibrateGSensor(): CommandResult = runHelper("calibrate-gsensor")

    fun calibrateGyroscope(): CommandResult = runHelper("calibrate-gyroscope")

    fun getAlspsStatus(): CommandResult = runHelper("alsps-status")

    fun captureAlspsMin(): CommandResult = runHelper("alsps-capture-min")

    fun captureAlspsMax(): CommandResult = runHelper("alsps-capture-max")

    fun writeAlspsMin(minValue: Int): CommandResult = runHelper("alsps-write-min $minValue")

    fun writeAlspsMax(maxValue: Int): CommandResult = runHelper("alsps-write-max $maxValue")

    fun applyAlspsDefaultThresholds(minValue: Int?): CommandResult {
        return if (minValue != null) {
            runHelper("alsps-apply-default-thresholds $minValue")
        } else {
            runHelper("alsps-apply-default-thresholds")
        }
    }

    fun syncAlspsNoise(): CommandResult = runHelper("alsps-sync-noise")

    fun queryProximiPsName(): CommandResult = runHelper("alsps-query-ps")

    fun parseAlspsStatus(result: CommandResult): AlspsStatus? {
        val statusLine = result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("STATUS ") }
            ?: return null
        val values = mutableMapOf<String, String>()
        statusLine.removePrefix("STATUS ")
            .split(' ')
            .forEach { token ->
                val index = token.indexOf('=')
                if (index > 0) {
                    values[token.substring(0, index)] = token.substring(index + 1)
                }
            }
        return AlspsStatus(
            currentPs = values["ps"]?.toIntOrNull(),
            minValue = values["min"]?.toIntOrNull(),
            maxValue = values["max"]?.toIntOrNull(),
            thresholdHigh = values["th_high"]?.toIntOrNull(),
            thresholdLow = values["th_low"]?.toIntOrNull(),
            standard = values["std"]
        )
    }

    fun extractResultValue(result: CommandResult): String? {
        return result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("RESULT ") }
            ?.removePrefix("RESULT ")
            ?.trim()
    }

    private fun runHelper(command: String): CommandResult {
        return try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(RootCalibrationDaemon.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
                val writer = OutputStreamWriter(socket.outputStream)
                writer.write(command)
                writer.write("\n")
                writer.flush()
                val stdout = readAll(socket.inputStream)
                val result = when {
                    stdout.lineSequence().any { it.startsWith("ERROR ") } -> CommandResult(1, stdout, "")
                    stdout.lineSequence().any { it.startsWith("OK ") || it.startsWith("RESULT ") || it.startsWith("STATUS ") } -> CommandResult(0, stdout, "")
                    stdout.contains("ERROR ") -> CommandResult(1, stdout, "")
                    else -> CommandResult(1, stdout, "")
                }
                Log.i(TAG, "socket command=$command, output=${result.combined}")
                result
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Socket daemon request failed", t)
            CommandResult(-1, "", "daemon_unreachable:${t.javaClass.simpleName}:${t.message}")
        }
    }

    private fun readAll(stream: InputStream): String {
        return BufferedReader(InputStreamReader(stream)).use { reader ->
            buildString {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    append(line).append('\n')
                }
            }
        }
    }
}