package com.example.secondlock

import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.os.Build
import android.content.ComponentName
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.media.AudioAttributes
import android.media.AudioManager

class MainActivity : ComponentActivity() {
    private lateinit var tvStatus: TextView
    private var tts: TextToSpeech? = null
    private var hasAudioFocus = false
    // Runtime request for notifications on Android 13+ to allow the persistent watcher notification
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Toast.makeText(this, if (granted) "Notifications allowed" else "Notifications denied", Toast.LENGTH_SHORT).show()
            updateStatusBanner()
            // If enabled and permission just granted, ensure service is running
            if (Prefs.isLockEnabled(this)) toggleWatcherService(true)
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            } else {
                speak("Camera permission granted")
            }
            updateStatusBanner()
        }

    private fun toggleWatcherService(enable: Boolean) {
        val service = Intent(this, LockWatcherService::class.java)
        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, service)
            } else {
                startService(service)
            }
        } else {
            stopService(service)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        val etNew: EditText = findViewById(R.id.etNew)
        val etConfirm: EditText = findViewById(R.id.etConfirm)
        val btnSave: Button = findViewById(R.id.btnSave)
        val switchEnable: Switch = findViewById(R.id.switchEnable)
        val btnCheck: ImageButton = findViewById(R.id.btnCheck)
        tvStatus = findViewById(R.id.tvStatus)

        // Init TTS
        try {
            tts = TextToSpeech(this) { _ ->
                try {
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    tts?.setPitch(0.95f)
                    tts?.setSpeechRate(1.0f)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Limit to 4 characters
        val filters = arrayOf(InputFilter.LengthFilter(4))
        etNew.filters = filters
        etConfirm.filters = filters

        // Pre-fill current password to guide user (optional: keep empty for security)
        // etNew.setText("")
        // etConfirm.setText("")

        // Load saved state
        switchEnable.isChecked = Prefs.isLockEnabled(this)

        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setLockEnabled(this, isChecked)
            Toast.makeText(
                this,
                if (isChecked) "Second Lock Enabled" else "Second Lock Disabled",
                Toast.LENGTH_SHORT
            ).show()
            updateStatusBanner()
            toggleWatcherService(isChecked)
        }

        btnSave.setOnClickListener {
            val newPass = etNew.text?.toString()?.trim() ?: ""
            val confirmPass = etConfirm.text?.toString()?.trim() ?: ""

            if (newPass.length != 4 || confirmPass.length != 4) {
                Toast.makeText(this, "Password must be exactly 4 digits", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (newPass != confirmPass) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!newPass.all { it.isDigit() }) {
                Toast.makeText(this, "Password must contain only digits", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Prefs.setPassword(this, newPass)
            Toast.makeText(this, "Password saved", Toast.LENGTH_SHORT).show()
            etNew.text?.clear()
            etConfirm.text?.clear()
        }

        // Optional: clean confirm when new changes
        etNew.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (etConfirm.text?.isNotEmpty() == true) etConfirm.text?.clear()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Check requirements: battery optimization whitelist and receiver state
        btnCheck.setOnClickListener {
            var missing = false

            // Notifications (Android 13+ requires runtime permission + channel enabled)
            var notifEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasPerm = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (!hasPerm) {
                    missing = true
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (!notifEnabled) {
                missing = true
                try {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        putExtra("app_package", packageName)
                        putExtra("app_uid", applicationInfo.uid)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(details)
                }
            }

            // Camera permission (for intruder selfie)
            val hasCamera = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (!hasCamera) {
                missing = true
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }

            if (!missing) {
                speak("All permissions are granted")
                Toast.makeText(this, "All permissions are granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Guiding to grant required permissions…", Toast.LENGTH_SHORT).show()
            }
            updateStatusBanner()
        }

        // Initial banner state
        updateStatusBanner()
        // Ensure watcher service reflects saved state
        toggleWatcherService(Prefs.isLockEnabled(this))
    }

    override fun onResume() {
        super.onResume()
        updateStatusBanner()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { if (hasAudioFocus) (getSystemService(AUDIO_SERVICE) as AudioManager).abandonAudioFocus(null) } catch (_: Exception) {}
        try { tts?.shutdown() } catch (_: Exception) {}
    }

    private fun updateStatusBanner() {
        val lockEnabled = Prefs.isLockEnabled(this)
        val notifOk = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val text = buildString {
            append(if (lockEnabled) "Lock: Enabled" else "Lock: Disabled")
            append("  |  ")
            append(if (notifOk) "Notif: OK" else "Notif: Missing")
        }
        tvStatus.text = text
    }

    private fun speak(text: String) {
        try {
            if (!hasAudioFocus) {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                hasAudioFocus = am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "main_say")
        } catch (_: Exception) {}
    }

    private fun openAutostartSettings(): Boolean {
        // Try a list of known MIUI autostart/permission components
        val candidates = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity"),
            ComponentName("com.miui.securitycenter", "com.miui.appmanager.AppManagerActivity"),
            ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity")
        )
        for (cn in candidates) {
            try {
                val intent = Intent().apply { component = cn }
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    return true
                }
            } catch (_: Exception) { /* try next */ }
        }
        return false
    }
}
