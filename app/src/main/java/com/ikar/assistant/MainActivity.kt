package com.ikar.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.net.Uri
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.ikar.assistant.data.MemoryDatabase
import com.ikar.assistant.data.MemoryEntry
import com.ikar.assistant.data.MemoryDao
import com.ikar.assistant.ai.OfflineChatEngine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CHANNEL_ID = "ikar_general"

class MainActivity : ComponentActivity() {
    private var speechOutput: TextToSpeech? = null
    private var speechOutputReady = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showLocalNotification()
            } else {
                Toast.makeText(this, "Autorise les notifications pour recevoir les alertes IKAR.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        speechOutput = TextToSpeech(this) { status ->
            speechOutputReady = status == TextToSpeech.SUCCESS &&
                (speechOutput?.setLanguage(Locale.FRANCE) ?: TextToSpeech.LANG_MISSING_DATA) >= TextToSpeech.LANG_AVAILABLE
        }
        val memoryDao = MemoryDatabase.getInstance(applicationContext).memoryDao()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IkarHome(
                        memoryDao = memoryDao,
                        onPickImage = { uri, onResult -> recognizeText(uri, onResult) },
                        onNotify = { requestNotificationPermissionOrNotify() },
                        onSpeak = { speakReply(it) },
                        onEnableBubble = { enableFloatingBubble() },
                        onDisableBubble = { stopService(Intent(this, FloatingAssistantService::class.java)) }
                    )
                }
            }
        }
    }

    private fun speakReply(message: String) {
        if (speechOutputReady) {
            speechOutput?.speak(message.take(3500), TextToSpeech.QUEUE_FLUSH, null, "ikar-reply")
        } else {
            Toast.makeText(this, "La voix française n'est pas prête. Vérifie les données vocales dans Android.", Toast.LENGTH_LONG).show()
        }
    }

    private fun enableFloatingBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Autorise l'affichage par-dessus les autres applications, puis reviens appuyer sur ce bouton.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, FloatingAssistantService::class.java))
        Toast.makeText(this, "Bulle IKAR activée.", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        speechOutput?.stop()
        speechOutput?.shutdown()
        speechOutput = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Notifications IKAR",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Notifications déclenchées par l'utilisateur dans IKAR" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun recognizeText(uri: Uri, onResult: (String) -> Unit) {
        try {
            val image = InputImage.fromFilePath(this, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    onResult(result.text.ifBlank { "Aucun texte détecté dans cette image." })
                    recognizer.close()
                }
                .addOnFailureListener { error ->
                    onResult("Échec de la lecture : ${error.localizedMessage ?: "erreur inconnue"}")
                    recognizer.close()
                }
        } catch (error: Exception) {
            onResult("Impossible d'ouvrir l'image : ${error.localizedMessage ?: "erreur"}")
        }
    }

    private fun requestNotificationPermissionOrNotify() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            showLocalNotification()
        }
    }

    private fun showLocalNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("IKAR Assistant")
            .setContentText("Notification de test demandée depuis l'application.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(1001, notification)
    }
}

@Composable
private fun IkarHome(
    memoryDao: MemoryDao,
    onPickImage: (Uri, (String) -> Unit) -> Unit,
    onNotify: () -> Unit,
    onSpeak: (String) -> Unit,
    onEnableBubble: () -> Unit,
    onDisableBubble: () -> Unit
) {
    var recognizedText by remember { mutableStateOf("Le texte extrait de l'image apparaîtra ici.") }
    var processing by remember { mutableStateOf(false) }
    var connectedMode by remember { mutableStateOf(false) }
    var noteText by remember { mutableStateOf("") }
    var memories by remember { mutableStateOf<List<MemoryEntry>>(emptyList()) }
    var chatMessages by remember { mutableStateOf<List<MemoryEntry>>(emptyList()) }
    var chatInput by remember { mutableStateOf("") }
    var sendingMessage by remember { mutableStateOf(false) }
    var speakReplies by remember { mutableStateOf(true) }
    var listening by remember { mutableStateOf(false) }
    var bubbleEnabled by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val recognizerState = remember { mutableStateOf<SpeechRecognizer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            recognizerState.value?.destroy()
            recognizerState.value = null
        }
    }

    fun beginVoiceRecognition() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                memoryError = "Aucun service de reconnaissance vocale n'est disponible."
                return
            }
            val recognizer = if (Build.VERSION.SDK_INT >= 31 &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            else SpeechRecognizer.createSpeechRecognizer(context)
            recognizerState.value?.destroy()
            recognizerState.value = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { listening = true }
                override fun onBeginningOfSpeech() { listening = true }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { listening = false }
                override fun onError(error: Int) {
                    listening = false
                    recognizer.destroy()
                    recognizerState.value = null
                    memoryError = "Échec de la reconnaissance vocale (code $error). Vérifie les services vocaux et la langue française."
                }
                override fun onResults(results: Bundle?) {
                    listening = false
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!spoken.isNullOrBlank()) chatInput = spoken
                    recognizer.destroy()
                    recognizerState.value = null
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
            listening = true
            memoryError = null
        } catch (error: Exception) {
            listening = false
            memoryError = "Impossible de démarrer le microphone : " + (error.localizedMessage ?: "service indisponible")
        }
    }

    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginVoiceRecognition()
        else memoryError = "Autorise le microphone pour parler à IKAR."
    }
    var memoryError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refreshMemories() {
        val allEntries = withContext(Dispatchers.IO) { memoryDao.getAll() }
        memories = allEntries.filter { it.type == "NOTE" || it.type == "OCR" }
        chatMessages = allEntries.filter { it.type == "USER_MESSAGE" || it.type == "ASSISTANT_MESSAGE" }.take(30).reversed()
    }

    LaunchedEffect(memoryDao) {
        try {
            refreshMemories()
        } catch (error: Exception) {
            memoryError = "Impossible de charger la mémoire locale."
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            processing = true
            onPickImage(uri) { text ->
                recognizedText = text
                processing = false
                if (text.isNotBlank() &&
                    !text.startsWith("Échec de la lecture") &&
                    !text.startsWith("Impossible d'ouvrir") &&
                    text != "Aucun texte détecté dans cette image."
                ) {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                memoryDao.insert(MemoryEntry(type = "OCR", content = text))
                            }
                            refreshMemories()
                            memoryError = null
                        } catch (error: Exception) {
                            memoryError = "Le texte a été lu, mais n'a pas pu être mémorisé."
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("IKAR Assistant", style = MaterialTheme.typography.headlineMedium)
        Text("Assistant Android — mémoire locale V2", style = MaterialTheme.typography.bodyMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Discussion hors connexion", style = MaterialTheme.typography.titleLarge)
                Text("IKAR recherche dans tes notes et tes textes extraits. Les échanges sont enregistrés sur ce téléphone.", style = MaterialTheme.typography.bodySmall)
                if (chatMessages.isEmpty()) {
                    Text("Bonjour ! Pose une question sur une information que tu as déjà enregistrée.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    chatMessages.forEach { message ->
                        val fromUser = message.type == "USER_MESSAGE"
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = if (fromUser) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(if (fromUser) "Toi" else "IKAR", style = MaterialTheme.typography.labelMedium)
                                Text(message.content, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Lire les réponses à voix haute")
                    Switch(checked = speakReplies, onCheckedChange = { speakReplies = it })
                }
                OutlinedTextField(
                    value = chatInput,
                    onValueChange = { chatInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Écris ou dicte ton message") },
                    minLines = 1,
                    maxLines = 4,
                    enabled = !sendingMessage
                )
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            beginVoiceRecognition()
                        } else {
                            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !sendingMessage && !listening,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (listening) "Écoute en cours…" else "Parler à IKAR")
                }
                Button(
                    onClick = {
                        val question = chatInput.trim()
                        if (question.isNotEmpty() && !sendingMessage) {
                            sendingMessage = true
                            chatInput = ""
                            scope.launch {
                                try {
                                    val answer = withContext(Dispatchers.IO) {
                                        memoryDao.insert(MemoryEntry(type = "USER_MESSAGE", content = question))
                                        val savedEntries = memoryDao.getAll()
                                        val reply = OfflineChatEngine.answer(question, savedEntries)
                                        memoryDao.insert(MemoryEntry(type = "ASSISTANT_MESSAGE", content = reply))
                                        reply
                                    }
                                    refreshMemories()
                                    memoryError = null
                                    if (speakReplies) onSpeak(answer)
                                } catch (error: Exception) {
                                    chatInput = question
                                    memoryError = "Impossible d'enregistrer la conversation. Réessaie."
                                } finally {
                                    sendingMessage = false
                                }
                            }
                        }
                    },
                    enabled = chatInput.isNotBlank() && !sendingMessage,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (sendingMessage) "IKAR réfléchit…" else "Envoyer")
                }
                Text("Moteur local simple : les réponses générales et la recherche Internet ne sont pas encore intégrées.", style = MaterialTheme.typography.labelSmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bulle flottante", style = MaterialTheme.typography.titleMedium)
                Text("Affiche une bulle IKAR au-dessus des autres applications. Android demandera ton autorisation. La bulle ne lit pas l'écran et ne surveille pas les autres applications.")
                Button(onClick = { onEnableBubble(); bubbleEnabled = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Activer la bulle IKAR")
                }
                Button(onClick = { onDisableBubble(); bubbleEnabled = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("Désactiver la bulle")
                }
                Text(if (bubbleEnabled) "Activation demandée. Vérifie que la bulle apparaît." else "La bulle est désactivée ou son état n'a pas encore été vérifié.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mémoire personnelle hors connexion", style = MaterialTheme.typography.titleMedium)
                Text("Enregistre ici des notes que tu souhaites qu'IKAR puisse retrouver plus tard. Elles restent dans le stockage privé de cette application.")
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Écrire une note à mémoriser") },
                    minLines = 2
                )
                Button(
                    onClick = {
                        val value = noteText.trim()
                        if (value.isNotEmpty()) {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        memoryDao.insert(MemoryEntry(type = "NOTE", content = value))
                                    }
                                    noteText = ""
                                    refreshMemories()
                                    memoryError = null
                                } catch (error: Exception) {
                                    memoryError = "Impossible d'enregistrer cette note."
                                }
                            }
                        }
                    },
                    enabled = noteText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Enregistrer dans ma mémoire")
                }
                Text("${memories.size} élément(s) enregistré(s)", style = MaterialTheme.typography.labelMedium)
                memoryError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                memories.forEach { entry ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                if (entry.type == "OCR") "Texte extrait d'une image" else "Note personnelle",
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(entry.content, style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Button(onClick = {
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { memoryDao.delete(entry) }
                                            refreshMemories()
                                        } catch (error: Exception) {
                                            memoryError = "Impossible de supprimer cet élément."
                                        }
                                    }
                                }) {
                                    Text("Supprimer")
                                }
                            }
                        }
                    }
                }
                if (memories.isEmpty()) {
                    Text("Ta mémoire est encore vide.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lecture de texte hors connexion", style = MaterialTheme.typography.titleMedium)
                Text("Sélectionne une image. IKAR en extrait le texte sur le téléphone et l'ajoute à ta mémoire.")
                Button(onClick = { imagePicker.launch("image/*") }, enabled = !processing) {
                    Text(if (processing) "Lecture en cours…" else "Choisir une image")
                }
                Text(recognizedText, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mode de fonctionnement", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Préférence mode connecté")
                        Text("Le serveur distant n'est pas encore configuré.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = connectedMode, onCheckedChange = { connectedMode = it })
                }
                Text(
                    if (connectedMode) "Mode connecté sélectionné, mais aucune requête serveur n'est envoyée tant que le backend n'est pas configuré."
                    else "Mode hors connexion : la mémoire et l'OCR local restent disponibles.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Button(onClick = onNotify, modifier = Modifier.fillMaxWidth()) {
            Text("Envoyer une notification de test")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Confidentialité : les notes et les textes extraits sont conservés dans la base locale privée de l'application. Ils ne sont pas envoyés à un serveur.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
