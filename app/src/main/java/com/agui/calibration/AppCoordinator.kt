package com.agui.calibration

import android.content.Intent
import android.os.Handler
import com.agui.calibration.root.RootCalibrationProxy

internal class AppCoordinator(
    val activity: MainActivity,
    val rootProxy: RootCalibrationProxy,
    private val mainHandler: Handler,
    val logState: AppLogState
) {

    val applicationContext
        get() = activity.applicationContext

    val packageName: String
        get() = activity.packageName

    val packageCodePath: String
        get() = activity.packageCodePath

    fun appendLog(message: String) {
        logState.append(message)
    }

    fun appendMultiLineLog(message: String) {
        logState.appendMultiLine(message)
    }

    fun post(action: () -> Unit) {
        mainHandler.post(action)
    }

    fun postDelayed(delayMillis: Long, action: () -> Unit) {
        mainHandler.postDelayed(action, delayMillis)
    }

    fun startActivity(intent: Intent) {
        activity.startActivity(intent)
    }

    fun sendBroadcast(intent: Intent) {
        activity.sendBroadcast(intent)
    }
}