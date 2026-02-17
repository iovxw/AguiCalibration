package com.agui.calibration

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal fun GSensorScreen(pageState: GSensorPageState) {
    Text("重力计校准", style = MaterialTheme.typography.headlineMedium)
    Text("静止平放时，Z 应为 9.81, X/Y 应接近 0")
    SensorCard("当前重力加速度", pageState.accelValues.value)
    StatusCard("校准状态", pageState.stateText.value)
    StatusCard("最近结果", pageState.resultText.value)
    ActionRow(
        primaryLabel = "开始校准",
        primaryEnabled = !pageState.isCalibrating.value,
        primaryBusy = pageState.isCalibrating.value,
        onPrimary = pageState::startCalibration
    )
}