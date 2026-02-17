package com.agui.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun AlspsScreen(pageState: AlspsPageState) {
    val state = pageState.alspsState.value
    Text("ALS / PS 校准", style = MaterialTheme.typography.headlineMedium)
    StatusCard(
        "原版 ALS/PS 提示",
        ALSPS_ORIGINAL_TIPS.joinToString("\n\n")
    )
    StatusCard("当前状态", pageState.statusText.value)
    StatusCard("校准流程", pageState.calibrationResult.value)
    StatusCard(
        "PS / 阈值信息",
        buildString {
            append("PS = ${state.currentPs ?: "-"}\n")
            append("Min = ${state.minValue ?: "-"}\n")
            append("Max = ${state.maxValue ?: "-"}\n")
            append("Threshold High = ${state.thresholdHigh ?: "-"}\n")
            append("Threshold Low = ${state.thresholdLow ?: "-"}\n")
            append("Standard = ${state.standard ?: "-"}")
        }
    )
    StatusCard("后台广播", pageState.lastBroadcast.value)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = pageState::requestProximityBroadcast,
            modifier = Modifier.fillMaxWidth(),
            enabled = !pageState.isBusy.value
        ) {
            if (pageState.isBusy.value) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            } else {
                Text("触发后台查询广播")
            }
        }
        Button(
            onClick = pageState::onCalibrationRequested,
            modifier = Modifier.fillMaxWidth(),
            enabled = false
        ) {
            Text(pageState.calibrationButtonText.value)
        }
        FullWidthBusyButton(
            label = "刷新 ALS/PS 状态",
            enabled = !pageState.refreshInFlight.value,
            busy = pageState.refreshInFlight.value,
            onClick = { pageState.refreshStatus(silent = false) }
        )
    }
}