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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.room.Room
import com.ikar.assistant.ai.OfflineChatEngine
import com.ikar.assistant.ai.ConversationTurn
import com.ikar.assistant.ai.IkarCredentialStore
import com.ikar.assistant.ai.IkarOnlineModelGateway
import com.ikar.assistant.ai.IkarOrchestrator
import com.ikar.assistant.ai.MemoryCommandParser
import com.ikar.assistant.ai.WebResearch
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
            text = "✦"
            textSize = 25f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Ouvrir IKAR Assistant"
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(38, 190, 117), Color.rgb(16, 103, 77))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.rgb(190, 255, 218))
            }
            elevation = dp(12).toFloat()
            setOnClickListener { showPanel() }
        }
        params = WindowManager.LayoutParams(
            dp(58), dp(58),
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(14)
            y = dp(190)
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

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val panelWidth = minOf(dp(430), (screenWidth * 0.94f).toInt())
        val panelHeight = minOf(dp(620), (screenHeight * 0.78f).toInt())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.rgb(9, 19, 17))
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.rgb(47, 112, 83))
            }
            elevation = dp(18).toFloat()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val avatar = TextView(this).apply {
            text = "✦"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(41, 180, 112), Color.rgb(18, 92, 70))
            ).apply { cornerRadius = dp(17).toFloat() }
        }
        header.addView(avatar, LinearLayout.LayoutParams(dp(46), dp(46)))
        val identity = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        identity.addView(TextView(this).apply {
            text = "IKAR"
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        identity.addView(TextView(this).apply {
            text = "Assistant personnel · prêt à aider"
            textSize = 11f
            setTextColor(Color.rgb(153, 190, 171))
        })
        header.addView(identity, LinearLayout.LayoutParams(0, -2, 1f))
        val minimize = TextView(this).apply {
            text = "—"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(218, 239, 226))
            background = roundedPanel(Color.rgb(25, 43, 37), dp(13))
            setOnClickListener { hidePanel(); showBubble() }
        }
        header.addView(minimize, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            marginEnd = dp(7)
        })
        val close = TextView(this).apply {
            text = "×"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedPanel(Color.rgb(75, 39, 43), dp(13))
            setOnClickListener { hidePanel(); stopSelf() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(42), dp(42)))
        root.addView(header)

        val divider = View(this).apply { setBackgroundColor(Color.rgb(37, 62, 52)) }
        root.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))

        val transcript = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(12), dp(2), dp(12))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(transcript, android.view.ViewGroup.LayoutParams(-1, -2))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        appendChatBubble(
            transcript,
            "Bonjour ! Je suis IKAR. Je peux t'aider à protéger ton téléphone, organiser tes tâches et répondre à tes questions.",
            fromIkar = true
        )

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val input = EditText(this).apply {
            hint = "Écris ton message…"
            setHintTextColor(Color.rgb(137, 164, 150))
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(14), dp(8), dp(12), dp(8))
            setSingleLine(false)
            minLines = 1
            maxLines = 4
            background = roundedPanel(Color.rgb(23, 39, 33), dp(18)).apply {
                setStroke(dp(1), Color.rgb(53, 87, 70))
            }
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        }
        composer.addView(input, LinearLayout.LayoutParams(0, dp(52), 1f))
        val voice = TextView(this).apply {
            text = "🎙"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedPanel(Color.rgb(32, 69, 53), dp(16))
            contentDescription = "Parler à IKAR"
        }
        composer.addView(voice, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginStart = dp(7)
        })
        val send = TextView(this).apply {
            text = "➤"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(6, 31, 19))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(164, 244, 119), Color.rgb(58, 195, 119))
            ).apply { cornerRadius = dp(16).toFloat() }
            contentDescription = "Envoyer le message"
        }
        composer.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginStart = dp(7)
        })
        root.addView(composer)

        voice.setOnClickListener { startVoiceRecognition(input, transcript, scroll) }
        val sendMessage: () -> Unit = {
            val question = input.text.toString().trim()
            if (question.isNotEmpty()) {
                input.setText("")
                appendChatBubble(transcript, question, fromIkar = false)
                val waiting = appendChatBubble(transcript, "Je réfléchis…", fromIkar = true, subdued = true)
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                send.isEnabled = false
                serviceScope.launch {
                    try {
                        val reply = withContext(Dispatchers.IO) {
                        val dao = MemoryDatabase.getInstance(applicationContext).memoryDao()
                        val previousEntries = dao.getAll()
                        val history = previousEntries
                            .filter { it.type == "USER_MESSAGE" || it.type == "ASSISTANT_MESSAGE" }
                            .sortedBy { it.createdAt }
                            .takeLast(10)
                            .map {
                                ConversationTurn(
                                    role = if (it.type == "USER_MESSAGE") "user" else "assistant",
                                    content = it.content
                                )
                            }
                        dao.insert(MemoryEntry(type = "USER_MESSAGE", content = question))
                        val savedEntries = dao.getAll()
                        val connectedMode = getSharedPreferences("ikar_runtime_settings", MODE_PRIVATE)
                            .getBoolean("connected_mode", false)
                        val route = IkarOrchestrator.plan(
                            question = question,
                            connectedMode = connectedMode,
                            shareRelevantMemory = false,
                            hasRelevantMemory = false
                        )
                        val answer = when (route.destination) {
                            IkarOrchestrator.Destination.REMEMBER_LOCALLY -> {
                                val fact = MemoryCommandParser.extract(question)
                                    ?: throw IllegalStateException("Instruction de mémorisation non reconnue.")
                                val alreadySaved = savedEntries.any {
                                    it.type == "NOTE" && MemoryCommandParser.sameFact(it.content, fact)
                                }
                                if (!alreadySaved) {
                                    dao.insert(MemoryEntry(type = "NOTE", content = fact))
                                    "C'est enregistré dans ta mémoire locale."
                                } else {
                                    "Cette information est déjà enregistrée dans ta mémoire locale."
                                }
                            }
                            IkarOrchestrator.Destination.WEB_RESEARCH -> WebResearch.search(question)
                            IkarOrchestrator.Destination.ONLINE_MODEL -> {
                                val token = IkarCredentialStore.load(applicationContext)
                                    ?: throw IllegalStateException(
                                        "Le mode connecté est activé, mais aucun jeton mémorisé n'est disponible pour la bulle. Dans IKAR, active « Mémoriser le jeton sur cet appareil » pour utiliser l'IA en ligne depuis la bulle."
                                    )
                                IkarOnlineModelGateway.sendMessageWithLocalFallback(token, question, history, savedEntries)
                            }
                            IkarOrchestrator.Destination.LOCAL_SECURITY_GUIDANCE,
                            IkarOrchestrator.Destination.LOCAL_KNOWLEDGE,
                            IkarOrchestrator.Destination.OFFLINE_ASSISTANT ->
                                OfflineChatEngine.answer(question, savedEntries)
                        }
                        dao.insert(MemoryEntry(type = "ASSISTANT_MESSAGE", content = answer))
                        answer
                    }
                        transcript.removeView(waiting)
                        appendChatBubble(transcript, reply, fromIkar = true)
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                        if (speechOutputReady) speechOutput?.speak(reply.take(2500), TextToSpeech.QUEUE_FLUSH, null, "ikar-floating-reply")
                    } catch (error: Exception) {
                        transcript.removeView(waiting)
                        appendChatBubble(
                            transcript,
                            error.localizedMessage ?: "IKAR n'a pas pu traiter cette demande. Réessaie.",
                            fromIkar = true
                        )
                        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                    } finally {
                        send.isEnabled = true
                    }
                }
            }
        }
        send.setOnClickListener { sendMessage() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendMessage()
                true
            } else false
        }

        val p = WindowManager.LayoutParams(
            panelWidth, panelHeight,
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

    private fun appendChatBubble(
        container: LinearLayout,
        message: String,
        fromIkar: Boolean,
        subdued: Boolean = false
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (fromIkar) Gravity.START else Gravity.END
            setPadding(0, dp(3), 0, dp(3))
        }
        val bubble = TextView(this).apply {
            text = message
            textSize = 14f
            setTextColor(if (subdued) Color.rgb(153, 181, 165) else Color.rgb(239, 247, 241))
            setPadding(dp(13), dp(10), dp(13), dp(10))
            background = roundedPanel(
                if (fromIkar) Color.rgb(25, 45, 36) else Color.rgb(32, 105, 75),
                dp(18)
            )
            maxWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
            setTextIsSelectable(true)
        }
        row.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply {
            if (fromIkar) marginEnd = dp(34) else marginStart = dp(34)
        })
        container.addView(row, LinearLayout.LayoutParams(-1, -2))
        return row
    }

    private fun roundedPanel(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun startVoiceRecognition(input: EditText, transcript: LinearLayout, scroll: ScrollView) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            appendChatBubble(transcript, "Autorise d'abord le microphone depuis l'application principale.", fromIkar = true)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            appendChatBubble(transcript, "Aucun service de reconnaissance vocale n'est disponible.", fromIkar = true)
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
                    appendChatBubble(transcript, "Je t'écoute…", fromIkar = true, subdued = true)
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    appendChatBubble(transcript, "Reconnaissance vocale impossible (code $error).", fromIkar = true)
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
                override fun onResults(results: Bundle?) {
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (spoken.isNotBlank()) {
                        input.setText(spoken)
                        input.setSelection(input.text.length)
                        appendChatBubble(transcript, "J'ai compris : $spoken. Appuie sur envoyer pour confirmer.", fromIkar = true)
                    } else {
                        appendChatBubble(transcript, "Je n'ai pas compris. Réessaie.", fromIkar = true)
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
            appendChatBubble(transcript, "Impossible de démarrer le microphone.", fromIkar = true)
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
