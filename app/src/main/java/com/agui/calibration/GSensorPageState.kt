package com.agui.calibration

import androidx.compose.runtime.mutableStateOf

internal class GSensorPageState(
    private val coordinator: AppCoordinator
) {

    val accelValues = mutableStateOf(FloatArray(3))
    val stateText = mutableStateOf("尚未开始")
    val resultText = mutableStateOf("-")
    val isCalibrating = mutableStateOf(false)

    fun onSensorChanged(values: FloatArray) {
        accelValues.value = floatArrayOf(values[0], values[1], values[2])
    }

    fun startCalibration() {
        if (isCalibrating.value) return
        isCalibrating.value = true
        stateText.value = "校准中，请保持平放静止"
        resultText.value = "pending"
        coordinator.appendLog("start calibrate-gsensor")

        Thread {
            val result = coordinator.rootProxy.calibrateGSensor()
            val parsed = coordinator.rootProxy.extractResultValue(result) ?: "error"
            coordinator.post {
                isCalibrating.value = false
                resultText.value = parsed
                stateText.value = if (parsed == "0") "校准成功" else "校准失败：$parsed"
                coordinator.appendLog("calibrate-gsensor exit=${result.exitCode}")
                if (result.combined.isNotBlank()) {
                    coordinator.appendLog(result.combined)
                }
            }
        }.start()
    }
}