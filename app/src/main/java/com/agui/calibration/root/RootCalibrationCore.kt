package com.agui.calibration.root

import android.os.IBinder
import com.agui.calibration.vendor.IAgoldDaemon
import com.agui.calibration.vendor.IAgoldDaemonCallback
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

object RootCalibrationCore {
    private const val SERVICE_NAME = "vendor.mediatek.hardware.agolddaemon.IAgoldDaemon/default"
    private const val TYPE_GSENSOR_CALIBRATION = 2
    private const val TYPE_GYROSCOPE_CALIBRATION = 1
    private const val TYPE_ALSPS_IOCTL = 4
    private const val TYPE_GYROSCOPE_IOCTL = 6
    private const val ALSPS_NVRAM_FILE = "/mnt/vendor/nvdata/APCFG/APRDCL/AGOLD_HWMON_PS"
    private const val ALSPS_NOISE_FILE = "/sys/bus/platform/drivers/als_ps/pscali"
    private const val NVRAM_NOISE_OFFSET = 8
    private const val NVRAM_TOTAL_LENGTH = 12

    private data class AlspsStandard(
        val raw: String,
        val minStd: Int,
        val maxStd: Int,
        val offsetStd: Int,
        val thresholdGain: Int,
        val thresholdMin: Int
    )

    fun execute(command: String): Pair<String, Int> {
        startBinderThreadPoolBestEffort()
        val args = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (args.isEmpty()) {
            return "ERROR empty_command" to 2
        }
        return when (args.first()) {
            "probe" -> probe()
            "calibrate-gsensor" -> calibrateGSensor()
            "calibrate-gyroscope" -> calibrateGyroscope()
            "alsps-status" -> alspsStatus()
            "alsps-capture-min" -> alspsCaptureMin()
            "alsps-capture-max" -> alspsCaptureMax()
            "alsps-write-min" -> {
                val value = args.getOrNull(1)?.toIntOrNull()
                if (value == null) "ERROR invalid_min_value" to 2 else alspsWriteMin(value)
            }
            "alsps-write-max" -> {
                val value = args.getOrNull(1)?.toIntOrNull()
                if (value == null) "ERROR invalid_max_value" to 2 else alspsWriteMax(value)
            }
            "alsps-apply-default-thresholds" -> {
                val minValue = args.getOrNull(1)?.toIntOrNull()
                alspsApplyDefaultThresholds(minValue)
            }
            "alsps-sync-noise" -> alspsSyncNoise()
            "alsps-query-ps" -> alspsQueryPs()
            "exit" -> kotlin.system.exitProcess(0)
            else -> "ERROR unknown_command:${args.first()}" to 2
        }
    }

    private fun probe(): Pair<String, Int> {
        val daemon = getDaemon() ?: return "ERROR service_unavailable" to 3
        return if (daemon.asBinder() != null) {
            "OK service_found" to 0
        } else {
            "ERROR service_unavailable" to 3
        }
    }

    private fun calibrateGSensor(): Pair<String, Int> {
        val daemon = getDaemon() ?: return "ERROR service_unavailable" to 3

        val result = awaitCommonResult(daemon, TYPE_GSENSOR_CALIBRATION, 15)
            ?: return "ERROR timeout" to 4
        return "RESULT $result" to if (result == "0") 0 else 5
    }

    private fun calibrateGyroscope(): Pair<String, Int> {
        val daemon = getDaemon() ?: return "ERROR service_unavailable" to 3
        val startResult = daemon.SendMessageToIoctl(TYPE_GYROSCOPE_IOCTL, 1, 0, 0)
        if (startResult != 0) {
            return "ERROR start_failed:$startResult" to 5
        }

        var latest = ""
        repeat(4) {
            val result = awaitCommonResult(daemon, TYPE_GYROSCOPE_CALIBRATION, 5) ?: return@repeat
            latest = result
            if (isValidGyroscopeData(result)) {
                return "RESULT $result" to 0
            }
        }
        return if (latest.isNotBlank()) {
            "ERROR invalid_result:$latest" to 5
        } else {
            "ERROR timeout" to 4
        }
    }

    private fun alspsStatus(): Pair<String, Int> {
        val daemon = getDaemon() ?: return "ERROR service_unavailable" to 3
        val currentPs = daemon.SendMessageToIoctl(TYPE_ALSPS_IOCTL, 6, 0, 0)
        val thresholdHigh = daemon.SendMessageToIoctl(TYPE_ALSPS_IOCTL, 9, 0, 0)
        val thresholdLow = daemon.SendMessageToIoctl(TYPE_ALSPS_IOCTL, 9, 1, 0)
        val minValue = daemon.SendMessageToIoctl(TYPE_ALSPS_IOCTL, 7, 0, 0)
        val maxValue = daemon.SendMessageToIoctl(TYPE_ALSPS_IOCTL, 8, 0, 0)
        val standard = readAlspsStandard()
        return buildString {
            append("STATUS")
            append(" ps=").append(currentPs)
            append(" min=").append(minValue)
            append(" max=").append(maxValue)
            append(" th_high=").append(thresholdHigh)
            append(" th_low=").append(thresholdLow)
            append(" std=").append(standard.raw)
        } to 0
    }

    private fun alspsCaptureMin(): Pair<String, Int> {
        val daemon = getDaemon() ?: return "ERROR service_unavailable" to 3
        daemon.SendMessageToIoctl(TYPE_ALSPS_IOCTL, 1, 0, 0)
        Thread.sleep(150)

        var latest = -1
        repeat(16) {
            val value = daemon.SendMessageToIoctl(TYPE_ALSPS_IOCTL, 2, 0, 0)
            if (value >= 0) {
                latest = value
            }
            Thread.sleep(120)
        }

        return if (latest >= 0) {
            "RESULT $latest" to 0
        } else {
            "ERROR min_capture_failed" to 5
        }
    }

    private fun alspsCaptureMax(): Pair<String, Int> {
        val daemon = getDaemon() ?: return "ERROR service_unavailable" to 3
        daemon.SendMessageToIoctl(TYPE_ALSPS_IOCTL, 3, 0, 0)
        Thread.sleep(150)

        var latest = -1
        repeat(16) {
            val raw = daemon.SendMessageToIoctl(TYPE_ALSPS_IOCTL, 2, 1, 0)
            val value = if (raw >= 0) raw and 0x7fff else raw
            if (value >= 0) {
                latest = value
            }
            Thread.sleep(120)
        }

        return if (latest >= 0) {
            "RESULT $latest" to 0
        } else {
            "ERROR max_capture_failed" to 5
        }
    }

    private fun alspsApplyDefaultThresholds(minValue: Int?): Pair<String, Int> {
        val daemon = getDaemon() ?: return "ERROR service_unavailable" to 3
        val standard = readAlspsStandard()
        if (standard.minStd <= 0 || standard.maxStd <= 0 || standard.offsetStd < 0 ||
            standard.thresholdGain <= 0 || standard.thresholdMin < 0
        ) {
            return "ERROR invalid_standard:${standard.raw}" to 5
        }
        val baseMin = minValue ?: daemon.SendMessageToIoctl(TYPE_ALSPS_IOCTL, 7, 0, 0)
        if (baseMin < 0) {
            return "ERROR invalid_min_value" to 5
        }
        val high = max(baseMin + standard.offsetStd, standard.thresholdMin)
        val low = high - standard.thresholdGain
        val ret = daemon.SendMessageToIoctl(TYPE_ALSPS_IOCTL, 10, high, low)
        return if (ret == 1) {
            "RESULT high=$high low=$low" to 0
        } else {
            "ERROR threshold_apply_failed:$ret" to 5
        }
    }

    private fun alspsWriteMin(minValue: Int): Pair<String, Int> {
        return try {
            writeAsciiValueToNvram(offset = 0, totalLength = 4, value = minValue)
            "RESULT $minValue" to 0
        } catch (t: Throwable) {
            "ERROR write_min_failed:${t.javaClass.simpleName}" to 5
        }
    }

    private fun alspsWriteMax(maxValue: Int): Pair<String, Int> {
        return try {
            writeAsciiValueToNvram(offset = 4, totalLength = 8, value = maxValue)
            "RESULT $maxValue" to 0
        } catch (t: Throwable) {
            "ERROR write_max_failed:${t.javaClass.simpleName}" to 5
        }
    }

    private fun alspsSyncNoise(): Pair<String, Int> {
        val sysfsValue = readNoiseValue() ?: return "ERROR noise_unavailable" to 5
        return try {
            writeNoiseToNvram(sysfsValue)
            try {
                Files.writeString(Paths.get(ALSPS_NOISE_FILE), sysfsValue.toString())
            } catch (_: Throwable) {
            }
            "RESULT $sysfsValue" to 0
        } catch (t: Throwable) {
            "ERROR noise_sync_failed:${t.javaClass.simpleName}" to 5
        }
    }

    private fun alspsQueryPs(): Pair<String, Int> {
        val daemon = getDaemon() ?: return "ERROR service_unavailable" to 3
        val value = daemon.SendMessageToIoctl(TYPE_ALSPS_IOCTL, 6, 0, 0)
        return "RESULT $value" to 0
    }

    private fun awaitCommonResult(daemon: IAgoldDaemon, type: Int, timeoutSeconds: Long): String? {
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference<String?>(null)
        val callback = object : IAgoldDaemonCallback.Stub() {
            override fun onResult(result: String) {
                resultRef.set(result)
                latch.countDown()
            }
        }

        daemon.CommonGetResult(callback, type)
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            return null
        }

        return resultRef.get()?.trim().orEmpty()
    }

    private fun isValidGyroscopeData(data: String): Boolean {
        if (data.isBlank()) {
            return false
        }
        val values = data.split(',').mapNotNull { it.trim().toFloatOrNull() }
        return values.size >= 3 && values.take(3).any { it != 0f }
    }

    private fun readAlspsStandard(): AlspsStandard {
        // minStd, maxStd, offsetStd, thresholdGain, thresholdMin
        // default: 800,2000,800,80,1000
        val raw = readSystemProperty("ro.alsps_stdvalue", "")
        val parts = raw.split(',', '-').mapNotNull { it.trim().toIntOrNull() }
        return AlspsStandard(
            raw = raw,
            minStd = parts.getOrElse(0) { -1 },
            maxStd = parts.getOrElse(1) { -1 },
            offsetStd = parts.getOrElse(2) { -1 },
            thresholdGain = parts.getOrElse(3) { -1 },
            thresholdMin = parts.getOrElse(4) { -1 }
        )
    }

    private fun readSystemProperty(key: String, defaultValue: String): String {
        return try {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val get = systemProperties.getMethod("get", String::class.java, String::class.java)
            get.invoke(null, key, defaultValue) as? String ?: defaultValue
        } catch (_: Throwable) {
            defaultValue
        }
    }

    private fun readNoiseValue(): Int? {
        val content = try {
            Files.readString(Paths.get(ALSPS_NOISE_FILE)).trim()
        } catch (_: Throwable) {
            return null
        }
        val digits = buildString {
            for (ch in content) {
                if (ch.isDigit()) {
                    append(ch)
                } else if (isNotEmpty()) {
                    break
                }
            }
        }
        return digits.toIntOrNull()
    }

    private fun writeNoiseToNvram(noise: Int) {
        val path: Path = Paths.get(ALSPS_NVRAM_FILE)
        val original = if (Files.exists(path)) Files.readAllBytes(path) else ByteArray(0)
        val buffer = ByteArray(NVRAM_TOTAL_LENGTH)
        original.copyInto(buffer, endIndex = minOf(original.size, buffer.size))
        for (index in NVRAM_NOISE_OFFSET until NVRAM_TOTAL_LENGTH) {
            buffer[index] = 0
        }
        val bytes = noise.toString().toByteArray(Charsets.US_ASCII)
        bytes.copyInto(buffer, destinationOffset = NVRAM_NOISE_OFFSET, endIndex = minOf(bytes.size, 4))
        Files.write(path, buffer)
    }

    private fun writeAsciiValueToNvram(offset: Int, totalLength: Int, value: Int) {
        val path: Path = Paths.get(ALSPS_NVRAM_FILE)
        val original = if (Files.exists(path)) Files.readAllBytes(path) else ByteArray(0)
        val buffer = ByteArray(NVRAM_TOTAL_LENGTH)
        original.copyInto(buffer, endIndex = minOf(original.size, buffer.size))
        for (index in offset until totalLength) {
            buffer[index] = 0
        }
        val hex = value.toString(16).toByteArray(Charsets.US_ASCII)
        val destinationEnd = totalLength
        var writeIndex = destinationEnd - 1
        for (index in hex.lastIndex downTo 0) {
            if (writeIndex < offset) break
            buffer[writeIndex] = hex[index]
            writeIndex--
        }
        Files.write(path, buffer)
    }

    private fun getDaemon(): IAgoldDaemon? {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        var binder = getService.invoke(null, SERVICE_NAME) as? IBinder
        if (binder == null) {
            try {
                val waitForDeclaredService = serviceManager.getMethod("waitForDeclaredService", String::class.java)
                binder = waitForDeclaredService.invoke(null, SERVICE_NAME) as? IBinder
            } catch (_: NoSuchMethodException) {
            }
        }
        return IAgoldDaemon.asInterface(binder)
    }

    private fun startBinderThreadPoolBestEffort() {
        try {
            val processStateClass = Class.forName("android.os.ProcessState")
            val self: Method = processStateClass.getDeclaredMethod("self")
            self.isAccessible = true
            val processState = self.invoke(null)
            val startThreadPool = processStateClass.getDeclaredMethod("startThreadPool")
            startThreadPool.isAccessible = true
            startThreadPool.invoke(processState)
        } catch (_: Throwable) {
        }
    }
}