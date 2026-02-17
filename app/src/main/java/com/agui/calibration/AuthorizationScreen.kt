package com.agui.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
internal fun AuthorizationScreen(pageState: AuthorizationPageState) {
    if (pageState.showPairDialog.value) {
        AlertDialog(
            onDismissRequest = { pageState.showPairDialog.value = false },
            title = { Text("输入无线调试配对信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(CommonSpacing)) {
                    Text(
                        pageState.pairingEndpoint.value?.let {
                            "已扫描到配对端口 ${it.host}:${it.port}。请在“使用配对码配对设备”页面输入这里的六位授权码。"
                        } ?: "请在“使用配对码配对设备”页面输入这里的六位授权码。"
                    )
                    OutlinedTextField(
                        value = pageState.pairingPortInput.value,
                        onValueChange = { pageState.pairingPortInput.value = it.filter(Char::isDigit) },
                        label = { Text("配对端口") },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pageState.pairingCodeInput.value,
                        onValueChange = { pageState.pairingCodeInput.value = it.filter(Char::isDigit).take(6) },
                        label = { Text("六位授权码") },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = pageState::startWirelessAuthorization) {
                    Text("开始授权")
                }
            },
            dismissButton = {
                TextButton(onClick = { pageState.showPairDialog.value = false }) {
                    Text("取消")
                }
            }
        )
    }

    val busy = pageState.isScanningPairPort.value || pageState.isAuthorizingAdb.value || pageState.isRestartingDaemon.value
    Text("无线 ADB 授权", style = MaterialTheme.typography.headlineMedium)
    Text(
        buildString {
            append("1. 先授予通知权限，后续会用通知内联输入授权码。\n")
            append("2. 点下方按钮后，App 会直达 Wireless debugging 页面，并在后台持续扫描配对端口。\n")
            append("3. 在设置中开启无线调试，再点“使用配对码配对设备”。\n")
            append("4. 扫描到端口后，系统会弹出高优先级通知；直接在通知里输入六位授权码，不要切回 App。\n")
            append("5. App 会自动连接无线 ADB、请求 root，并启动 root daemon。\n\n")
            append("手动 adb 启动 daemon：\n")
            pageState.manualDaemonCommands.value.forEachIndexed { index, command ->
                append(index + 1)
                append(". ")
                append(command)
                if (index != pageState.manualDaemonCommands.value.lastIndex) append("\n")
            }
        }
    )
    StatusCard("通知权限", pageState.notificationPermissionState.value)
    StatusCard("ADB 状态", pageState.adbAuthorizationState.value)
    StatusCard("root daemon 状态", pageState.daemonState.value)
    StatusCard(
        "已发现的配对端口",
        pageState.pairingEndpoint.value?.let { "${it.host}:${it.port}" }
            ?: "尚未扫描到，请在 Wireless debugging 的“使用配对码配对设备”页面停留几秒"
    )
    FullWidthBusyButton(
        label = "申请通知权限",
        enabled = true,
        busy = false,
        onClick = pageState::requestNotificationPermission
    )
    FullWidthBusyButton(
        label = "打开 Wireless debugging 并等待配对码",
        enabled = !busy,
        busy = pageState.isScanningPairPort.value,
        onClick = pageState::openWirelessDebuggingAndScan
    )
    FullWidthBusyButton(
        label = "重连 adb 并启动 root daemon",
        enabled = !busy,
        busy = pageState.isRestartingDaemon.value,
        onClick = pageState::reconnectRootDaemon
    )
    FullWidthBusyButton(
        label = "停止 root daemon",
        enabled = !busy,
        busy = false,
        onClick = pageState::stopRootDaemon
    )
    FullWidthBusyButton(
        label = "刷新 root daemon 状态",
        enabled = !busy,
        busy = false,
        onClick = { pageState.refreshBridgeState(initial = false) }
    )
}