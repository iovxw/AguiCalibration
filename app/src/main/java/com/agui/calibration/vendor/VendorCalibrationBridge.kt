package com.agui.calibration.vendor

import android.os.IBinder
import android.os.RemoteException
import android.util.Log

class VendorCalibrationBridge {

    interface Callback {
        fun onResult(result: String)
    }

    companion object {
        const val TYPE_GSENSOR_CALIBRATION = 2
        private const val TAG = "VendorCalibBridge"
        private const val SERVICE_NAME = "vendor.mediatek.hardware.agolddaemon.IAgoldDaemon/default"
    }

    @Volatile
    private var daemon: IAgoldDaemon? = null

    fun isAvailable(): Boolean {
        if (daemon == null) {
            connect()
        }
        return daemon != null
    }

    fun calibrateGSensor(callback: Callback) {
        if (daemon == null) {
            connect()
        }
        val current = daemon
        if (current == null) {
            callback.onResult("bridge_unavailable")
            return
        }
        try {
            current.CommonGetResult(object : IAgoldDaemonCallback.Stub() {
                override fun onResult(result: String) {
                    Log.i(TAG, "GSensor calibration result=$result")
                    callback.onResult(result)
                }
            }, TYPE_GSENSOR_CALIBRATION)
        } catch (e: RemoteException) {
            Log.e(TAG, "CommonGetResult failed", e)
            daemon = null
            callback.onResult("remote_exception:${e.javaClass.simpleName}")
        } catch (t: Throwable) {
            Log.e(TAG, "Unexpected calibration failure", t)
            callback.onResult("bridge_error:${t.javaClass.simpleName}")
        }
    }

    private fun connect() {
        try {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, SERVICE_NAME) as? IBinder
            if (binder == null) {
                Log.w(TAG, "Service $SERVICE_NAME not found")
                daemon = null
                return
            }
            binder.linkToDeath({ daemon = null }, 0)
            daemon = IAgoldDaemon.asInterface(binder)
            Log.i(TAG, "Connected to vendor daemon")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to connect vendor daemon", t)
            daemon = null
        }
    }
}