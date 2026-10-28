package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/** Foreground service that listens for the wake word, then waits for the user's command. */
class JarvisWakeService : Service() {
    companion object {
        const val ACTION_START = "com.jarvis.assistant.START_WAKE"
        const val ACTION_STOP = "com.jarvis.assistant.STOP_WAKE"
        const val ACTION_WAKE = "com.jarvis.assistant.WAKE_PHRASE"
        const val ACTION_RESUME = "com.jarvis.assistant.RESUME_WAKE"
        const val EXTRA_PHRASE = "wake_phrase"
        private const val CHANNEL_ID = "jarvis_wake_channel"
        private const val NOTIFICATION_ID = 7301
        @Volatile var isRunning: Boolean = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var pausedForReply = false
    private var awaitingCommand = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var restartPending = false
    private var lastWakeAt = 0L
    private var originalSystemVolume: Int? = null
    private val restoreSystemVolume = Runnable { restoreRecognitionCueVolume() }

    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_RESUME -> {
                pausedForReply = false
                awaitingCommand = false
                scheduleRestart(300)
            }
            else -> {
                try {
                    startForeground(NOTIFICATION_ID, buildNotification())
                    val pm = getSystemService(POWER_SERVICE) as PowerManager
                    if (wakeLock == null) wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JARVIS:WakeWord").apply { setReferenceCounted(false); acquire() }
                    isRunning = true
                    startRecognition()
                } catch (e: Exception) {
                    Log.e("JARVIS-Wake", "Could not start microphone service", e)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "JARVIS em segundo plano", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Escuta a palavra de ativação JARVIS"
                setShowBadge(false)
            })
        }
    }

    private fun buildNotification(): Notification {
        val open = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("J.A.R.V.I.S. ativo")
            .setContentText("Diga ‘Jarvis’ e depois seu comando")
            .setContentIntent(pi).setOngoing(true).build()
    }

    private fun startRecognition() {
        if (!isRunning || pausedForReply || listening || restartPending) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { scheduleRestart(4000); return }
        try {
            // Reuse one SpeechRecognizer instance. Destroying and recreating it after every
            // phrase caused repeated audio-focus/microphone hand-offs on many Android phones.
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) { listening = true }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { listening = false }
                    override fun onError(error: Int) {
                        listening = false
                        val delay = when (error) {
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 900L
                            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> 2200L
                            SpeechRecognizer.ERROR_CLIENT -> 650L
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> 300L
                            else -> 450L
                        }
                        scheduleRestart(delay)
                    }
                    override fun onResults(results: android.os.Bundle?) {
                        listening = false
                        val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val heard = if (awaitingCommand) alternatives.firstOrNull().orEmpty() else
                            alternatives.firstOrNull { findWakeWord(it) != null } ?: alternatives.firstOrNull().orEmpty()
                        Log.d("JARVIS-Wake", "Heard: $heard; alternatives=$alternatives; awaitingCommand=$awaitingCommand")
                        if (awaitingCommand) {
                            awaitingCommand = false
                            if (heard.isNotBlank()) dispatchCommand(heard.trim()) else scheduleRestart(300)
                            return
                        }
                        val wakeRange = findWakeWord(heard)
                        if (wakeRange != null) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastWakeAt > 1800L) {
                                lastWakeAt = now
                                val command = heard.substring((wakeRange.last + 1).coerceAtMost(heard.length))
                                    .trim(' ', ',', '.', '!', '?', ':', ';', '-', '—')
                                if (command.isNotBlank()) dispatchCommand(command)
                                else {
                                    dispatchCommand("")
                                    awaitingCommand = true
                                    scheduleRestart(300)
                                }
                            } else scheduleRestart(300)
                        } else scheduleRestart(300)
                    }
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
            }
            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (awaitingCommand) 18000L else 60000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (awaitingCommand) 9000L else 30000L)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            listening = true
            suppressRecognitionCueBriefly()
            recognizer?.startListening(speechIntent)
        } catch (e: Exception) {
            listening = false
            Log.e("JARVIS-Wake", "Recognition start failed", e)
            try { recognizer?.cancel() } catch (_: Exception) {}
            scheduleRestart(700)
        }
    }

    private fun dispatchCommand(command: String) {
        // Pause microphone capture while JARVIS processes/speaks, preventing it from
        // hearing its own voice and repeatedly restarting the recognizer.
        pausedForReply = command.isNotBlank()
        if (pausedForReply) {
            restartPending = false
            handler.removeCallbacksAndMessages(null)
            try { recognizer?.cancel() } catch (_: Exception) {}
            listening = false
        }
        awaitingCommand = false
        val wake = Intent(ACTION_WAKE).setPackage(packageName).putExtra(EXTRA_PHRASE, command)
        sendBroadcast(wake)
        scheduleRestart(300)
    }

    private fun normalize(value: String): String = java.text.Normalizer
        .normalize(value.lowercase(Locale.forLanguageTag("pt-BR")), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")

    /** Accepts common Portuguese speech-to-text spellings of the wake word. */
    private fun findWakeWord(transcript: String): IntRange? {
        val tokenRegex = Regex("[\\p{L}]+")
        for (match in tokenRegex.findAll(transcript)) {
            val token = normalize(match.value)
            // ASR often writes Jarvis as Jarves/Jervis/Jarvys; edit distance <= 1 handles these.
            if (token in setOf("jarvis", "jarves", "jervis", "jarvys", "jarviss", "jarvix") ||
                (token.length in 5..7 && editDistanceAtMostOne(token, "jarvis"))) {
                return match.range
            }
        }
        // A few recognizers split the name into two words, e.g. "jar vis".
        val split = Regex("(?i)\\bjar\\s+vis\\b").find(transcript)
        return split?.range
    }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++ } else {
                edits++
                if (edits > 1) return false
                if (a.length > b.length) i++ else if (b.length > a.length) j++ else { i++; j++ }
            }
        }
        if (i < a.length || j < b.length) edits++
        return edits <= 1
    }

    /**
     * Android/Google speech services may play a short cue whenever recognition starts.
     * Temporarily mute only the system-sound stream, then restore its previous level.
     * Some recognition engines ignore this workaround; the app never permanently changes volume.
     */
    private fun suppressRecognitionCueBriefly() {
        try {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            if (originalSystemVolume == null) {
                val current = audio.getStreamVolume(AudioManager.STREAM_SYSTEM)
                if (current > 0) {
                    originalSystemVolume = current
                    audio.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
                }
            }
            handler.removeCallbacks(restoreSystemVolume)
            handler.postDelayed(restoreSystemVolume, 900L)
        } catch (e: Exception) {
            Log.d("JARVIS-Wake", "Recognition cue mute unavailable", e)
        }
    }

    private fun restoreRecognitionCueVolume() {
        val original = originalSystemVolume ?: return
        originalSystemVolume = null
        try {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            // Restore only if it is still muted, so a user volume change is not overwritten.
            if (audio.getStreamVolume(AudioManager.STREAM_SYSTEM) == 0) {
                audio.setStreamVolume(
                    AudioManager.STREAM_SYSTEM,
                    original.coerceAtMost(audio.getStreamMaxVolume(AudioManager.STREAM_SYSTEM)),
                    0
                )
            }
        } catch (e: Exception) {
            Log.d("JARVIS-Wake", "Could not restore system sound volume", e)
        }
    }

    private fun scheduleRestart(delay: Long) {
        if (!isRunning || pausedForReply || restartPending) return
        restartPending = true
        handler.postDelayed({ restartPending = false; listening = false; startRecognition() }, delay)
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        restoreRecognitionCueVolume()
        try { recognizer?.cancel(); recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
