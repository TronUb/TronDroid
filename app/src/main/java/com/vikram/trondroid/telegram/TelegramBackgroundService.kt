package com.vikram.trondroid.telegram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import com.vikram.trondroid.R

class TelegramBackgroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "telegram_sessions"
        private const val NOTIFICATION_ID = 4102

        fun reconcile(context: Context) {
            val intent = Intent(context, TelegramBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }

    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        reconcileWorkers()
        if (TelegramRuntime.activeCount() + TelegramBotRuntime.activeCount() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        } else {
            updateNotification()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the Activity from Recents must not stop the service-owned workers.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        networkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        TelegramRuntime.stopAll()
        TelegramBotRuntime.stopAll()
        super.onDestroy()
    }

    private fun reconcileWorkers() {
        TelegramRuntime.reconcile(applicationContext)
        TelegramBotRuntime.reconcile(applicationContext)
    }

    private fun registerNetworkCallback() {
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                reconcileWorkers()
                updateNotification()
            }

            override fun onLost(network: Network) {
                updateNotification()
            }
        }
        networkCallback = callback
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(callback)
        }.onFailure {
            android.util.Log.w("TronDroidService", "Unable to monitor network changes", it)
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Telegram sessions", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Keeps enabled TronDroid Telegram sessions connected"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val userbotCount = TelegramRuntime.activeCount()
        val botCount = TelegramBotRuntime.activeCount()
        val details = listOfNotNull(
            if (userbotCount > 0) "$userbotCount userbot${if (userbotCount == 1) "" else "s"}" else null,
            if (botCount > 0) "$botCount bot${if (botCount == 1) "" else "s"}" else null
        ).joinToString(" · ").ifBlank { "Restoring enabled sessions" }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
            else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("TronDroid is running")
            .setContentText("$details · background connection service")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
