package com.example.secondlock

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import android.app.KeyguardManager
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ContentValues
import android.provider.MediaStore
import android.net.Uri
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.media.AudioManager
import android.media.AudioAttributes
import android.speech.tts.UtteranceProgressListener
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import android.view.animation.AnimationUtils
import android.annotation.SuppressLint
import androidx.activity.OnBackPressedCallback

class LockActivity : ComponentActivity(), View.OnClickListener {

    private lateinit var tvPinDots: TextView
    private lateinit var tvDate: TextView
    private val buffer = StringBuilder()
    private var tts: TextToSpeech? = null
    private var pendingCapture: Boolean = false
    private var pendingPhotoUri: Uri? = null
    private var audioManager: AudioManager? = null
    private var finishingAfterTts = false
    private var imageCapture: ImageCapture? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                captureSelfieCameraX()
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
                pendingCapture = false
            }
        }
private fun startEnterAnimations() {
        try {
            val fadeInUp = AnimationUtils.loadAnimation(this, R.anim.fade_in_up)
            val popSpin = AnimationUtils.loadAnimation(this, R.anim.pop_spin_in)
            val clock = findViewById<View>(R.id.textClock)
            val date = findViewById<View>(R.id.tvDate)
            val dots = findViewById<View>(R.id.tvPinDots)
            val card = findViewById<View>(R.id.keypadCard)
            clock.startAnimation(fadeInUp)
            date.startAnimation(fadeInUp)
            dots.startAnimation(fadeInUp)
            card.startAnimation(fadeInUp)

            val grid = findViewById<android.widget.GridLayout>(R.id.keypadGrid)
            val childCount = grid.childCount
            var delay = 50L
            for (i in 0 until childCount) {
                val v = grid.getChildAt(i)
                v.alpha = 0f
                v.postDelayed({
                    v.alpha = 1f
                    v.startAnimation(popSpin)
                }, delay)
                delay += 40L
            }
        } catch (_: Exception) { }
    }

    // Legacy intent path removed to avoid opening camera app

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ensure we can appear over keyguard and wake the screen at runtime
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
        } catch (_: Exception) {}
        enableEdgeToEdge()

        // Keep screen on and show over lock/home
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        setContentView(R.layout.activity_lock)


        // Disable system back using OnBackPressedDispatcher (gesture-safe)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // no-op to block exit
            }
        })

        tvPinDots = findViewById(R.id.tvPinDots)
        tvDate = findViewById(R.id.tvDate)
        tvDate.text = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date())

        val ids = listOf(
            R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
            R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9
        )
        ids.forEach { findViewById<Button>(it).setOnClickListener(this) }


        enterImmersive()

        // Best-effort: dismiss keyguard shortly after appearing so activity surfaces on top
        try {
            val km = getSystemService(KeyguardManager::class.java)
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        km?.requestDismissKeyguard(this, null)
                    }
                } catch (_: Exception) {}
            }, 300)
        } catch (_: Exception) {}

        // Initialize TTS and greet
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                // Lower delay: set audio attributes for prompt playback
                val attrs = AudioAttributes.Builder()
                    // Accessibility usage tends to bypass some OEM optimizations/DND
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .build()
                try { tts?.setAudioAttributes(attrs) } catch (_: Exception) {}
                // Prefer a male voice if available; else adjust pitch/rate
                try { applyMaleVoicePreference() } catch (_: Exception) {}
                // Greet
                speak("welcome back sir", utteranceId = "tts_welcome")
            }
        }

        // Finish only after 'access granted' completes
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "tts_access_granted" && finishingAfterTts) {
                    Handler(Looper.getMainLooper()).post {
                        finish()
                        overridePendingTransition(0, 0)
                    }
                }
                abandonAudioFocus()
            }
        })

        // Run entrance animations
        startEnterAnimations()
    }

    private fun enterImmersive() {
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
        // Notify service that lock activity is visible
        try {
            val intent = Intent(ACTION_LOCK_VISIBLE)
            intent.setPackage(packageName)
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    // onBackPressed intentionally not overridden; handled by OnBackPressedDispatcher above

    override fun onClick(v: View?) {
        if (v is Button) {
            if (buffer.length < 4) {
                buffer.append(v.text)
                updateDots()
            }
            if (buffer.length == 4) {
                validatePin()
            }
        }
    }

    private fun updateDots() {
        val dots = CharArray(buffer.length) { '•' }.concatToString()
        val placeholders = CharArray(4 - buffer.length) { '◦' }.concatToString()
        tvPinDots.text = dots + placeholders
    }

    private fun validatePin() {
        val saved = Prefs.getPassword(this)
        if (buffer.toString() == saved) {
            // Announce and mention intruder if any previous failure captured
            val hadIntruder = Prefs.isIntruderDetected(this)
            if (hadIntruder) {
                speak("access granted. also sir someone tried to unlock your phone i captured their photo and saved it in gallery", utteranceId = "tts_access_granted")
                Prefs.setIntruderDetected(this, false)
            } else {
                speak("access granted", utteranceId = "tts_access_granted")
            }
            // Wait for TTS complete to avoid truncation
            finishingAfterTts = true
        } else {
            Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
            speak("wrong password you may try again", utteranceId = "tts_wrong")
            // Mark intruder immediately so the next correct unlock announces it, regardless of camera success
            Prefs.setIntruderDetected(this, true)
            Prefs.appendEventLog(this, "[lock] Wrong PIN; intruder flagged")
            // Capture selfie evidence (best effort)
            tryCaptureSelfie()
            buffer.clear()
            updateDots()
        }
    }

    private fun requestAudioFocus() {
        try { audioManager?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) } catch (_: Exception) {}
    }

    private fun abandonAudioFocus() {
        try { audioManager?.abandonAudioFocus(null) } catch (_: Exception) {}
    }

    private fun speak(text: String, utteranceId: String = "lock_tts") {
        try {
            requestAudioFocus()
            // Send a short silent buffer first to wake TTS and avoid clipping
            try { tts?.playSilentUtterance(60L, TextToSpeech.QUEUE_FLUSH, "tts_silent") } catch (_: Exception) {}
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        } catch (_: Exception) {}
    }


    private fun applyMaleVoicePreference() {
        val engineVoices = tts?.voices ?: return
        val lang = Locale.getDefault().language
        // Try to find a voice that suggests male characteristics
        val preferred = engineVoices.firstOrNull { v ->
            (v.locale.language == lang) && (
                v.name.contains("male", ignoreCase = true) ||
                v.name.contains("masc", ignoreCase = true) ||
                (v.features?.any { it.contains("male", true) || it.contains("masc", true) } == true)
            )
        }
        if (preferred != null) {
            try { tts?.voice = preferred } catch (_: Exception) {}
        } else {
            // Fallback: adjust pitch/rate to sound more masculine
            try { tts?.setPitch(0.9f) } catch (_: Exception) {}
            try { tts?.setSpeechRate(0.95f) } catch (_: Exception) {}
        }
    }

    private fun tryCaptureSelfie() {
        if (pendingCapture) return
        pendingCapture = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
                return
            }
        }
        // Prefer in-app CameraX capture; fallback to Intent only if CameraX fails
        captureSelfieCameraX()
    }

    // Removed captureSelfie() intent fallback

    private fun ensureCameraX(onReady: (Boolean) -> Unit) {
        if (imageCapture != null) { onReady(true); return }
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val selector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                        .build()
                    val capture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    // Try to align rotation for correct output
                    try { capture.targetRotation = windowManager.defaultDisplay.rotation } catch (_: Exception) {}
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(this, selector, capture)
                    imageCapture = capture
                    Prefs.appendEventLog(this, "[lock] CameraX init OK")
                    onReady(true)
                } catch (e: Exception) {
                    Prefs.appendEventLog(this, "[lock] CameraX init FAILED: ${e.javaClass.simpleName}")
                    onReady(false)
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (_: Exception) {
            Prefs.appendEventLog(this, "[lock] CameraX init EXCEPTION")
            onReady(false)
        }
    }

    private fun captureSelfieCameraX() {
        ensureCameraX { ok ->
            if (!ok) { Prefs.appendEventLog(this, "[lock] CameraX not ready; skip capture"); pendingCapture = false; return@ensureCameraX }
            try {
                val name = "intruder_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SecondLock")
                    }
                }
                val output = ImageCapture.OutputFileOptions.Builder(
                    contentResolver,
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ).build()
                imageCapture?.takePicture(output, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        Prefs.setIntruderDetected(this@LockActivity, true)
                        Prefs.setIntruderPhotoUri(this@LockActivity, outputFileResults.savedUri?.toString())
                        Prefs.appendEventLog(this@LockActivity, "[lock] CameraX saved photo")
                        pendingCapture = false
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Prefs.appendEventLog(this@LockActivity, "[lock] CameraX capture ERROR: ${exception.imageCaptureError}")
                        pendingCapture = false
                    }
                })
            } catch (_: Exception) {
                Prefs.appendEventLog(this, "[lock] CameraX capture EXCEPTION")
                pendingCapture = false
            }
        }
    }

    override fun onDestroy() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val ACTION_LOCK_VISIBLE = "com.example.secondlock.LOCK_VISIBLE"
    }
}
