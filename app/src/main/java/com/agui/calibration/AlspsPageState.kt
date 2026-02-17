package com.agui.calibration

import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import com.agui.calibration.root.RootCalibrationProxy

internal class AlspsPageState(
    private val coordinator: AppCoordinator
) {

    val alspsState = mutableStateOf(RootCalibrationProxy.AlspsStatus())
    val statusText = mutableStateOf("尚未读取")
    val calibrationResult = mutableStateOf("尚未开始")
    val calibrationButtonText = mutableStateOf("开始 ALS/PS 校准（暂不可用）")
    val lastBroadcast = mutableStateOf("暂无后台广播结果")
    val isBusy = mutableStateOf(false)
    val refreshInFlight = mutableStateOf(false)

    private val calibrationStep = mutableStateOf(0)

    fun onProximityBroadcastResult(value: String) {
        lastBroadcast.value = "后台广播返回：$value"
        coordinator.appendLog("broadcast get_proximi_ps_acton => $value")
    }

    fun refreshStatus(silent: Boolean) {
        if (refreshInFlight.value) return
        refreshInFlight.value = true
        Thread {
            val result = coordinator.rootProxy.getAlspsStatus()
            val parsed = coordinator.rootProxy.parseAlspsStatus(result)
            coordinator.post {
                refreshInFlight.value = false
                if (parsed != null) {
                    alspsState.value = parsed
                    statusText.value = "状态已刷新（自动）"
                } else {
                    statusText.value = "读取失败"
                }
                if (!silent) {
                    coordinator.appendLog("alsps-status exit=${result.exitCode}")
                    if (result.combined.isNotBlank()) {
                        coordinator.appendLog(result.combined)
                    }
                }
            }
        }.start()
    }

    fun requestProximityBroadcast() {
        if (isBusy.value) return
        isBusy.value = true
        coordinator.sendBroadcast(
            Intent(CalibrationReceiver.ACTION_NOTIFY_GET_PROXIMI_PS)
                .setPackage(coordinator.packageName)
        )
        lastBroadcast.value = "已发送后台广播请求，等待返回…"
        coordinator.appendLog("broadcast notify_get_proximi_ps_acton")
        coordinator.postDelayed(800L) {
            isBusy.value = false
        }
    }

    fun onCalibrationRequested() {
        if (isBusy.value) return
        isBusy.value = true
        calibrationResult.value = "校准进行中"
        val currentStep = calibrationStep.value
        coordinator.appendLog("start alsps calibration step=${currentStep + 1}")
        Thread {
            val result = when (currentStep) {
                0 -> runCalibrationStep1()
                1 -> runCalibrationStep2()
                2 -> runCalibrationStep3()
                else -> resetCalibrationFlow()
            }
            coordinator.post {
                isBusy.value = false
                calibrationResult.value = result.first
                coordinator.appendMultiLineLog(result.second)
                refreshStatus(false)
            }
        }.start()
    }

    private fun runCalibrationStep1(): Pair<String, String> {
        val details = mutableListOf<String>()
        val noiseResult = coordinator.rootProxy.syncAlspsNoise()
        details += "sync-noise exit=${noiseResult.exitCode}"
        if (noiseResult.combined.isNotBlank()) details += noiseResult.combined

        val minResult = coordinator.rootProxy.captureAlspsMin()
        val minValue = coordinator.rootProxy.extractResultValue(minResult)?.toIntOrNull()
        details += "capture-min exit=${minResult.exitCode}"
        if (minResult.combined.isNotBlank()) details += minResult.combined
        if (minValue == null) {
            calibrationStep.value = -1
            calibrationButtonText.value = "重试 ALS/PS 校准（暂不可用）"
            return "ALS/PS 校准失败：未获取到有效 Min" to details.joinToString("\n")
        }

        val standard = parseAlspsStandard(alspsState.value.standard)
        if (standard != null && (minValue < 0 || minValue > standard.minStd)) {
            calibrationStep.value = -1
            calibrationButtonText.value = "重试 ALS/PS 校准（暂不可用）"
            details += "min validation failed min=$minValue minStd=${standard.minStd}"
            return "ALS/PS 校准失败：Min 超出标准" to details.joinToString("\n")
        }

        val writeMin = coordinator.rootProxy.writeAlspsMin(minValue)
        details += "write-min exit=${writeMin.exitCode}"
        if (writeMin.combined.isNotBlank()) details += writeMin.combined
        if (writeMin.exitCode != 0) {
            calibrationStep.value = -1
            calibrationButtonText.value = "重试 ALS/PS 校准（暂不可用）"
            return "ALS/PS 校准失败：Min 写入失败" to details.joinToString("\n")
        }

        calibrationStep.value = 1
        calibrationButtonText.value = "下一步：采集 Max（暂不可用）"
        return "ALS/PS 第一步完成：Min = $minValue" to details.joinToString("\n")
    }

    private fun runCalibrationStep2(): Pair<String, String> {
        val details = mutableListOf<String>()
        val maxResult = coordinator.rootProxy.captureAlspsMax()
        val maxValue = coordinator.rootProxy.extractResultValue(maxResult)?.toIntOrNull()
        details += "capture-max exit=${maxResult.exitCode}"
        if (maxResult.combined.isNotBlank()) details += maxResult.combined
        if (maxValue == null) {
            calibrationStep.value = -1
            calibrationButtonText.value = "重试 ALS/PS 校准（暂不可用）"
            return "ALS/PS 校准失败：未获取到有效 Max" to details.joinToString("\n")
        }

        val standard = parseAlspsStandard(alspsState.value.standard)
        if (standard != null && (maxValue <= 0 || maxValue < standard.maxStd)) {
            calibrationStep.value = -1
            calibrationButtonText.value = "重试 ALS/PS 校准（暂不可用）"
            details += "max validation failed max=$maxValue maxStd=${standard.maxStd}"
            return "ALS/PS 校准失败：Max 低于标准" to details.joinToString("\n")
        }

        val writeMax = coordinator.rootProxy.writeAlspsMax(maxValue)
        details += "write-max exit=${writeMax.exitCode}"
        if (writeMax.combined.isNotBlank()) details += writeMax.combined
        if (writeMax.exitCode != 0) {
            calibrationStep.value = -1
            calibrationButtonText.value = "重试 ALS/PS 校准（暂不可用）"
            return "ALS/PS 校准失败：Max 写入失败" to details.joinToString("\n")
        }

        calibrationStep.value = 2
        calibrationButtonText.value = "下一步：写入阈值（暂不可用）"
        return "ALS/PS 第二步完成：Max = $maxValue" to details.joinToString("\n")
    }

    private fun runCalibrationStep3(): Pair<String, String> {
        val details = mutableListOf<String>()
        val minValue = alspsState.value.minValue
        val applyResult = coordinator.rootProxy.applyAlspsDefaultThresholds(minValue)
        details += "apply-thresholds exit=${applyResult.exitCode}"
        if (applyResult.combined.isNotBlank()) details += applyResult.combined
        if (applyResult.exitCode != 0) {
            calibrationStep.value = -1
            calibrationButtonText.value = "重试 ALS/PS 校准（暂不可用）"
            return "ALS/PS 校准失败：阈值写入失败" to details.joinToString("\n")
        }

        coordinator.sendBroadcast(Intent("agold.intent.action.ALSPS_CALIBRATE_OK"))
        details += "broadcast agold.intent.action.ALSPS_CALIBRATE_OK"
        calibrationStep.value = -1
        calibrationButtonText.value = "重试 ALS/PS 校准（暂不可用）"
        return "ALS/PS 校准完成" to details.joinToString("\n")
    }

    private fun resetCalibrationFlow(): Pair<String, String> {
        calibrationStep.value = 0
        calibrationButtonText.value = "开始 ALS/PS 校准（暂不可用）"
        return "ALS/PS 校准流程已重置" to "reset calibration flow"
    }

    private data class AlspsStandard(
        val minStd: Int,
        val maxStd: Int,
        val offsetStd: Int,
        val thresholdGain: Int,
        val thresholdMin: Int
    )

    private fun parseAlspsStandard(raw: String?): AlspsStandard? {
        val parts = raw
            ?.split(',', '-')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: return null
        if (parts.size < 5) return null
        return AlspsStandard(
            minStd = parts[0],
            maxStd = parts[1],
            offsetStd = parts[2],
            thresholdGain = parts[3],
            thresholdMin = parts[4]
        )
    }
}