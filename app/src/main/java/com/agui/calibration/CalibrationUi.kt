package com.agui.calibration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.agui.calibration.adb.WirelessAdbManager
import com.agui.calibration.root.RootCalibrationProxy
import com.agui.calibration.ui.theme.AguiCalibrationTheme
import java.util.Locale

@Composable
internal fun CalibrationApp(
    selectedTab: MutableState<CalibrationTab>,
    bridgeState: MutableState<String>,
    adbAuthorizationState: MutableState<String>,
    notificationPermissionState: MutableState<String>,
    manualDaemonCommands: MutableState<List<String>>,
    pairingEndpoint: MutableState<WirelessAdbManager.Endpoint?>,
    pairingCodeInput: MutableState<String>,
    pairingPortInput: MutableState<String>,
    showPairDialog: MutableState<Boolean>,
    accelValues: MutableState<FloatArray>,
    gyroValues: MutableState<FloatArray>,
    gsensorState: MutableState<String>,
    gsensorResult: MutableState<String>,
    gyroscopeState: MutableState<String>,
    gyroscopeResult: MutableState<String>,
    alspsState: MutableState<RootCalibrationProxy.AlspsStatus>,
    alspsStatusText: MutableState<String>,
    alspsCalibrationResult: MutableState<String>,
    alspsCalibrationButtonText: MutableState<String>,
    lastBroadcast: MutableState<String>,
    isScanningPairPort: MutableState<Boolean>,
    isAuthorizingAdb: MutableState<Boolean>,
    isRestartingDaemon: MutableState<Boolean>,
    isGsensorCalibrating: MutableState<Boolean>,
    isGyroscopeCalibrating: MutableState<Boolean>,
    isAlspsBusy: MutableState<Boolean>,
    logs: MutableState<List<String>>,
    onTabSelected: (CalibrationTab) -> Unit,
    onStartAuthorizationFlow: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onDismissPairDialog: () -> Unit,
    onRefreshBridge: () -> Unit,
    onReconnectRootDaemon: () -> Unit,
    onStopRootDaemon: () -> Unit,
    onPairAndAuthorize: () -> Unit,
    onRefreshAlsps: () -> Unit,
    onAlspsCalibrate: () -> Unit,
    onGSensorCalibrate: () -> Unit,
    onGyroscopeCalibrate: () -> Unit,
    onRequestBroadcast: () -> Unit
) {
    if (showPairDialog.value) {
        AlertDialog(
            onDismissRequest = onDismissPairDialog,
            title = { Text("输入无线调试配对信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        pairingEndpoint.value?.let {
                            "已扫描到配对端口 ${it.host}:${it.port}。请在“使用配对码配对设备”页面输入这里的六位授权码。"
                        } ?: "请在“使用配对码配对设备”页面输入这里的六位授权码。"
                    )
                    OutlinedTextField(
                        value = pairingPortInput.value,
                        onValueChange = { pairingPortInput.value = it.filter(Char::isDigit) },
                        label = { Text("配对端口") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = pairingCodeInput.value,
                        onValueChange = { pairingCodeInput.value = it.filter(Char::isDigit).take(6) },
                        label = { Text("六位授权码") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onPairAndAuthorize) {
                    Text("开始授权")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissPairDialog) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                CalibrationTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab.value == tab,
                        onClick = { onTabSelected(tab) },
                        icon = { Text(tab.iconText) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab.value) {
                CalibrationTab.Authorization -> ScreenColumn {
                    AuthorizationScreen(
                        daemonState = bridgeState.value,
                        adbAuthorizationState = adbAuthorizationState.value,
                        notificationPermissionState = notificationPermissionState.value,
                        manualDaemonCommands = manualDaemonCommands.value,
                        pairingEndpoint = pairingEndpoint.value,
                        isScanningPairPort = isScanningPairPort.value,
                        isAuthorizingAdb = isAuthorizingAdb.value,
                        isRestartingDaemon = isRestartingDaemon.value,
                        logs = logs.value,
                        onStartAuthorizationFlow = onStartAuthorizationFlow,
                        onRequestNotificationPermission = onRequestNotificationPermission,
                        onReconnectRootDaemon = onReconnectRootDaemon,
                        onStopRootDaemon = onStopRootDaemon,
                        onRefreshBridge = onRefreshBridge
                    )
                }

                CalibrationTab.GSensor -> ScreenColumn {
                    Text("重力计校准", style = MaterialTheme.typography.headlineMedium)
                    Text("静止平放时，Z 应为 9.81, X/Y 应接近 0")
                    StatusCard("Vendor 服务状态", bridgeState.value)
                    SensorCard("当前重力加速度", accelValues.value)
                    StatusCard("校准状态", gsensorState.value)
                    StatusCard("最近结果", gsensorResult.value)
                    ActionRow(
                        primaryLabel = "刷新连接",
                        primaryEnabled = true,
                        onPrimary = onRefreshBridge,
                        secondaryLabel = "开始校准",
                        secondaryEnabled = !isGsensorCalibrating.value,
                        secondaryBusy = isGsensorCalibrating.value,
                        onSecondary = onGSensorCalibrate
                    )
                    LogsCard(logs.value)
                }

                CalibrationTab.Gyroscope -> ScreenColumn {
                    Text("陀螺仪校准", style = MaterialTheme.typography.headlineMedium)
                    Text("保持设备稳定，等待校准完成。校准结果为 X,Y,Z 三轴的偏移值，单位为 °/s")
                    StatusCard("Vendor 服务状态", bridgeState.value)
                    SensorCard("当前角速度", gyroValues.value)
                    StatusCard("校准状态", gyroscopeState.value)
                    StatusCard("最近结果", gyroscopeResult.value)
                    ActionRow(
                        primaryLabel = "刷新连接",
                        primaryEnabled = true,
                        onPrimary = onRefreshBridge,
                        secondaryLabel = "开始校准",
                        secondaryEnabled = !isGyroscopeCalibrating.value,
                        secondaryBusy = isGyroscopeCalibrating.value,
                        onSecondary = onGyroscopeCalibrate
                    )
                    LogsCard(logs.value)
                }

                CalibrationTab.Alsps -> ScreenColumn {
                    val state = alspsState.value
                    Text("ALS / PS 校准", style = MaterialTheme.typography.headlineMedium)
                    StatusCard(
                        "原版 ALS/PS 提示",
                        ALSPS_ORIGINAL_TIPS.joinToString("\n\n")
                    )
                    StatusCard("Vendor 服务状态", bridgeState.value)
                    StatusCard("当前状态", alspsStatusText.value)
                    StatusCard("校准流程", alspsCalibrationResult.value)
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
                    StatusCard("后台广播", lastBroadcast.value)
                    AlspsActions(
                        busy = isAlspsBusy.value,
                        buttonText = alspsCalibrationButtonText.value,
                        onCalibrate = onAlspsCalibrate,
                        onRequestBroadcast = onRequestBroadcast
                    )
                    LogsCard(logs.value)
                }
            }
        }
    }
}

@Composable
private fun AuthorizationScreen(
    daemonState: String,
    adbAuthorizationState: String,
    notificationPermissionState: String,
    manualDaemonCommands: List<String>,
    pairingEndpoint: WirelessAdbManager.Endpoint?,
    isScanningPairPort: Boolean,
    isAuthorizingAdb: Boolean,
    isRestartingDaemon: Boolean,
    logs: List<String>,
    onStartAuthorizationFlow: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onReconnectRootDaemon: () -> Unit,
    onStopRootDaemon: () -> Unit,
    onRefreshBridge: () -> Unit
) {
    val busy = isScanningPairPort || isAuthorizingAdb || isRestartingDaemon
    Text("无线 ADB 授权", style = MaterialTheme.typography.headlineMedium)
    Text(
        buildString {
            append("1. 先授予通知权限，后续会用通知内联输入授权码。\n")
            append("2. 点下方按钮后，App 会直达 Wireless debugging 页面，并在后台持续扫描配对端口。\n")
            append("3. 在设置中开启无线调试，再点“使用配对码配对设备”。\n")
            append("4. 扫描到端口后，系统会弹出高优先级通知；直接在通知里输入六位授权码，不要切回 App。\n")
            append("5. App 会自动连接无线 ADB、请求 root，并启动 root daemon。\n\n")
            append("手动 adb 启动 daemon：\n")
            manualDaemonCommands.forEachIndexed { index, command ->
                append(index + 1)
                append(". ")
                append(command)
                if (index != manualDaemonCommands.lastIndex) append("\n")
            }
        }
    )
    StatusCard("通知权限", notificationPermissionState)
    StatusCard("ADB 状态", adbAuthorizationState)
    StatusCard("root daemon 状态", daemonState)
    StatusCard(
        "已发现的配对端口",
        pairingEndpoint?.let { "${it.host}:${it.port}" }
            ?: "尚未扫描到，请在 Wireless debugging 的“使用配对码配对设备”页面停留几秒"
    )
    FullWidthBusyButton(
        label = "申请通知权限",
        enabled = true,
        busy = false,
        onClick = onRequestNotificationPermission
    )
    FullWidthBusyButton(
        label = "打开 Wireless debugging 并等待配对码",
        enabled = !busy,
        busy = isScanningPairPort,
        onClick = onStartAuthorizationFlow
    )
    FullWidthBusyButton(
        label = "重连 adb 并启动 root daemon",
        enabled = !busy,
        busy = isRestartingDaemon,
        onClick = onReconnectRootDaemon
    )
    FullWidthBusyButton(
        label = "停止 root daemon",
        enabled = !busy,
        busy = false,
        onClick = onStopRootDaemon
    )
    FullWidthBusyButton(
        label = "刷新 root daemon 状态",
        enabled = !busy,
        busy = false,
        onClick = onRefreshBridge
    )
    LogsCard(logs)
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun StatusCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body)
        }
    }
}

@Composable
private fun SensorCard(title: String, values: FloatArray) {
    StatusCard(
        title,
        String.format(
            Locale.US,
            "X = %.3f\nY = %.3f\nZ = %.3f",
            values.getOrElse(0) { 0f },
            values.getOrElse(1) { 0f },
            values.getOrElse(2) { 0f }
        )
    )
}

@Composable
private fun ActionRow(
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    secondaryLabel: String,
    secondaryEnabled: Boolean,
    secondaryBusy: Boolean,
    onSecondary: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onPrimary, enabled = primaryEnabled) {
            Text(primaryLabel)
        }
        Button(onClick = onSecondary, enabled = secondaryEnabled) {
            if (secondaryBusy) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
            } else {
                Text(secondaryLabel)
            }
        }
    }
}

@Composable
private fun FullWidthBusyButton(
    label: String,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
        } else {
            Text(label)
        }
    }
}

@Composable
private fun AlspsActions(
    busy: Boolean,
    buttonText: String,
    onCalibrate: () -> Unit,
    onRequestBroadcast: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRequestBroadcast, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
            } else {
                Text("触发后台查询广播")
            }
        }
        Button(
            onClick = onCalibrate,
            modifier = Modifier.fillMaxWidth(),
            enabled = false
        ) {
            Text(buttonText)
        }
    }
}

@Composable
private fun LogsCard(logs: List<String>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("日志", style = MaterialTheme.typography.titleMedium)
            if (logs.isEmpty()) {
                Text("暂无日志")
            } else {
                logs.forEach {
                    Text(it, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun CalibrationPreview() {
    AguiCalibrationTheme {
        CalibrationApp(
            selectedTab = remember { mutableStateOf(CalibrationTab.Authorization) },
            bridgeState = remember { mutableStateOf("root daemon 已连接，vendor HAL 可访问") },
            adbAuthorizationState = remember { mutableStateOf("无线 ADB 已授权") },
            notificationPermissionState = remember { mutableStateOf("已授权") },
            manualDaemonCommands = remember {
                mutableStateOf(
                    listOf(
                        "adb root",
                        "adb shell sh /data/user/0/com.agui.calibration/files/start_root_daemon.sh"
                    )
                )
            },
            pairingEndpoint = remember { mutableStateOf(WirelessAdbManager.Endpoint("127.0.0.1", 37099)) },
            pairingCodeInput = remember { mutableStateOf("123456") },
            pairingPortInput = remember { mutableStateOf("37099") },
            showPairDialog = remember { mutableStateOf(false) },
            accelValues = remember { mutableStateOf(floatArrayOf(0f, 0f, 9.8f)) },
            gyroValues = remember { mutableStateOf(floatArrayOf(0.01f, -0.02f, 0.0f)) },
            gsensorState = remember { mutableStateOf("尚未开始") },
            gsensorResult = remember { mutableStateOf("0") },
            gyroscopeState = remember { mutableStateOf("校准成功") },
            gyroscopeResult = remember { mutableStateOf("0.01,0.02,0.03") },
            alspsState = remember {
                mutableStateOf(
                    RootCalibrationProxy.AlspsStatus(
                        currentPs = 123,
                        minValue = 100,
                        maxValue = 1800,
                        thresholdHigh = 900,
                        thresholdLow = 820,
                        standard = "800,2000,800,80,1000"
                    )
                )
            },
            alspsStatusText = remember { mutableStateOf("状态已刷新（自动）") },
            alspsCalibrationResult = remember { mutableStateOf("尚未开始") },
            alspsCalibrationButtonText = remember { mutableStateOf("开始 ALS/PS 校准（暂不可用）") },
            lastBroadcast = remember { mutableStateOf("后台广播返回：123") },
            isScanningPairPort = remember { mutableStateOf(false) },
            isAuthorizingAdb = remember { mutableStateOf(false) },
            isRestartingDaemon = remember { mutableStateOf(false) },
            isGsensorCalibrating = remember { mutableStateOf(false) },
            isGyroscopeCalibrating = remember { mutableStateOf(false) },
            isAlspsBusy = remember { mutableStateOf(false) },
            logs = remember {
                mutableStateOf(
                    listOf(
                        "preview: pairing endpoint 127.0.0.1:37099",
                        "preview: root daemon started"
                    )
                )
            },
            onTabSelected = {},
            onStartAuthorizationFlow = {},
            onRequestNotificationPermission = {},
            onDismissPairDialog = {},
            onRefreshBridge = {},
            onReconnectRootDaemon = {},
            onStopRootDaemon = {},
            onPairAndAuthorize = {},
            onRefreshAlsps = {},
            onAlspsCalibrate = {},
            onGSensorCalibrate = {},
            onGyroscopeCalibrate = {},
            onRequestBroadcast = {}
        )
    }
}
