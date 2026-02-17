package com.agui.calibration

import android.content.Intent
import androidx.compose.runtime.mutableStateOf

internal class GyroscopePageState(
    private val coordinator: AppCoordinator
) {

    val gyroValues = mutableStateOf(FloatArray(3))
    val stateText = mutableStateOf("尚未开始")
    val resultText = mutableStateOf("-")
    val isCalibrating = mutableStateOf(false)

    fun onSensorChanged(values: FloatArray) {
        gyroValues.value = floatArrayOf(values[0], values[1], values[2])
    }

    fun startCalibration() {
        if (isCalibrating.value) return
        isCalibrating.value = true
        stateText.value = "校准中，请保持设备稳定"
        resultText.value = "pending"
        coordinator.appendLog("start calibrate-gyroscope")

        Thread {
            val result = coordinator.rootProxy.calibrateGyroscope()
            val parsed = coordinator.rootProxy.extractResultValue(result) ?: "error"
            coordinator.post {
                isCalibrating.value = false
                resultText.value = parsed
                val success = result.exitCode == 0 && parsed.contains(',')
                stateText.value = if (success) "校准成功" else "校准失败：$parsed"
                if (success) {
                    coordinator.sendBroadcast(Intent("agold.intent.action.gyroscope_calibrate_ok"))
                }
                coordinator.appendLog("calibrate-gyroscope exit=${result.exitCode}")
                if (result.combined.isNotBlank()) {
                    coordinator.appendLog(result.combined)
                }
            }
        }.start()
    }
}