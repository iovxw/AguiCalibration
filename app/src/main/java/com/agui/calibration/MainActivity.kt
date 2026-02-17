package com.agui.calibration

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
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
import com.agui.calibration.root.RootDaemonScriptInstaller
import com.agui.calibration.root.RootCalibrationProxy
import com.agui.calibration.ui.theme.AguiCalibrationTheme

class MainActivity : ComponentActivity(), SensorEventListener {

    private val selectedTab = mutableStateOf(CalibrationTab.Authorization)
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var logState: AppLogState
    private lateinit var coordinator: AppCoordinator
    private lateinit var authorizationPageState: AuthorizationPageState
    private lateinit var gSensorPageState: GSensorPageState
    private lateinit var gyroscopePageState: GyroscopePageState
    private lateinit var alspsPageState: AlspsPageState
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (::authorizationPageState.isInitialized) {
            authorizationPageState.refreshNotificationPermissionState()
        }
    }

    private val alspsAutoRefresh = object : Runnable {
        override fun run() {
            alspsPageState.refreshStatus(true)
            mainHandler.postDelayed(this, 1_500L)
        }
    }

    private val proximiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val value = intent?.getStringExtra(CalibrationReceiver.EXTRA_GET_PROXIMI_PS_NAME)
                ?: "unavailable"
            alspsPageState.onProximityBroadcastResult(value)
        }
    }

    private val authStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val message = intent?.getStringExtra(WirelessDebuggingNotificationController.EXTRA_MESSAGE)
                ?: return
            val details = intent.getStringExtra(WirelessDebuggingNotificationController.EXTRA_DETAILS).orEmpty()
            val success = intent.getBooleanExtra(WirelessDebuggingNotificationController.EXTRA_SUCCESS, false)
            authorizationPageState.onAuthorizationStatusReceived(message, details, success)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        RootDaemonScriptInstaller.ensureInstalled(this)
        val rootProxy = RootCalibrationProxy(this)
        logState = AppLogState()
        coordinator = AppCoordinator(this, rootProxy, mainHandler, logState)
        authorizationPageState = AuthorizationPageState(
            coordinator = coordinator,
            requestNotificationPermissionLauncher = {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            onAuthorizationSuccess = { alspsPageState.refreshStatus(true) }
        )
        gSensorPageState = GSensorPageState(coordinator)
        gyroscopePageState = GyroscopePageState(coordinator)
        alspsPageState = AlspsPageState(coordinator)
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

        authorizationPageState.refreshBridgeState(initial = true)
        authorizationPageState.refreshNotificationPermissionState()

        setContent {
            AguiCalibrationTheme {
                CalibrationApp(
                    selectedTab = selectedTab,
                    authorizationPageState = authorizationPageState,
                    gSensorPageState = gSensorPageState,
                    gyroscopePageState = gyroscopePageState,
                    alspsPageState = alspsPageState,
                    logState = logState,
                    onTabSelected = { selectedTab.value = it }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroscope?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        authorizationPageState.refreshNotificationPermissionState()
        authorizationPageState.refreshBridgeState(initial = false)
        alspsPageState.refreshStatus(true)
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
                gSensorPageState.onSensorChanged(event.values)
            }

            Sensor.TYPE_GYROSCOPE -> {
                gyroscopePageState.onSensorChanged(event.values)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
