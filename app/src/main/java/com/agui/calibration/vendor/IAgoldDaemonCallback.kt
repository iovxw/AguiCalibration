package com.agui.calibration.vendor

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException

interface IAgoldDaemonCallback : IInterface {
    @Throws(RemoteException::class)
    fun onResult(result: String)

    abstract class Stub : Binder(), IAgoldDaemonCallback {
        init {
            attachInterface(this, DESCRIPTOR)
            try {
                Binder::class.java.getDeclaredMethod("markVintfStability").invoke(this)
            } catch (_: Throwable) {
            }
        }

        override fun asBinder(): IBinder = this

        @Throws(RemoteException::class)
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    true
                }

                TRANSACTION_ON_RESULT -> {
                    data.enforceInterface(DESCRIPTOR)
                    onResult(data.readString().orEmpty())
                    true
                }

                TRANSACTION_GET_INTERFACE_VERSION -> {
                    data.enforceInterface(DESCRIPTOR)
                    reply?.writeNoException()
                    reply?.writeInt(VERSION)
                    true
                }

                TRANSACTION_GET_INTERFACE_HASH -> {
                    data.enforceInterface(DESCRIPTOR)
                    reply?.writeNoException()
                    reply?.writeString(HASH)
                    true
                }

                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }

    companion object {
        const val DESCRIPTOR: String = "vendor.mediatek.hardware.agolddaemon.IAgoldDaemonCallback"
        const val VERSION: Int = 2
        const val HASH: String = "23a82e28a54589e003c20288e6f42f863a2d3da6"

        private const val TRANSACTION_ON_RESULT = IBinder.FIRST_CALL_TRANSACTION
        private const val TRANSACTION_GET_INTERFACE_HASH = 16777214
        private const val TRANSACTION_GET_INTERFACE_VERSION = 16777215
    }
}