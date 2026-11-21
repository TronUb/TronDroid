package com.vikram.trondroid.telegram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TelegramRuntime.reconcile(applicationContext)
        TelegramBotRuntime.reconcile(applicationContext)
        if (TelegramRuntime.activeCount() + TelegramBotRuntime.activeCount() == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        } else {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        TelegramRuntime.stopAll()
        TelegramBotRuntime.stopAll()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Telegram sessions", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Keeps enabled TronDroid userbot sessions connected"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val userbotCount = TelegramRuntime.activeCount()
        val botCount = TelegramBotRuntime.activeCount()
        val count = userbotCount + botCount
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("TronDroid is running")
            .setContentText(listOfNotNull(
                if (userbotCount > 0) "$userbotCount userbot${if (userbotCount == 1) "" else "s"}" else null,
                if (botCount > 0) "$botCount bot${if (botCount == 1) "" else "s"}" else null
            ).joinToString(" · ") + " running in background")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
