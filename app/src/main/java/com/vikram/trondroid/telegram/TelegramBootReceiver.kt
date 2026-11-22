package com.vikram.trondroid.telegram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Restores opted-in Telegram sessions after a device reboot or app update.
 * Android may still defer or block background starts under system/OEM policy.
 */
class TelegramBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val preferences = context.getSharedPreferences("trondroid.settings", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("auto_start_sessions", true)) return

        runCatching {
            val serviceIntent = Intent(context, TelegramBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }.onFailure {
            android.util.Log.w("TronDroidBoot", "Unable to restore Telegram background service", it)
        }
    }
}
