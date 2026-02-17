package com.agui.calibration

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal fun GyroscopeScreen(pageState: GyroscopePageState) {
    Text("陀螺仪校准", style = MaterialTheme.typography.headlineMedium)
    Text("保持设备稳定，等待校准完成。校准结果为 X,Y,Z 三轴的偏移值，单位为 °/s")
    SensorCard("当前角速度", pageState.gyroValues.value)
    StatusCard("校准状态", pageState.stateText.value)
    StatusCard("最近结果", pageState.resultText.value)
    ActionRow(
        primaryLabel = "开始校准",
        primaryEnabled = !pageState.isCalibrating.value,
        primaryBusy = pageState.isCalibrating.value,
        onPrimary = pageState::startCalibration
    )
}