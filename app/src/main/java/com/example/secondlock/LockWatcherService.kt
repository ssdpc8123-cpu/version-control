package com.example.secondlock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.os.Handler
import android.os.Looper
import android.hardware.display.DisplayManager
import android.view.Display
import android.os.PowerManager
import android.app.KeyguardManager
import androidx.core.app.NotificationManagerCompat
import android.provider.Settings

class LockWatcherService : Service() {
    private val channelId = "secondlock_watcher"
    private val notifId = 2001

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            when (action) {
                LockActivity.ACTION_LOCK_VISIBLE -> {
                    lastLockVisibleAt = System.currentTimeMillis()
                    Prefs.appendEventLog(context, "[svc] LockActivity visible ack")
                    // Do NOT clear isLaunching here; wait for safety timer to avoid double popups
                }
                Intent.ACTION_USER_PRESENT, Intent.ACTION_USER_UNLOCKED -> {
                    Prefs.appendEventLog(context, "[svc] Unlock detected (broadcast)")
                    // Only arm here; actual launch happens when polling sees keyguard cleared
                    armedForUnlock = true
                }
                Intent.ACTION_SCREEN_ON -> Prefs.appendEventLog(context, "[svc] Screen ON detected (broadcast)")
                Intent.ACTION_SCREEN_OFF -> Prefs.appendEventLog(context, "[svc] Screen OFF detected (broadcast)")
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            val state = display.state
            if (state != lastDisplayState) {
                lastDisplayState = state
                when (state) {
                    Display.STATE_ON, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> {
                        Prefs.appendEventLog(this@LockWatcherService, "[svc] Screen ON detected (display)")
                        armedForUnlock = true
                    }
                    Display.STATE_OFF -> {
                        Prefs.appendEventLog(this@LockWatcherService, "[svc] Screen OFF detected (display)")
                        armedForUnlock = false
                    }
                    else -> {}
                }
            }
        }
    }

    // Polling-based fallback for screen state (class-level)
    private val pollHandler = Handler(Looper.getMainLooper())
    private var lastInteractive: Boolean? = null
    private var armedForUnlock: Boolean = false
    private var lastHeartbeatBucket: Long = -1L
    private var lastDisplayState: Int = -1
    private var lastLaunchRequestedAt: Long = 0L
    private var lastLockVisibleAt: Long = 0L
    private var isLaunching: Boolean = false
    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val nowInteractive = pm.isInteractive
                val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                val unlocked = !km.isKeyguardLocked
                if (lastInteractive == null) {
                    lastInteractive = nowInteractive
                } else if (lastInteractive != nowInteractive) {
                    lastInteractive = nowInteractive
                    if (nowInteractive) {
                        Prefs.appendEventLog(this@LockWatcherService, "[svc] Screen ON detected (poll)")
                        armedForUnlock = true
                    } else {
                        Prefs.appendEventLog(this@LockWatcherService, "[svc] Screen OFF detected (poll)")
                    }
                }

                // If we are armed and device is interactive and unlocked, trigger lock
                val enabled = Prefs.isLockEnabled(this@LockWatcherService)
                if (armedForUnlock && nowInteractive && unlocked && enabled) {
                    armedForUnlock = false
                    scheduleLockLaunch()
                } else if (armedForUnlock && nowInteractive && !unlocked) {
                    Prefs.appendEventLog(this@LockWatcherService, "[svc] Waiting keyguard to clear…")
                }
                // Heartbeat every ~10s
                val hb = System.currentTimeMillis() / 10000L
                if (hb != lastHeartbeatBucket) {
                    lastHeartbeatBucket = hb
                    Prefs.appendEventLog(this@LockWatcherService, "[svc] Heartbeat (interactive=$nowInteractive, unlocked=$unlocked)")
                }
            } catch (_: Exception) { }
            // schedule next check
            pollHandler.postDelayed(this, 500)
        }
    }

    private fun scheduleLockLaunch() {
        // Debounce to avoid double pop-ups from multiple triggers
        if (!Prefs.canLaunchLock(this, 3000L)) {
            Prefs.appendEventLog(this, "[svc] Launch suppressed (debounce)")
            return
        }
        if (isLaunching) {
            Prefs.appendEventLog(this, "[svc] Launch suppressed (already launching)")
            return
        }
        isLaunching = true
        Prefs.markLockLaunched(this)
        val h = Handler(Looper.getMainLooper())
        fun attemptLaunch(tag: String) {
            try {
                val notifEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
                val overlayPerm = try { Settings.canDrawOverlays(this) } catch (_: Exception) { false }
                val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                val keyguardLocked = km.isKeyguardLocked
                Prefs.appendEventLog(this, "[svc] Launch attempt ($tag) perms: notif=$notifEnabled, overlay=$overlayPerm, keyguardLocked=$keyguardLocked")
                // Wake the screen reliably on OEM ROMs
                try {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    @Suppress("DEPRECATION")
                    pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "secondlock:wakeup"
                    ).apply { acquire(2000) }
                } catch (_: Exception) { }
                val i = Intent(this, LockActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                lastLaunchRequestedAt = System.currentTimeMillis()
                startActivity(i)
                // Single retry only if activity did not become visible
                Handler(Looper.getMainLooper()).postDelayed({
                    val ackAfterLaunch = lastLockVisibleAt >= lastLaunchRequestedAt
                    if (!ackAfterLaunch) {
                        try {
                            startActivity(i)
                            Prefs.appendEventLog(this, "[svc] Retry relaunch (+300ms)")
                        } catch (_: Exception) {}
                    }
                }, 300)
                // Safety: clear launching guard after a short window to avoid being stuck
                Handler(Looper.getMainLooper()).postDelayed({
                    isLaunching = false
                }, 4000)
            } catch (e: Exception) {
                Prefs.appendEventLog(this, "[svc] Launch failed ($tag)")
                throw e
            }
        }
        // Launch immediately; one conditional retry will follow
        try {
            attemptLaunch("t0 now")
            Prefs.appendEventLog(this, "[svc] Launch issued (t0 now)")
        } catch (_: Exception) {
            Handler(Looper.getMainLooper()).postDelayed({
                try { attemptLaunch("t1 +300ms"); Prefs.appendEventLog(this, "[svc] Launch issued (t1)") } catch (_: Exception) {}
            }, 300)
        }
    }

    private fun triggerFallbackIfNotVisible(timeoutMs: Long = 1200L) {
        val now = System.currentTimeMillis()
        val sinceLaunch = now - lastLaunchRequestedAt
        // If we have NOT seen a visible ack after this launch timestamp, and timeout elapsed -> fallback
        val ackAfterLaunch = lastLockVisibleAt >= lastLaunchRequestedAt
        if (sinceLaunch >= timeoutMs && !ackAfterLaunch) {
            Prefs.appendEventLog(this, "[svc] Fallback disabled; not showing overlay or notification")
        } else {
            Prefs.appendEventLog(this, "[svc] Launch acknowledged visible; no fallback")
        }
    }
    override fun onCreate() {
        super.onCreate()
        // Foreground service with ongoing notification (typed as dataSync in manifest)
        createWatcherChannel()
        startForeground(notifId, buildOngoingNotification())
        // Pre-warm TTS for snappier playback when LockActivity speaks
        try {
            TtsManager.init(applicationContext)
            TtsManager.prewarm()
            Prefs.appendEventLog(this, "[svc] TTS prewarmed")
        } catch (_: Exception) {}
        // Ensure channels exist at startup for fallback (do not post notifications here)
        try { val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { if (nm.getNotificationChannel("secondlock_alert") == null) { nm.createNotificationChannel(NotificationChannel("secondlock_alert", "Second Lock", NotificationManager.IMPORTANCE_HIGH)) } } } catch (_: Exception) {}
        logBootState("onCreate")

        // Register for unlock-related broadcasts
        val f = IntentFilter().apply {
            addAction(LockActivity.ACTION_LOCK_VISIBLE)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_USER_UNLOCKED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, f)
        }
        Prefs.appendEventLog(this, "[svc] Receiver registered")

        // Register display listener to reliably detect screen state changes
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        Prefs.appendEventLog(this, "[svc] DisplayListener registered")

        // Start polling fallback
        pollHandler.postDelayed(pollRunnable, 1000)
        Prefs.appendEventLog(this, "[svc] Polling started (500ms)")
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {}
        try { pollHandler.removeCallbacks(pollRunnable) } catch (_: Exception) {}
        // Keep the service lightweight; foreground will be torn down by the system when destroyed.
        Prefs.appendEventLog(this, "[svc] onDestroy() called")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensure persistence if the process is reclaimed; does not auto-start at boot.
        Prefs.appendEventLog(this, "[svc] onStartCommand(flags=$flags, startId=$startId)")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Best-effort restart if the task is swiped away; acceptable for local testing.
        val service = Intent(applicationContext, LockWatcherService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(service)
        } else {
            applicationContext.startService(service)
        }
        super.onTaskRemoved(rootIntent)
        Prefs.appendEventLog(this, "[svc] onTaskRemoved(): requested restart")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createWatcherChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Second Lock Watcher", NotificationManager.IMPORTANCE_LOW)
                )
                Prefs.appendEventLog(this, "[svc] Created channel '$channelId'")
            }
        }
    }

    private fun buildOngoingNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Second Lock active")
            .setContentText("Waiting for device unlock…")
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pi)
            .build()
    }

    private fun showFullScreenNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelIdFs = "secondlock_alert"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(channelIdFs) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelIdFs, "Second Lock", NotificationManager.IMPORTANCE_HIGH)
                )
                Prefs.appendEventLog(this, "[svc] Created channel '$channelIdFs'")
            }
        }
        val pi = PendingIntent.getActivity(
            this,
            1001,
            Intent(this, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val n = NotificationCompat.Builder(this, channelIdFs)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Tap to open Second Lock")
            .setContentText("Security overlay ready")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()
        nm.notify(1002, n)
        Prefs.appendEventLog(this, "[svc] Posted full-screen notification")
    }

    private fun logBootState(tag: String) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val notifEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val pmgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val watcherCh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) pmgr.getNotificationChannel(channelId) else null
        val alertCh = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) pmgr.getNotificationChannel("secondlock_alert") else null
        val overlayPerm = try { Settings.canDrawOverlays(this) } catch (_: Exception) { false }
        val ignoring = pm.isIgnoringBatteryOptimizations(packageName)
        val line = buildString {
            append("[svc] $tag: sdk=${Build.VERSION.SDK_INT}, manuf=${Build.MANUFACTURER}, model=${Build.MODEL}")
            append(", notifEnabled=$notifEnabled")
            append(", watcherCh=${watcherCh?.importance ?: -1}")
            append(", alertCh=${alertCh?.importance ?: -1}")
            append(", overlayPerm=$overlayPerm")
            append(", ignoreBattOpt=$ignoring")
            append(", interactive=${pm.isInteractive}, keyguardLocked=${km.isKeyguardLocked}")
        }
        Prefs.appendEventLog(this, line)
    }
}
