package com.ikar.assistant.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun BatteryTestPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf("Aucun test effectué sur cet appareil.") }
    var sampleCount by remember { mutableIntStateOf(0) }
    var firstPercent by remember { mutableIntStateOf(-1) }
    var lastPercent by remember { mutableIntStateOf(-1) }
    var peakPssMb by remember { mutableIntStateOf(0) }
    var latestPssMb by remember { mutableIntStateOf(0) }
    var currentAvailable by remember { mutableStateOf(false) }
    var chargeAvailable by remember { mutableStateOf(false) }
    var session: BatteryStabilitySession? by remember { mutableStateOf(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Test batterie et stabilité sur le téléphone", style = MaterialTheme.typography.titleMedium)
            Text("Test volontaire de 5 minutes, avec un relevé toutes les 30 secondes. Garde IKAR ouvert et utilise-le normalement pendant le test.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !running, onClick = {
                val active = BatteryStabilitySession(context)
                session = active
                running = true
                sampleCount = 0
                firstPercent = -1
                lastPercent = -1
                peakPssMb = 0
                report = "Test en cours…"
                scope.launch {
                    repeat(10) {
                        if (!isActive || !running) return@launch
                        val sample = runCatching { active.sample() }.getOrNull() ?: return@launch
                        if (firstPercent < 0) firstPercent = sample.batteryPercent
                        lastPercent = sample.batteryPercent
                        latestPssMb = sample.processPssKb / 1024
                        peakPssMb = maxOf(peakPssMb, latestPssMb)
                        currentAvailable = sample.currentNowMicroAmps != null
                        chargeAvailable = sample.chargeCounterMicroAmpHours != null
                        sampleCount++
                        report = "Échantillon $sampleCount/10 · batterie $lastPercent% · mémoire IKAR ${latestPssMb} Mo"
                        if (sampleCount < 10) delay(30_000)
                    }
                    session?.finish()
                    running = false
                    val delta = if (firstPercent >= 0 && lastPercent >= 0) firstPercent - lastPercent else 0
                    report = "Test terminé : $sampleCount relevés sur environ 5 minutes. Variation batterie : $delta point(s). PSS actuel : ${latestPssMb} Mo; pic observé : ${peakPssMb} Mo. Courant batterie : ${if (currentAvailable) "capteur disponible" else "non fourni"}. Compteur de charge : ${if (chargeAvailable) "disponible" else "non fourni"}. Ce test ne mesure pas précisément l’énergie consommée et dépend des capteurs du fabricant."
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Démarrer le test de 5 minutes") }
            Button(enabled = running, onClick = {
                session?.finish()
                running = false
                report = "Test arrêté après $sampleCount relevé(s)."
            }, modifier = Modifier.fillMaxWidth()) { Text("Arrêter le test") }
            Text(report, style = MaterialTheme.typography.bodySmall)
            Text("Pour comparer deux essais, garde la même luminosité et les mêmes applications ouvertes. Ce relevé n’est pas un test thermique certifié.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
