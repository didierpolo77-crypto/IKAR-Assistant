package com.ikar.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.Manifest
import androidx.core.content.ContextCompat
import java.util.Locale
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.room.Room
import com.ikar.assistant.ai.OfflineChatEngine
import com.ikar.assistant.data.MemoryDatabase
import com.ikar.assistant.data.MemoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * User-started floating chat bubble. The user must explicitly grant overlay
 * access and start/stop the service from IKAR; no screen capture or monitoring.
 */
class FloatingAssistantService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private lateinit var params: WindowManager.LayoutParams
    private val channelId = "ikar_floating_assistant"
    private var speechOutput: TextToSpeech? = null
    private var speechOutputReady = false
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        speechOutput = TextToSpeech(this) { status ->
            speechOutputReady = status == TextToSpeech.SUCCESS &&
                (speechOutput?.setLanguage(Locale.FRANCE) ?: TextToSpeech.LANG_MISSING_DATA) >= TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("IKAR est disponible")
            .setContentText("La bulle de discussion est active. Appuie dessus pour discuter.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2401, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2401, notification)
        }
        if (bubble == null && panel == null) showBubble()
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Bulle IKAR", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showBubble() {
        val view = TextView(this).apply {
            text = "IKAR"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(28, 91, 170))
                setStroke(dp(2), Color.WHITE)
            }
            elevation = dp(8).toFloat()
            setOnClickListener { showPanel() }
        }
        params = WindowManager.LayoutParams(
            dp(62), dp(62),
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(220)
        }
        view.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        startX = params.x; startY = params.y; moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).roundToInt()
                        val dy = (event.rawY - downY).roundToInt()
                        if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(5)) moved = true
                        params.x = (startX - dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        windowManager.updateViewLayout(v, params)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) v.performClick()
                        return true
                    }
                }
                return false
            }
        })
        bubble = view
        windowManager.addView(view, params)
    }

    private fun showPanel() {
        if (panel != null) return
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(17, 29, 49))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(67, 111, 171))
            }
            elevation = dp(12).toFloat()
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply { text = "IKAR • Discussion"; textSize = 16f; setTextColor(Color.WHITE) }
        val close = Button(this).apply { text = "—"; setOnClickListener { hidePanel(); showBubble() } }
        header.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(48), dp(44)))
        root.addView(header)

        val transcript = TextView(this).apply {
            text = "Bonjour ! Je suis disponible ici, même lorsque tu utilises une autre application.\n"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(transcript) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val input = EditText(this).apply {
            hint = "Écris à IKAR…"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            setSingleLine(false)
            maxLines = 3
        }
        root.addView(input, LinearLayout.LayoutParams(-1, dp(54)))
        val voice = Button(this).apply { text = "🎙 Parler à IKAR" }
        root.addView(voice, LinearLayout.LayoutParams(-1, dp(48)))
        voice.setOnClickListener { startVoiceRecognition(input, transcript, scroll) }

        val send = Button(this).apply { text = "Envoyer" }
        root.addView(send, LinearLayout.LayoutParams(-1, dp(48)))
        send.setOnClickListener {
            val question = input.text.toString().trim()
            if (question.isNotEmpty()) {
                input.setText("")
                transcript.append("\nToi : $question\nIKAR : recherche…\n")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                serviceScope.launch {
                    val reply = withContext(Dispatchers.IO) {
                        val dao = MemoryDatabase.getInstance(applicationContext).memoryDao()
                        dao.insert(MemoryEntry(type = "USER_MESSAGE", content = question))
                        val answer = OfflineChatEngine.answer(question, dao.getAll())
                        dao.insert(MemoryEntry(type = "ASSISTANT_MESSAGE", content = answer))
                        answer
                    }
                    transcript.append("$reply\n")
                    if (speechOutputReady) speechOutput?.speak(reply.take(2500), TextToSpeech.QUEUE_FLUSH, null, "ikar-floating-reply")
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }

        val p = WindowManager.LayoutParams(
            dp(320), dp(440),
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        panel = root
        panelParams = p
        windowManager.addView(root, p)
    }


    private fun startVoiceRecognition(input: EditText, transcript: TextView, scroll: ScrollView) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            transcript.append("\nIKAR : autorise d'abord le microphone depuis l'application principale.\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            transcript.append("\nIKAR : aucun service de reconnaissance vocale n'est disponible.\n")
            return
        }
        try {
            val recognizer = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
            speechRecognizer?.destroy()
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    transcript.append("\nIKAR : je t'écoute…\n")
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    transcript.append("IKAR : reconnaissance vocale impossible (code $error).\n")
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (spoken.isNotBlank()) {
                        input.setText(spoken)
                        input.setSelection(input.text.length)
                        transcript.append("IKAR : j'ai compris : $spoken\nAppuie sur Envoyer pour confirmer.\n")
                    } else {
                        transcript.append("IKAR : je n'ai pas compris. Réessaie.\n")
                    }
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer.startListening(intent)
        } catch (error: Exception) {
            transcript.append("IKAR : impossible de démarrer le microphone.\n")
        }
    }

    private fun hidePanel() {
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        panelParams = null
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    override fun onDestroy() {
        hidePanel()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        speechOutput?.stop()
        speechOutput?.shutdown()
        speechOutput = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.ikar.assistant.STOP_FLOATING"
    }
}
