package com.agui.calibration

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.agui.calibration.adb.WirelessAdbManager
import com.agui.calibration.root.RootCalibrationProxy

internal object WirelessDebuggingNotificationController {
    const val ACTION_PAIRING_REPLY = "com.agui.calibration.action.PAIRING_REPLY"
    const val ACTION_AUTH_STATUS = "com.agui.calibration.action.AUTH_STATUS"
    const val EXTRA_PAIRING_PORT = "pairing_port"
    const val EXTRA_PAIRING_HOST = "pairing_host"
    const val EXTRA_MESSAGE = "message"
    const val EXTRA_DETAILS = "details"
    const val EXTRA_SUCCESS = "success"
    const val REMOTE_INPUT_RESULT_KEY = "pairing_code"

    private const val CHANNEL_ID = "wireless_adb_auth"
    private const val CHANNEL_NAME = "无线 ADB 授权"
    private const val PROMPT_NOTIFICATION_ID = 1001
    private const val STATUS_NOTIFICATION_ID = 1002

    fun areNotificationsAllowed(context: Context): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        return runtimeGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "用于在 Wireless debugging 页面直接输入配对码"
                    enableVibration(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun showPairingReplyNotification(context: Context, endpoint: WirelessAdbManager.Endpoint) {
        if (!areNotificationsAllowed(context)) return
        ensureChannel(context)

        val replyIntent = Intent(context, PairingReplyReceiver::class.java).apply {
            action = ACTION_PAIRING_REPLY
            putExtra(EXTRA_PAIRING_HOST, endpoint.host)
            putExtra(EXTRA_PAIRING_PORT, endpoint.port)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            PROMPT_NOTIFICATION_ID,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_RESULT_KEY)
            .setLabel("输入六位授权码")
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "输入授权码并授权",
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .setShowsUserInterface(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("已发现无线调试配对端口")
            .setContentText("${endpoint.host}:${endpoint.port}，直接在这里输入六位授权码")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "已发现无线调试配对端口 ${endpoint.host}:${endpoint.port}。保持停留在 Wireless debugging 的“使用配对码配对设备”页面，直接从此通知输入六位授权码即可。"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(replyAction)
            .build()

        NotificationManagerCompat.from(context).notify(PROMPT_NOTIFICATION_ID, notification)
    }

    fun showStatusNotification(context: Context, title: String, message: String, success: Boolean) {
        if (!areNotificationsAllowed(context)) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(STATUS_NOTIFICATION_ID, notification)
    }

    fun cancelPairingPrompt(context: Context) {
        NotificationManagerCompat.from(context).cancel(PROMPT_NOTIFICATION_ID)
    }

    fun broadcastAuthStatus(
        context: Context,
        message: String,
        details: String,
        success: Boolean
    ) {
        context.sendBroadcast(Intent(ACTION_AUTH_STATUS).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_MESSAGE, message)
            putExtra(EXTRA_DETAILS, details)
            putExtra(EXTRA_SUCCESS, success)
        })
    }
}

internal class PairingReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        Thread {
            val appContext = context.applicationContext
            val inputResults = RemoteInput.getResultsFromIntent(intent ?: Intent())
            val pairingCode = inputResults?.getCharSequence(
                WirelessDebuggingNotificationController.REMOTE_INPUT_RESULT_KEY
            )?.toString()?.trim().orEmpty()
            val pairingPort = intent?.getIntExtra(
                WirelessDebuggingNotificationController.EXTRA_PAIRING_PORT,
                -1
            ) ?: -1
            val result = if (pairingCode.length != 6 || pairingPort <= 0) {
                WirelessAdbManager.OperationResult(
                    success = false,
                    message = "通知里的授权码或端口无效",
                    details = "pairingCode=$pairingCode pairingPort=$pairingPort"
                )
            } else {
                val packageCodePath = appContext.packageManager
                    .getApplicationInfo(appContext.packageName, 0)
                    .sourceDir
                    WirelessAdbManager.authorizeAndStartDaemon(
                        context = appContext,
                        pairingCode = pairingCode,
                        pairingPort = pairingPort,
                        pairingHost = intent?.getStringExtra(
                            WirelessDebuggingNotificationController.EXTRA_PAIRING_HOST
                        ).orEmpty(),
                        packageCodePath = packageCodePath,
                        rootProxy = RootCalibrationProxy(appContext)
                    )
            }
            WirelessDebuggingNotificationController.cancelPairingPrompt(appContext)
            WirelessDebuggingNotificationController.showStatusNotification(
                appContext,
                if (result.success) "无线 ADB 授权成功" else "无线 ADB 授权失败",
                result.message,
                result.success
            )
            WirelessDebuggingNotificationController.broadcastAuthStatus(
                appContext,
                result.message,
                result.details,
                result.success
            )
            pendingResult.finish()
        }.start()
    }
}
