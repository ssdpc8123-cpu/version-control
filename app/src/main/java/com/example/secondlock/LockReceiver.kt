package com.example.secondlock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat

class LockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            Intent.ACTION_USER_PRESENT -> {
                ensureWatcherService(context)
            }
            Intent.ACTION_USER_UNLOCKED -> {
                ensureWatcherService(context)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                // Nothing to do immediately; relying on USER_PRESENT after boot
            }
        }
    }

    private fun ensureWatcherService(context: Context) {
        val service = Intent(context, LockWatcherService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service)
        } else {
            context.startService(service)
        }
    }
}
