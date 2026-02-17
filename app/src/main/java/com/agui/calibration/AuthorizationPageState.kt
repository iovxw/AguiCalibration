package com.agui.calibration

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import com.agui.calibration.adb.WirelessAdbManager
import com.agui.calibration.root.RootDaemonScriptInstaller

private const val WIRELESS_DEBUGGING_FRAGMENT = "com.android.settings.development.WirelessDebuggingFragment"
private const val SETTINGS_SOURCE_METRICS = ":settings:source_metrics"
private const val SETTINGS_SHOW_FRAGMENT = ":settings:show_fragment"
private const val SETTINGS_SHOW_FRAGMENT_AS_SUBSETTING = ":settings:show_fragment_as_subsetting"

internal class AuthorizationPageState(
    private val coordinator: AppCoordinator,
    private val requestNotificationPermissionLauncher: () -> Unit,
    private val onAuthorizationSuccess: () -> Unit
) {

    val daemonState = mutableStateOf("未连接")
    val adbAuthorizationState = mutableStateOf("请先在开发者选项中开启无线调试，然后完成配对")
    val notificationPermissionState = mutableStateOf("未检查")
    val manualDaemonCommands = mutableStateOf(RootDaemonScriptInstaller.manualCommands(coordinator.activity))
    val pairingEndpoint = mutableStateOf<WirelessAdbManager.Endpoint?>(null)
    val pairingCodeInput = mutableStateOf("")
    val pairingPortInput = mutableStateOf("")
    val showPairDialog = mutableStateOf(false)
    val isScanningPairPort = mutableStateOf(false)
    val isAuthorizingAdb = mutableStateOf(false)
    val isRestartingDaemon = mutableStateOf(false)

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            refreshNotificationPermissionState()
            return
        }
        requestNotificationPermissionLauncher()
    }

    fun refreshNotificationPermissionState() {
        notificationPermissionState.value = if (WirelessDebuggingNotificationController.areNotificationsAllowed(coordinator.activity)) {
            "已授权，可在通知里直接输入配对码"
        } else {
            "未授权；需要通知权限才能在 Wireless debugging 页面通过通知直接输入配对码"
        }
    }

    fun onAuthorizationStatusReceived(message: String, details: String, success: Boolean) {
        adbAuthorizationState.value = message
        coordinator.appendMultiLineLog(details)
        refreshBridgeState(initial = false)
        if (success) {
            onAuthorizationSuccess()
        }
    }

    fun openWirelessDebuggingAndScan() {
        if (isScanningPairPort.value || isAuthorizingAdb.value || isRestartingDaemon.value) return
        if (!WirelessDebuggingNotificationController.areNotificationsAllowed(coordinator.activity)) {
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
        runCatching { coordinator.startActivity(primaryIntent) }
            .recoverCatching { coordinator.startActivity(secondaryIntent) }
            .recoverCatching { coordinator.startActivity(fallbackIntent) }
            .onSuccess {
                adbAuthorizationState.value = "已尝试直达 Wireless debugging 页面，正在后台扫描配对端口。请在该页面点“使用配对码配对设备”，扫描到后会通过通知直接输入。"
            }
            .onFailure {
                adbAuthorizationState.value = "无法打开设置，请手动进入开发者选项 > 无线调试"
                coordinator.appendLog("open settings failed: ${it.message}")
            }
    }

    fun refreshBridgeState(initial: Boolean) {
        Thread {
            val result = coordinator.rootProxy.probe()
            coordinator.post {
                val available = result.exitCode == 0 && result.stdout.contains("OK service_found")
                daemonState.value = if (available) {
                    "root daemon 已连接，vendor HAL 可访问"
                } else {
                    "root daemon 不可用或 vendor HAL 不可访问"
                }
                if (available && (adbAuthorizationState.value.startsWith("请先") || adbAuthorizationState.value.contains("未就绪"))) {
                    adbAuthorizationState.value = "无线 ADB 已就绪，root daemon 可用"
                }
                if (initial || !available) {
                    coordinator.appendLog("probe exit=${result.exitCode}")
                    if (result.combined.isNotBlank()) {
                        coordinator.appendLog(result.combined)
                    }
                }
            }
        }.start()
    }

    fun reconnectRootDaemon() {
        if (isAuthorizingAdb.value || isRestartingDaemon.value) return
        isRestartingDaemon.value = true
        adbAuthorizationState.value = "正在重连无线调试并启动 root daemon…"
        Thread {
            val result = WirelessAdbManager.connectRootAndStartDaemon(
                context = coordinator.activity,
                packageCodePath = coordinator.packageCodePath,
                rootProxy = coordinator.rootProxy
            )
            coordinator.post {
                isRestartingDaemon.value = false
                adbAuthorizationState.value = result.message
                coordinator.appendMultiLineLog(result.details)
                refreshBridgeState(initial = false)
            }
        }.start()
    }

    fun stopRootDaemon() {
        Thread {
            val result = coordinator.rootProxy.stopDaemon()
            coordinator.post {
                coordinator.appendLog("stop-daemon exit=${result.exitCode}")
                if (result.combined.isNotBlank()) {
                    coordinator.appendLog(result.combined)
                }
                daemonState.value = if (result.exitCode == 0) {
                    "root daemon 已停止"
                } else {
                    "停止 root daemon 失败：${result.combined}"
                }
                refreshBridgeState(initial = false)
            }
        }.start()
    }

    fun startWirelessAuthorization() {
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
                context = coordinator.activity,
                pairingHost = pairingHost,
                pairingCode = pairingCode,
                pairingPort = pairingPort,
                packageCodePath = coordinator.packageCodePath,
                rootProxy = coordinator.rootProxy
            )
            coordinator.post {
                isAuthorizingAdb.value = false
                adbAuthorizationState.value = result.message
                coordinator.appendMultiLineLog(result.details)
                refreshBridgeState(initial = false)
                if (result.success) {
                    onAuthorizationSuccess()
                }
            }
        }.start()
    }

    private fun scanPairingPort(deliverNotification: Boolean, maxAttempts: Int) {
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
                endpoint = WirelessAdbManager.discoverPairingEndpoint(coordinator.activity, 3_000L)
                if (endpoint != null) {
                    break
                }
                if (attempt < maxAttempts - 1) {
                    Thread.sleep(1_500L)
                }
            }
            coordinator.post {
                isScanningPairPort.value = false
                pairingEndpoint.value = endpoint
                if (endpoint != null) {
                    pairingPortInput.value = endpoint.port.toString()
                    adbAuthorizationState.value = if (deliverNotification) {
                        "已发现配对端口：${endpoint.host}:${endpoint.port}，已发送通知，请直接在通知里输入六位授权码。"
                    } else {
                        "已发现配对端口：${endpoint.host}:${endpoint.port}，请输入六位授权码。"
                    }
                    coordinator.appendLog("pairing endpoint ${endpoint.host}:${endpoint.port}")
                    if (deliverNotification) {
                        WirelessDebuggingNotificationController.showPairingReplyNotification(
                            coordinator.applicationContext,
                            endpoint
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
                    coordinator.appendLog("pairing endpoint not found")
                }
            }
        }.start()
    }
}