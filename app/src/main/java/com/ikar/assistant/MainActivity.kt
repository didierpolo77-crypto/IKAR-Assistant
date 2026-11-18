package com.ikar.assistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

private const val CHANNEL_ID = "ikar_general"

class MainActivity : ComponentActivity() {
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
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IkarHome(
                        onPickImage = { uri, onResult ->
                            recognizeText(uri, onResult)
                        },
                        onNotify = { requestNotificationPermissionOrNotify() }
                    )
                }
            }
        }
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
    onPickImage: (Uri, (String) -> Unit) -> Unit,
    onNotify: () -> Unit
) {
    var recognizedText by remember { mutableStateOf("Le texte extrait de l'image apparaîtra ici.") }
    var processing by remember { mutableStateOf(false) }
    var connectedMode by remember { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            processing = true
            onPickImage(uri) { text ->
                recognizedText = text
                processing = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("IKAR Assistant", style = MaterialTheme.typography.headlineMedium)
        Text("Assistant Android — première version", style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lecture de texte hors connexion", style = MaterialTheme.typography.titleMedium)
                Text("Sélectionne une image. IKAR en extrait le texte directement sur le téléphone avec le moteur OCR local.")
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
                    else "Mode hors connexion : l'OCR local reste disponible.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Button(onClick = onNotify, modifier = Modifier.fillMaxWidth()) {
            Text("Envoyer une notification de test")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Confidentialité : l'image choisie est traitée localement par l'OCR. Cette version ne surveille pas l'écran et n'agit pas dans d'autres applications.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
