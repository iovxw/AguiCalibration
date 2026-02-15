package com.agui.calibration

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.agui.calibration.adb.WirelessAdbManager
import com.agui.calibration.root.RootDaemonScriptInstaller
import com.agui.calibration.root.RootCalibrationProxy
import com.agui.calibration.ui.theme.AguiCalibrationTheme

internal enum class CalibrationTab(val label: String, val iconText: String) {
    Authorization("授权", "A"),
    GSensor("重力计", "G"),
    Gyroscope("陀螺仪", "Y"),
    Alsps("ALS/PS", "P")
}

private const val WIRELESS_DEBUGGING_FRAGMENT = "com.android.settings.development.WirelessDebuggingFragment"
private const val SETTINGS_SOURCE_METRICS = ":settings:source_metrics"
private const val SETTINGS_SHOW_FRAGMENT = ":settings:show_fragment"
private const val SETTINGS_SHOW_FRAGMENT_AS_SUBSETTING = ":settings:show_fragment_as_subsetting"

internal val ALSPS_ORIGINAL_TIPS = listOf(
    "此操作分三步完成。\n步骤一：移除距离传感器正前方物体，点击开始。",
    "步骤二：请将标准遮挡物放置在距离传感器前,\n尽最大可能靠近距离传感器，\n点击下一步。",
    "步骤二：请将遮挡物放置在距离传感器\n正前方，点击下一步。",
    "步骤三：请将遮挡物渐渐靠近距离传感器\n或远离距离传感器正前方，验证校准是否成功。",
    "测试：点击下一步,\n并将遮挡物渐渐靠近距离传感器\n或远离距离传感器正前方，验证校准是否成功。"
)

class MainActivity : ComponentActivity(), SensorEventListener {

    private val selectedTab = mutableStateOf(CalibrationTab.Authorization)
    private val accelValues = mutableStateOf(FloatArray(3))
    private val gyroValues = mutableStateOf(FloatArray(3))
    private val bridgeState = mutableStateOf("未连接")
    private val adbAuthorizationState = mutableStateOf("请先在开发者选项中开启无线调试，然后完成配对")
    private val notificationPermissionState = mutableStateOf("未检查")
    private val manualDaemonCommands = mutableStateOf(listOf<String>())
    private val pairingEndpoint = mutableStateOf<WirelessAdbManager.Endpoint?>(null)
    private val pairingCodeInput = mutableStateOf("")
    private val pairingPortInput = mutableStateOf("")
    private val showPairDialog = mutableStateOf(false)
    private val gsensorState = mutableStateOf("尚未开始")
    private val gsensorResult = mutableStateOf("-")
    private val gyroscopeState = mutableStateOf("尚未开始")
    private val gyroscopeResult = mutableStateOf("-")
    private val alspsState = mutableStateOf(RootCalibrationProxy.AlspsStatus())
    private val alspsStatusText = mutableStateOf("尚未读取")
    private val alspsCalibrationStep = mutableStateOf(0)
    private val alspsCalibrationResult = mutableStateOf("尚未开始")
    private val alspsCalibrationButtonText = mutableStateOf("开始 ALS/PS 校准（暂不可用）")
    private val lastBroadcast = mutableStateOf("暂无后台广播结果")
    private val isScanningPairPort = mutableStateOf(false)
    private val isAuthorizingAdb = mutableStateOf(false)
    private val isRestartingDaemon = mutableStateOf(false)
    private val isGsensorCalibrating = mutableStateOf(false)
    private val isGyroscopeCalibrating = mutableStateOf(false)
    private val isAlspsBusy = mutableStateOf(false)
    private val logs = mutableStateOf(listOf<String>())

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private lateinit var rootProxy: RootCalibrationProxy
    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updateNotificationPermissionState()
    }

    @Volatile
    private var alspsRefreshInFlight = false

    private val alspsAutoRefresh = object : Runnable {
        override fun run() {
            refreshAlspsStatus(true)
            mainHandler.postDelayed(this, 1_500L)
        }
    }

    private val proximiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val value = intent?.getStringExtra(CalibrationReceiver.EXTRA_GET_PROXIMI_PS_NAME)
                ?: "unavailable"
            lastBroadcast.value = "后台广播返回：$value"
            appendLog("broadcast get_proximi_ps_acton => $value")
        }
    }

    private val authStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val message = intent?.getStringExtra(WirelessDebuggingNotificationController.EXTRA_MESSAGE)
                ?: return
            val details = intent.getStringExtra(WirelessDebuggingNotificationController.EXTRA_DETAILS).orEmpty()
            val success = intent.getBooleanExtra(WirelessDebuggingNotificationController.EXTRA_SUCCESS, false)
            adbAuthorizationState.value = message
            appendMultiLineLog(details)
            refreshBridgeState(initial = false)
            if (success) {
                refreshAlspsStatus(true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        RootDaemonScriptInstaller.ensureInstalled(this)
        manualDaemonCommands.value = RootDaemonScriptInstaller.manualCommands(this)
        rootProxy = RootCalibrationProxy(this)
        registerReceiver(
            proximiReceiver,
            IntentFilter(CalibrationReceiver.ACTION_GET_PROXIMI_PS),
            RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            authStatusReceiver,
            IntentFilter(WirelessDebuggingNotificationController.ACTION_AUTH_STATUS),
            RECEIVER_NOT_EXPORTED
        )

        refreshBridgeState(initial = true)
        updateNotificationPermissionState()

        setContent {
            AguiCalibrationTheme {
                CalibrationApp(
                    selectedTab = selectedTab,
                    bridgeState = bridgeState,
                    adbAuthorizationState = adbAuthorizationState,
                    notificationPermissionState = notificationPermissionState,
                    manualDaemonCommands = manualDaemonCommands,
                    pairingEndpoint = pairingEndpoint,
                    pairingCodeInput = pairingCodeInput,
                    pairingPortInput = pairingPortInput,
                    showPairDialog = showPairDialog,
                    accelValues = accelValues,
                    gyroValues = gyroValues,
                    gsensorState = gsensorState,
                    gsensorResult = gsensorResult,
                    gyroscopeState = gyroscopeState,
                    gyroscopeResult = gyroscopeResult,
                    alspsState = alspsState,
                    alspsStatusText = alspsStatusText,
                    alspsCalibrationResult = alspsCalibrationResult,
                    alspsCalibrationButtonText = alspsCalibrationButtonText,
                    lastBroadcast = lastBroadcast,
                    isScanningPairPort = isScanningPairPort,
                    isAuthorizingAdb = isAuthorizingAdb,
                    isRestartingDaemon = isRestartingDaemon,
                    isGsensorCalibrating = isGsensorCalibrating,
                    isGyroscopeCalibrating = isGyroscopeCalibrating,
                    isAlspsBusy = isAlspsBusy,
                    logs = logs,
                    onTabSelected = { selectedTab.value = it },
                    onDismissPairDialog = { showPairDialog.value = false },
                    onStartAuthorizationFlow = { openWirelessDebuggingAndScan() },
                    onRequestNotificationPermission = { requestNotificationPermission() },
                    onRefreshBridge = { refreshBridgeState(initial = false) },
                    onReconnectRootDaemon = { reconnectRootDaemon() },
                    onPairAndAuthorize = { startWirelessAuthorization() },
                    onRefreshAlsps = { refreshAlspsStatus(false) },
                    onAlspsCalibrate = { onAlspsCalibrationRequested() },
                    onGSensorCalibrate = { startGSensorCalibration() },
                    onGyroscopeCalibrate = { startGyroscopeCalibration() },
                    onRequestBroadcast = { requestProximityBroadcast() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        updateNotificationPermissionState()
        refreshBridgeState(initial = false)
        refreshAlspsStatus(true)
        mainHandler.post(alspsAutoRefresh)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        mainHandler.removeCallbacks(alspsAutoRefresh)
    }

    override fun onDestroy() {
        unregisterReceiver(proximiReceiver)
        unregisterReceiver(authStatusReceiver)
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                accelValues.value = floatArrayOf(event.values[0], event.values[1], event.values[2])
            }

            Sensor.TYPE_GYROSCOPE -> {
                gyroValues.value = floatArrayOf(event.values[0], event.values[1], event.values[2])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            updateNotificationPermissionState()
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun updateNotificationPermissionState() {
        notificationPermissionState.value = if (WirelessDebuggingNotificationController.areNotificationsAllowed(this)) {
            "已授权，可在通知里直接输入配对码"
        } else {
            "未授权；需要通知权限才能在 Wireless debugging 页面通过通知直接输入配对码"
        }
    }

    private fun openWirelessDebuggingAndScan() {
        if (isScanningPairPort.value || isAuthorizingAdb.value || isRestartingDaemon.value) return
        if (!WirelessDebuggingNotificationController.areNotificationsAllowed(this)) {
            adbAuthorizationState.value = "请先授予通知权限，否则无法在 Wireless debugging 页面内通过通知输入授权码"
            return
        }

        scanPairingPort(deliverNotification = true, maxAttempts = 20)

        val primaryIntent = Intent(Intent.ACTION_MAIN).apply {
            component = ComponentName(
                "com.android.settings",
                "com.android.settings.SubSettings"
            )
            putExtra(SETTINGS_SHOW_FRAGMENT, WIRELESS_DEBUGGING_FRAGMENT)
        }
        val secondaryIntent = Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS").apply {
            component = ComponentName(
                "com.android.settings",
                "com.android.settings.Settings\$DevelopmentSettingsActivity"
            )
            putExtra(SETTINGS_SHOW_FRAGMENT, WIRELESS_DEBUGGING_FRAGMENT)
            putExtra(SETTINGS_SHOW_FRAGMENT_AS_SUBSETTING, true)
            putExtra(SETTINGS_SOURCE_METRICS, 0)
        }
        val fallbackIntent = Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
        runCatching { startActivity(primaryIntent) }
            .recoverCatching { startActivity(secondaryIntent) }
            .recoverCatching { startActivity(fallbackIntent) }
            .onSuccess {
                adbAuthorizationState.value = "已尝试直达 Wireless debugging 页面，正在后台扫描配对端口。请在该页面点“使用配对码配对设备”，扫描到后会通过通知直接输入。"
            }
            .onFailure {
                adbAuthorizationState.value = "无法打开设置，请手动进入开发者选项 > 无线调试"
                appendLog("open settings failed: ${it.message}")
            }
    }

    private fun scanPairingPort(deliverNotification: Boolean = false, maxAttempts: Int = 1) {
        if (isScanningPairPort.value || isAuthorizingAdb.value || isRestartingDaemon.value) return
        isScanningPairPort.value = true
        adbAuthorizationState.value = if (deliverNotification) {
            "正在后台扫描无线调试配对端口…"
        } else {
            "正在扫描无线调试配对端口…"
        }
        Thread {
            var endpoint: WirelessAdbManager.Endpoint? = null
            for (attempt in 0 until maxAttempts.coerceAtLeast(1)) {
                endpoint = WirelessAdbManager.discoverPairingEndpoint(this, 3_000L)
                if (endpoint != null) {
                    break
                }
                if (attempt < maxAttempts - 1) {
                    Thread.sleep(1_500L)
                }
            }
            mainHandler.post {
                isScanningPairPort.value = false
                pairingEndpoint.value = endpoint
                if (endpoint != null) {
                    pairingPortInput.value = endpoint.port.toString()
                    adbAuthorizationState.value = if (deliverNotification) {
                        "已发现配对端口：${endpoint.host}:${endpoint.port}，已发送通知，请直接在通知里输入六位授权码。"
                    } else {
                        "已发现配对端口：${endpoint.host}:${endpoint.port}，请输入六位授权码。"
                    }
                    appendLog("pairing endpoint ${endpoint.host}:${endpoint.port}")
                    if (deliverNotification) {
                        WirelessDebuggingNotificationController.showPairingReplyNotification(
                            applicationContext,
                            endpoint!!
                        )
                    } else {
                        pairingCodeInput.value = ""
                        showPairDialog.value = true
                    }
                } else {
                    adbAuthorizationState.value = if (deliverNotification) {
                        "未发现配对端口，请确认当前停留在 Wireless debugging 的“使用配对码配对设备”页面，然后再点一次开始按钮。"
                    } else {
                        "未发现配对端口，请确认无线调试的配对码页面已打开"
                    }
                    appendLog("pairing endpoint not found")
                }
            }
        }.start()
    }

    private fun startWirelessAuthorization() {
        val pairingCode = pairingCodeInput.value.trim()
        val pairingHost = pairingEndpoint.value?.host.orEmpty().ifBlank { "127.0.0.1" }
        val pairingPort = pairingPortInput.value.trim().toIntOrNull()
        if (pairingPort == null) {
            adbAuthorizationState.value = "请先输入有效的配对端口"
            return
        }
        showPairDialog.value = false
        isAuthorizingAdb.value = true
        adbAuthorizationState.value = "正在配对、连接、请求 root 并启动 root daemon…"
        Thread {
            val result = WirelessAdbManager.authorizeAndStartDaemon(
                context = this,
                pairingHost = pairingHost,
                pairingCode = pairingCode,
                pairingPort = pairingPort,
                packageCodePath = packageCodePath,
                rootProxy = rootProxy
            )
            mainHandler.post {
                isAuthorizingAdb.value = false
                adbAuthorizationState.value = result.message
                appendMultiLineLog(result.details)
                refreshBridgeState(initial = false)
                if (result.success) {
                    refreshAlspsStatus(true)
                }
            }
        }.start()
    }

    private fun reconnectRootDaemon() {
        if (isAuthorizingAdb.value || isRestartingDaemon.value) return
        isRestartingDaemon.value = true
        adbAuthorizationState.value = "正在重连无线调试并启动 root daemon…"
        Thread {
            val result = WirelessAdbManager.connectRootAndStartDaemon(
                context = this,
                packageCodePath = packageCodePath,
                rootProxy = rootProxy
            )
            mainHandler.post {
                isRestartingDaemon.value = false
                adbAuthorizationState.value = result.message
                appendMultiLineLog(result.details)
                refreshBridgeState(initial = false)
            }
        }.start()
    }

    private fun refreshBridgeState(initial: Boolean) {
        Thread {
            val result = rootProxy.probe()
            mainHandler.post {
                val available = result.exitCode == 0 && result.stdout.contains("OK service_found")
                bridgeState.value = if (available) {
                    "root daemon 已连接，vendor HAL 可访问"
                } else {
                    "root daemon 不可用或 vendor HAL 不可访问"
                }
                if (available) {
                    if (adbAuthorizationState.value.startsWith("请先") || adbAuthorizationState.value.contains("未就绪")) {
                        adbAuthorizationState.value = "无线 ADB 已就绪，root daemon 可用"
                    }
                }
                if (initial || !available) {
                    appendLog("probe exit=${result.exitCode}")
                    if (result.combined.isNotBlank()) {
                        appendLog(result.combined)
                    }
                }
            }
        }.start()
    }

    private fun refreshAlspsStatus(silent: Boolean) {
        if (alspsRefreshInFlight) return
        alspsRefreshInFlight = true
        Thread {
            val result = rootProxy.getAlspsStatus()
            val parsed = rootProxy.parseAlspsStatus(result)
            mainHandler.post {
                alspsRefreshInFlight = false
                if (parsed != null) {
                    alspsState.value = parsed
                    alspsStatusText.value = "状态已刷新（自动）"
                } else {
                    alspsStatusText.value = "读取失败"
                }
                if (!silent) {
                    appendLog("alsps-status exit=${result.exitCode}")
                    if (result.combined.isNotBlank()) {
                        appendLog(result.combined)
                    }
                }
            }
        }.start()
    }

    private fun startGSensorCalibration() {
        if (isGsensorCalibrating.value) return
        isGsensorCalibrating.value = true
        gsensorState.value = "校准中，请保持平放静止"
        gsensorResult.value = "pending"
        appendLog("start calibrate-gsensor")

        Thread {
            val result = rootProxy.calibrateGSensor()
            val parsed = rootProxy.extractResultValue(result) ?: "error"
            mainHandler.post {
                isGsensorCalibrating.value = false
                gsensorResult.value = parsed
                gsensorState.value = if (parsed == "0") "校准成功" else "校准失败：$parsed"
                appendLog("calibrate-gsensor exit=${result.exitCode}")
                if (result.combined.isNotBlank()) {
                    appendLog(result.combined)
                }
            }
        }.start()
    }

    private fun startGyroscopeCalibration() {
        if (isGyroscopeCalibrating.value) return
        isGyroscopeCalibrating.value = true
        gyroscopeState.value = "校准中，请保持设备稳定"
        gyroscopeResult.value = "pending"
        appendLog("start calibrate-gyroscope")

        Thread {
            val result = rootProxy.calibrateGyroscope()
            val parsed = rootProxy.extractResultValue(result) ?: "error"
            mainHandler.post {
                isGyroscopeCalibrating.value = false
                gyroscopeResult.value = parsed
                val success = result.exitCode == 0 && parsed.contains(',')
                gyroscopeState.value = if (success) "校准成功" else "校准失败：$parsed"
                if (success) {
                    sendBroadcast(Intent("agold.intent.action.gyroscope_calibrate_ok"))
                }
                appendLog("calibrate-gyroscope exit=${result.exitCode}")
                if (result.combined.isNotBlank()) {
                    appendLog(result.combined)
                }
            }
        }.start()
    }

    private fun requestProximityBroadcast() {
        if (isAlspsBusy.value) return
        isAlspsBusy.value = true
        sendBroadcast(Intent(CalibrationReceiver.ACTION_NOTIFY_GET_PROXIMI_PS).setPackage(packageName))
        lastBroadcast.value = "已发送后台广播请求，等待返回…"
        appendLog("broadcast notify_get_proximi_ps_acton")
        mainHandler.postDelayed({
            isAlspsBusy.value = false
        }, 800L)
    }

    private fun onAlspsCalibrationRequested() {
        if (isAlspsBusy.value) return
        isAlspsBusy.value = true
        alspsCalibrationResult.value = "校准进行中"
        val currentStep = alspsCalibrationStep.value
        appendLog("start alsps calibration step=${currentStep + 1}")
        Thread {
            val result = when (currentStep) {
                0 -> runAlspsCalibrationStep1()
                1 -> runAlspsCalibrationStep2()
                2 -> runAlspsCalibrationStep3()
                else -> resetAlspsCalibrationFlow()
            }
            mainHandler.post {
                isAlspsBusy.value = false
                alspsCalibrationResult.value = result.first
                appendMultiLineLog(result.second)
                refreshAlspsStatus(false)
            }
        }.start()
    }

    private fun runAlspsCalibrationStep1(): Pair<String, String> {
        val details = mutableListOf<String>()
        val noiseResult = rootProxy.syncAlspsNoise()
        details += "sync-noise exit=${noiseResult.exitCode}"
        if (noiseResult.combined.isNotBlank()) details += noiseResult.combined

        val minResult = rootProxy.captureAlspsMin()
        val minValue = rootProxy.extractResultValue(minResult)?.toIntOrNull()
        details += "capture-min exit=${minResult.exitCode}"
        if (minResult.combined.isNotBlank()) details += minResult.combined
        if (minValue == null) {
            alspsCalibrationStep.value = -1
            alspsCalibrationButtonText.value = "重试 ALS/PS 校准（暂不可用）"
            return "ALS/PS 校准失败：未获取到有效 Min" to details.joinToString("\n")
        }

        val standard = parseAlspsStandard(alspsState.value.standard)
        if (standard != null && (minValue < 0 || minValue > standard.minStd)) {
            alspsCalibrationStep.value = -1
            alspsCalibrationButtonText.value = "重试 ALS/PS 校准（暂不可用）"
            details += "min validation failed min=$minValue minStd=${standard.minStd}"
            return "ALS/PS 校准失败：Min 超出标准" to details.joinToString("\n")
        }

        val writeMin = rootProxy.writeAlspsMin(minValue)
        details += "write-min exit=${writeMin.exitCode}"
        if (writeMin.combined.isNotBlank()) details += writeMin.combined
        if (writeMin.exitCode != 0) {
            alspsCalibrationStep.value = -1
            alspsCalibrationButtonText.value = "重试 ALS/PS 校准（暂不可用）"
            return "ALS/PS 校准失败：Min 写入失败" to details.joinToString("\n")
        }

        alspsCalibrationStep.value = 1
        alspsCalibrationButtonText.value = "下一步：采集 Max（暂不可用）"
        return "ALS/PS 第一步完成：Min = $minValue" to details.joinToString("\n")
    }

    private fun runAlspsCalibrationStep2(): Pair<String, String> {
        val details = mutableListOf<String>()
        val maxResult = rootProxy.captureAlspsMax()
        val maxValue = rootProxy.extractResultValue(maxResult)?.toIntOrNull()
        details += "capture-max exit=${maxResult.exitCode}"
        if (maxResult.combined.isNotBlank()) details += maxResult.combined
        if (maxValue == null) {
            alspsCalibrationStep.value = -1
            alspsCalibrationButtonText.value = "重试 ALS/PS 校准（暂不可用）"
            return "ALS/PS 校准失败：未获取到有效 Max" to details.joinToString("\n")
        }

        val standard = parseAlspsStandard(alspsState.value.standard)
        if (standard != null && (maxValue <= 0 || maxValue < standard.maxStd)) {
            alspsCalibrationStep.value = -1
            alspsCalibrationButtonText.value = "重试 ALS/PS 校准（暂不可用）"
            details += "max validation failed max=$maxValue maxStd=${standard.maxStd}"
            return "ALS/PS 校准失败：Max 低于标准" to details.joinToString("\n")
        }

        val writeMax = rootProxy.writeAlspsMax(maxValue)
        details += "write-max exit=${writeMax.exitCode}"
        if (writeMax.combined.isNotBlank()) details += writeMax.combined
        if (writeMax.exitCode != 0) {
            alspsCalibrationStep.value = -1
            alspsCalibrationButtonText.value = "重试 ALS/PS 校准（暂不可用）"
            return "ALS/PS 校准失败：Max 写入失败" to details.joinToString("\n")
        }

        alspsCalibrationStep.value = 2
        alspsCalibrationButtonText.value = "下一步：写入阈值（暂不可用）"
        return "ALS/PS 第二步完成：Max = $maxValue" to details.joinToString("\n")
    }

    private fun runAlspsCalibrationStep3(): Pair<String, String> {
        val details = mutableListOf<String>()
        val minValue = alspsState.value.minValue
        val applyResult = rootProxy.applyAlspsDefaultThresholds(minValue)
        details += "apply-thresholds exit=${applyResult.exitCode}"
        if (applyResult.combined.isNotBlank()) details += applyResult.combined
        if (applyResult.exitCode != 0) {
            alspsCalibrationStep.value = -1
            alspsCalibrationButtonText.value = "重试 ALS/PS 校准（暂不可用）"
            return "ALS/PS 校准失败：阈值写入失败" to details.joinToString("\n")
        }

        sendBroadcast(Intent("agold.intent.action.ALSPS_CALIBRATE_OK"))
        details += "broadcast agold.intent.action.ALSPS_CALIBRATE_OK"
        alspsCalibrationStep.value = -1
        alspsCalibrationButtonText.value = "重试 ALS/PS 校准（暂不可用）"
        return "ALS/PS 校准完成" to details.joinToString("\n")
    }

    private fun resetAlspsCalibrationFlow(): Pair<String, String> {
        alspsCalibrationStep.value = 0
        alspsCalibrationButtonText.value = "开始 ALS/PS 校准（暂不可用）"
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

    private fun appendLog(message: String) {
        logs.value = (logs.value + "${System.currentTimeMillis()}: $message").takeLast(40)
    }

    private fun appendMultiLineLog(message: String) {
        message.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach(::appendLog)
    }
}
