package com.agui.calibration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.agui.calibration.root.RootCalibrationProxy

class CalibrationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appContext = context.applicationContext
        Thread {
            val proxy = RootCalibrationProxy(appContext)
            when (action) {
                Intent.ACTION_BOOT_COMPLETED -> handleBootCompleted(proxy)
                ACTION_NOTIFY_GET_PROXIMI_PS -> handleQueryBroadcast(appContext, proxy)
            }
        }.start()
    }

    private fun handleBootCompleted(proxy: RootCalibrationProxy) {
        val result = proxy.getAlspsStatus()
        Log.i(TAG, "boot alsps status=${result.combined}")
    }

    private fun handleQueryBroadcast(context: Context, proxy: RootCalibrationProxy) {
        val result = proxy.queryProximiPsName()
        val payload = proxy.extractResultValue(result) ?: "unavailable"
        context.sendBroadcast(
            Intent(ACTION_GET_PROXIMI_PS)
                .setPackage(context.packageName)
                .putExtra(EXTRA_GET_PROXIMI_PS_NAME, payload)
        )
        Log.i(TAG, "notify_get_proximi_ps_acton exit=${result.exitCode} payload=$payload raw=${result.combined}")
    }

    companion object {
        const val ACTION_NOTIFY_GET_PROXIMI_PS = "notify_get_proximi_ps_acton"
        const val ACTION_GET_PROXIMI_PS = "get_proximi_ps_acton"
        const val EXTRA_GET_PROXIMI_PS_NAME = "get_proximi_ps_name"
        private const val TAG = "CalibrationReceiver"
    }
}