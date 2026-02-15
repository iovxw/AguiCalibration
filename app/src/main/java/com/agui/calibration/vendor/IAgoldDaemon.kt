package com.agui.calibration.vendor

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException

interface IAgoldDaemon : IInterface {
    @Throws(RemoteException::class)
    fun CommonGetResult(callback: IAgoldDaemonCallback, type: Int)

    @Throws(RemoteException::class)
    fun SendMessageToIoctl(type: Int, flag: Int, value1: Int, value2: Int): Int

    companion object {
        const val DESCRIPTOR: String = "vendor.mediatek.hardware.agolddaemon.IAgoldDaemon"
        private const val TRANSACTION_COMMON_GET_RESULT = IBinder.FIRST_CALL_TRANSACTION
        private const val TRANSACTION_SEND_MESSAGE_TO_IOCTL = IBinder.FIRST_CALL_TRANSACTION + 2

        @JvmStatic
        fun asInterface(binder: IBinder?): IAgoldDaemon? {
            if (binder == null) {
                return null
            }
            val local = binder.queryLocalInterface(DESCRIPTOR)
            return if (local is IAgoldDaemon) {
                local
            } else {
                Proxy(binder)
            }
        }

        private class Proxy(private val remote: IBinder) : IAgoldDaemon {
            override fun asBinder(): IBinder = remote

            @Throws(RemoteException::class)
            override fun CommonGetResult(callback: IAgoldDaemonCallback, type: Int) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeStrongInterface(callback)
                    data.writeInt(type)
                    remote.transact(TRANSACTION_COMMON_GET_RESULT, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            @Throws(RemoteException::class)
            override fun SendMessageToIoctl(type: Int, flag: Int, value1: Int, value2: Int): Int {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeInt(type)
                    data.writeInt(flag)
                    data.writeInt(value1)
                    data.writeInt(value2)
                    remote.transact(TRANSACTION_SEND_MESSAGE_TO_IOCTL, data, reply, 0)
                    reply.readException()
                    return reply.readInt()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
        }
    }
}