package com.example.secondlock

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val PREF_FILE = "second_lock_prefs"
    const val PREF_PASSWORD = "PREF_PASSWORD"
    const val PREF_LOCK_ENABLED = "PREF_LOCK_ENABLED"
    private const val PREF_EVENT_LOG = "PREF_EVENT_LOG"
    private const val PREF_LAST_LOCK_TS = "PREF_LAST_LOCK_TS"
    private const val PREF_INTRUDER_DETECTED = "PREF_INTRUDER_DETECTED"
    private const val PREF_INTRUDER_URI = "PREF_INTRUDER_URI"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun getPassword(context: Context): String =
        prefs(context).getString(PREF_PASSWORD, "1234") ?: "1234"

    fun setPassword(context: Context, pass: String) {
        prefs(context).edit().putString(PREF_PASSWORD, pass).apply()
    }

    fun isLockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREF_LOCK_ENABLED, true)

    fun setLockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(PREF_LOCK_ENABLED, enabled).apply()
    }

    fun appendEventLog(context: Context, line: String) {
        val cur = prefs(context).getString(PREF_EVENT_LOG, "") ?: ""
        val updated = if (cur.length > 8000) cur.take(8000) else cur
        prefs(context).edit().putString(PREF_EVENT_LOG, "$line\n$updated").apply()
    }

    fun getEventLog(context: Context): String =
        prefs(context).getString(PREF_EVENT_LOG, "") ?: ""

    fun clearEventLog(context: Context) {
        prefs(context).edit().remove(PREF_EVENT_LOG).apply()
    }

    fun canLaunchLock(context: Context, minIntervalMs: Long = 2000L): Boolean {
        val last = prefs(context).getLong(PREF_LAST_LOCK_TS, 0L)
        val now = System.currentTimeMillis()
        return now - last >= minIntervalMs
    }

    fun markLockLaunched(context: Context) {
        prefs(context).edit().putLong(PREF_LAST_LOCK_TS, System.currentTimeMillis()).apply()
    }

    // --- Intruder tracking ---
    fun setIntruderDetected(context: Context, detected: Boolean) {
        prefs(context).edit().putBoolean(PREF_INTRUDER_DETECTED, detected).apply()
    }

    fun isIntruderDetected(context: Context): Boolean =
        prefs(context).getBoolean(PREF_INTRUDER_DETECTED, false)

    fun setIntruderPhotoUri(context: Context, uri: String?) {
        prefs(context).edit().putString(PREF_INTRUDER_URI, uri ?: "").apply()
    }

    fun getIntruderPhotoUri(context: Context): String? =
        prefs(context).getString(PREF_INTRUDER_URI, null)
}
