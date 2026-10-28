package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var speechRecognizer: SpeechRecognizer? = null
    private var pendingListen = false
    private var textToSpeech: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var pendingText: String? = null
    private var pendingRate = 1.0f
    private var pageReady = false
    private var pendingWakePhrase: String? = null

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == JarvisWakeService.ACTION_WAKE) {
                val phrase = intent.getStringExtra(JarvisWakeService.EXTRA_PHRASE).orEmpty()
                runWakePhrase(phrase)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(2, 6, 13)
        window.navigationBarColor = Color.rgb(2, 6, 13)
        initializeTts()
        val filter = IntentFilter(JarvisWakeService.ACTION_WAKE)
        if (Build.VERSION.SDK_INT >= 33) { registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED) } else { registerReceiver(wakeReceiver, filter) }
        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(2, 6, 13))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                        val audio = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }.toTypedArray()
                        if (audio.isNotEmpty() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) request.grant(audio) else request.deny()
                    }
                }
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.e("JARVIS-Web", "${message.message()} -- ${message.sourceId()}:${message.lineNumber()}")
                    return true
                }
            }
            addJavascriptInterface(NativeSpeechBridge(), "AndroidSpeech")
            addJavascriptInterface(NativeTtsBridge(), "AndroidTTS")
            addJavascriptInterface(BackgroundBridge(), "AndroidBackground")
            addJavascriptInterface(AppsBridge(), "AndroidApps")
        }
        setContentView(webView)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageReady = true
                pendingWakePhrase?.let { runWakePhrase(it); pendingWakePhrase = null }
            }
            override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                Log.e("JARVIS-Web", "WebView error $errorCode: $description ($failingUrl)")
                if (failingUrl.startsWith("file:")) runOnUiThread { Toast.makeText(this@MainActivity, "Erro ao carregar a interface JARVIS", Toast.LENGTH_LONG).show() }
            }
        }
        webView.loadUrl("file:///android_asset/index.html")
        handleWakeIntent(intent)
    }

    private inner class BackgroundBridge {
        @JavascriptInterface fun start() {
            runOnUiThread {
                textToSpeech?.stop()
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingListen = false
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                    Toast.makeText(this@MainActivity, "Autorize o microfone e toque novamente em Ativar segundo plano", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                try {
                    val i = Intent(this@MainActivity, JarvisWakeService::class.java).setAction(JarvisWakeService.ACTION_START)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                    Toast.makeText(this@MainActivity, "JARVIS em segundo plano ativado", Toast.LENGTH_SHORT).show()
                    sendToPage("window.jarvisBackgroundState(true)")
                } catch (e: Exception) {
                    Log.e("JARVIS", "Não foi possível iniciar serviço", e)
                    Toast.makeText(this@MainActivity, "Não foi possível iniciar. Abra o app e tente novamente.", Toast.LENGTH_LONG).show()
                }
            }
        }
        @JavascriptInterface fun stop() {
            runOnUiThread {
                stopService(Intent(this@MainActivity, JarvisWakeService::class.java))
                sendToPage("window.jarvisBackgroundState(false)")
                Toast.makeText(this@MainActivity, "Escuta em segundo plano desativada", Toast.LENGTH_SHORT).show()
            }
        }
        @JavascriptInterface fun resume() {
            try {
                if (JarvisWakeService.isRunning) {
                    val i = Intent(this@MainActivity, JarvisWakeService::class.java).setAction(JarvisWakeService.ACTION_RESUME)
                    startService(i)
                }
            } catch (e: Exception) { Log.w("JARVIS", "Não foi possível retomar a escuta", e) }
        }
        @JavascriptInterface fun isRunning(): Boolean = JarvisWakeService.isRunning
    }


    private inner class AppsBridge {
        @JavascriptInterface fun launch(requestedName: String): Boolean {
            val query = normalizeAppName(requestedName)
            if (query.isBlank()) return false
            val aliases = mapOf(
                "youtube" to listOf("youtube"), "whatsapp" to listOf("whatsapp"),
                "bloco de notas" to listOf("notas", "note", "keep"),
                "notas" to listOf("notas", "note", "keep"), "notepad" to listOf("notas", "note"),
                "mensagens" to listOf("mensagens", "messages", "sms"), "telefone" to listOf("telefone", "phone", "dialer"),
                "camera" to listOf("camera"), "configuracoes" to listOf("configura", "settings"),
                "chrome" to listOf("chrome"), "instagram" to listOf("instagram"), "facebook" to listOf("facebook"),
                "spotify" to listOf("spotify"), "galeria" to listOf("galeria", "photos", "gallery"),
                "arquivos" to listOf("arquivos", "files", "meus arquivos")
            )
            val terms = aliases[query] ?: listOf(query)
            return try {
                val apps = queryLaunchableApps()
                val match = apps.firstOrNull { info ->
                    val label = normalizeAppName(info.loadLabel(packageManager).toString())
                    terms.any { term -> label.contains(term) || term.contains(label) } 
                } ?: apps.firstOrNull { info -> normalizeAppName(info.activityInfo.packageName).let { pkg -> terms.any { pkg.contains(it.replace(" ", "")) } } }
                val launch = match?.activityInfo?.let { packageManager.getLaunchIntentForPackage(it.packageName) }
                if (launch != null) { launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(launch); true } else false
            } catch (e: Exception) { Log.e("JARVIS-Apps", "Falha ao abrir app: $requestedName", e); false }
        }
        @JavascriptInterface fun installedApps(): String {
            return try {
                val apps = queryLaunchableApps()
                org.json.JSONArray().apply { apps.map { it.loadLabel(packageManager).toString() }.distinct().sorted().forEach { put(it) } }.toString()
            } catch (_: Exception) { "[]" }
        }
    }

    @Suppress("DEPRECATION")
    private fun queryLaunchableApps(): List<android.content.pm.ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            packageManager.queryIntentActivities(intent, 0)
        }
    }

    private fun normalizeAppName(value: String): String = java.text.Normalizer
        .normalize(value.lowercase(Locale.forLanguageTag("pt-BR")), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").trim()

    private fun handleWakeIntent(intent: Intent?) {
        if (intent?.action == JarvisWakeService.ACTION_WAKE) {
            runWakePhrase(intent.getStringExtra(JarvisWakeService.EXTRA_PHRASE).orEmpty())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    private fun runWakePhrase(phrase: String) {
        if (!pageReady || !::webView.isInitialized) { pendingWakePhrase = phrase; return }
        val quoted = JSONObject.quote(phrase)
        sendToPage("window.jarvisWakeWord($quoted)")
    }

    private fun initializeTts() {
        // Use the Android system's configured TTS engine (for example, HayaiTTS).
        val onInit = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tts = textToSpeech
                val lang = tts?.setLanguage(Locale.forLanguageTag("pt-BR"))
                ttsReady = lang != null && lang != TextToSpeech.LANG_MISSING_DATA && lang != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { sendToPage("window.jarvisNativeTtsState('speaking')") }
                    override fun onDone(utteranceId: String?) { sendToPage("window.jarvisNativeTtsState('idle')") }
                    @Deprecated("Deprecated in Android API")
                    override fun onError(utteranceId: String?) { sendToPage("window.jarvisNativeTtsState('error')") }
                    override fun onError(utteranceId: String?, errorCode: Int) { sendToPage("window.jarvisNativeTtsState('error')") }
                })
                pendingText?.let { text -> speakNative(text, pendingRate); pendingText = null }
            } else {
                ttsReady = false
                sendToPage("window.jarvisNativeTtsState('error')")
            }
        }
        val defaultEngine = Settings.Secure.getString(contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        textToSpeech = if (!defaultEngine.isNullOrBlank()) TextToSpeech(this, onInit, defaultEngine) else TextToSpeech(this, onInit)
    }

    private inner class NativeTtsBridge {
        @JavascriptInterface fun speak(text: String, rate: Double) {
            runOnUiThread {
                pendingRate = rate.toFloat().coerceIn(0.5f, 1.8f)
                if (ttsReady) speakNative(text, pendingRate) else pendingText = text
            }
        }
        @JavascriptInterface fun stop() { runOnUiThread { textToSpeech?.stop(); sendToPage("window.jarvisNativeTtsState('idle')") } }
        @JavascriptInterface fun isReady(): Boolean = ttsReady
    }

    private fun speakNative(text: String, rate: Float) {
        val tts = textToSpeech ?: return
        tts.setSpeechRate(rate)
        val id = "jarvis-${UUID.randomUUID()}"
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (result == TextToSpeech.ERROR) sendToPage("window.jarvisNativeTtsState('error')")
    }

    private inner class NativeSpeechBridge {
        @JavascriptInterface fun startListening() {
            runOnUiThread {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingListen = true
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
                    return@runOnUiThread
                }
                beginNativeRecognition()
            }
        }
        @JavascriptInterface fun stopListening() { runOnUiThread { try { speechRecognizer?.cancel() } catch (_: Exception) {} } }
    }

    private fun beginNativeRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { sendToPage("window.jarvisNativeError('unavailable')"); return }
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { sendToPage("window.jarvisNativeState('listening')") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { sendToPage("window.jarvisNativeState('idle')") }
            override fun onError(error: Int) { sendToPage("window.jarvisNativeError('error')") }
            override fun onResults(results: Bundle?) {
                val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                sendToPage("window.jarvisNativeResult(${JSONObject.quote(phrase)})")
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun sendToPage(js: String) {
        runOnUiThread { if (::webView.isInitialized) webView.evaluateJavascript(js, null) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && pendingListen) {
            pendingListen = false
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) beginNativeRecognition() else sendToPage("window.jarvisNativeError('not-allowed')")
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(wakeReceiver) } catch (_: Exception) {}
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        try { textToSpeech?.stop(); textToSpeech?.shutdown() } catch (_: Exception) {}
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated API retained for compatibility")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
