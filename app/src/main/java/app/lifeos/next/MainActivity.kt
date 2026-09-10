package app.lifeos.next

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lifeos.core.image.ImagePhotonFactory
import app.lifeos.core.model.Photon
import app.lifeos.core.runtime.goal.LocalReminderRecord
import app.lifeos.core.runtime.goal.LocalScheduleGoalEngine
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var model: LifeOsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[LifeOsViewModel::class.java]
        setContent { LifeOsApp(model) }
    }

    override fun onStop() {
        if (::model.isInitialized) model.stopVoiceCapture()
        super.onStop()
    }
}

@Composable
private fun LifeOsApp(model: LifeOsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val runtime by model.runtimeState.collectAsStateWithLifecycle()
    val matrix by model.matrixState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.startVoiceCapture() else model.voicePermissionDenied()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.saveDraft() else model.notificationPermissionDenied()
    }
    val pendingShare = state.pendingShare
    LaunchedEffect(pendingShare) {
        if (pendingShare != null) {
            try {
                val shareIntent = model.createShareIntent(pendingShare)
                context.startActivity(Intent.createChooser(shareIntent, "Mit App teilen"))
                model.communicationShareOpened(pendingShare)
            } catch (_: Exception) {
                model.communicationShareFailed(pendingShare)
            }
        }
    }
    val visible = remember(state.photons, query) {
        val term = query.trim()
        state.photons.filter {
            it.content.contains(term, ignoreCase = true) ||
                it.tags.any { tag -> tag.contains(term, ignoreCase = true) }
        }
    }
    val dateFormat = remember {
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())
    }

    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("LIFEOS · Gedanken", style = MaterialTheme.typography.headlineMedium)
                Text("${state.photons.size} gespeichert · lokal verschlüsselt", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    query,
                    { query = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Gedanken durchsuchen") },
                    singleLine = true,
                )
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.loading) {
                        item { Text("Gedanken werden geladen …") }
                    } else if (visible.isEmpty()) {
                        item {
                            Text(
                                if (query.isBlank()) "Halte deinen ersten Gedanken fest."
                                else "Keine passenden Gedanken gefunden.",
                            )
                        }
                    }
                    items(visible, key = { "${it.id.value}:${it.revision}" }) { photon ->
                        PhotonCard(photon, model, dateFormat)
                    }
                }
                if (state.unreadable > 0) {
                    Text(
                        "${state.unreadable} Datei(en) nicht lesbar. Originaldateien bleiben erhalten.",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                state.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = if (state.loadFailed) model::retryLoad else model::dismissError) {
                        Text(if (state.loadFailed) "Erneut laden" else "Meldung schließen")
                    }
                }
                if (state.lastCapabilityGaps.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Fehlende lokale Fähigkeit", style = MaterialTheme.typography.titleSmall)
                            state.lastCapabilityGaps.take(3).forEach { gap ->
                                Text(
                                    "${gap.requirement.capabilityId.value} · ${gap.type.name.lowercase()} · ${gap.requirement.severity.name.lowercase()}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (state.lastCapabilityGaps.size > 3) {
                                Text(
                                    "+ ${state.lastCapabilityGaps.size - 3} weitere",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            OutlinedButton(
                                onClick = model::requestCapabilityGaps,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.capabilityRequestSaving && !state.loading && !state.loadFailed,
                            ) {
                                Text(
                                    if (state.capabilityRequestSaving) "Anforderung wird gespeichert …"
                                    else "Für ToolWorkshop anfordern"
                                )
                            }
                            Text(
                                "Die Anforderung wird lokal und dauerhaft vorgemerkt; sie startet noch keine automatische Codegenerierung.",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                state.capabilityRequestStatus?.let { status ->
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = model::dismissCapabilityRequestStatus) {
                        Text("Tool-Anforderungsstatus schließen")
                    }
                }
                state.shareStatus?.let { status ->
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = model::dismissShareStatus) {
                        Text("Teilen-Status schließen")
                    }
                }
                state.voiceStatus?.let { status ->
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    if (state.voicePhase == VoiceCapturePhase.IDLE) {
                        TextButton(onClick = model::dismissVoiceStatus) { Text("Sprachstatus schließen") }
                    }
                }
                OutlinedTextField(
                    state.draft,
                    model::editDraft,
                    Modifier.fillMaxWidth(),
                    label = { Text("Neuer Gedanke") },
                    maxLines = 4,
                    enabled = !state.saving && state.voicePhase != VoiceCapturePhase.PROCESSING,
                )
                when (state.voicePhase) {
                    VoiceCapturePhase.IDLE -> {
                        OutlinedButton(
                            onClick = {
                                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    model.startVoiceCapture()
                                } else {
                                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.loading && !state.loadFailed && !state.saving,
                        ) {
                            Text("Sprache aufnehmen · lokal")
                        }
                    }
                    VoiceCapturePhase.RECORDING -> {
                        Button(
                            onClick = model::stopVoiceCapture,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Aufnahme stoppen")
                        }
                    }
                    VoiceCapturePhase.PROCESSING -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Akustik-, Wort- und Kontextfelder konvergieren lokal …", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(
                    onClick = {
                        if (requiresReminderNotificationPermission(context, state.draft)) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            model.saveDraft()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.draft.isNotBlank() &&
                        !state.loading &&
                        !state.loadFailed &&
                        !state.saving &&
                        state.voicePhase == VoiceCapturePhase.IDLE,
                ) {
                    Text(if (state.saving) "Wird gespeichert …" else "Gedanken speichern")
                }
                TextButton(onClick = { diagnostics = !diagnostics }) {
                    Text(if (diagnostics) "Diagnose schließen" else "Diagnose")
                }
                if (diagnostics) {
                    AlertDialog(
                        onDismissRequest = { diagnostics = false },
                        confirmButton = {
                            TextButton(onClick = { diagnostics = false }) { Text("Schließen") }
                        },
                        title = { Text("App-Diagnose") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    "Version ${BuildConfig.VERSION_NAME}\n" +
                                        "Runtime: ${if (runtime.running) "aktiv" else "gestoppt"}\n" +
                                        "Indexiert: ${matrix.nodes.size}\n" +
                                        "Verarbeitet: ${runtime.processed}\n" +
                                        "Fehlgeschlagen: ${runtime.failed}\n" +
                                        "Feldeinflüsse im Verlauf: ${runtime.recentInfluences.size}\n" +
                                        "Feldenergie: ${"%.1f".format(matrix.totalEnergy)}\n" +
                                        "Sprachaufnahme: ${state.voicePhase.name.lowercase()}\n" +
                                        (runtime.lastError ?: "Kein Runtime-Fehler"),
                                )
                                HorizontalDivider()
                                val toolStatus = state.generatedToolStatus
                                if (toolStatus == null) {
                                    Text("Generated Tools: noch nicht geladen")
                                } else {
                                    Text(
                                        "Generated Tools: ${toolStatus.totalTools}\n" +
                                            "Trial: ${toolStatus.trialTools} · Aktiv: ${toolStatus.activeTools}\n" +
                                            "Quarantäne: ${toolStatus.quarantinedTools} · Abgelehnt: ${toolStatus.rejectedTools}\n" +
                                            "Trial-Aufrufe: ${toolStatus.totalTrials}\n" +
                                            "Safety-Verstöße: ${toolStatus.totalSafetyViolations}",
                                    )
                                    toolStatus.tools.take(6).forEach { tool ->
                                        Text(
                                            "${tool.toolId} · ${tool.state.name.lowercase()} · ${tool.trials} Trial(s)",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                    if (toolStatus.tools.size > 6) {
                                        Text(
                                            "+ ${toolStatus.tools.size - 6} weitere Tools",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    }
                                }
                                state.generatedToolStatusError?.let { error ->
                                    Text(error, color = MaterialTheme.colorScheme.error)
                                }
                                if (state.generatedToolStatusLoading) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                                TextButton(
                                    onClick = model::refreshGeneratedToolStatus,
                                    enabled = !state.generatedToolStatusLoading,
                                ) {
                                    Text("Toolstatus aktualisieren")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotonCard(
    photon: Photon,
    model: LifeOsViewModel,
    dateFormat: DateTimeFormatter,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when (photon.mimeType) {
                ImagePhotonFactory.IMAGE_REFERENCE_MIME -> GeneratedImageContent(photon, model)
                LocalScheduleGoalEngine.REMINDER_MIME -> ReminderContent(photon, dateFormat)
                else -> SelectionContainer { Text(photon.content) }
            }
            Text(
                dateFormat.format(photon.provenance.createdAt),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ReminderContent(
    photon: Photon,
    dateFormat: DateTimeFormatter,
) {
    val record = remember(photon.id.value, photon.revision) {
        runCatching { LocalReminderRecord.decode(photon) }.getOrNull()
    }
    if (record == null) {
        Text("Erinnerung beschädigt", color = MaterialTheme.colorScheme.error)
        return
    }
    val failed = "schedule-failed" in photon.tags
    Text(
        if (failed) "Erinnerung nicht aktiv" else "Erinnerung geplant",
        style = MaterialTheme.typography.titleSmall,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
    SelectionContainer { Text(record.message) }
    Text(
        "Auslösung: ${dateFormat.format(record.triggerAt)}",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun GeneratedImageContent(
    photon: Photon,
    model: LifeOsViewModel,
) {
    val previewState by produceState<ImagePreviewState>(
        initialValue = ImagePreviewState.Loading,
        key1 = photon.id.value,
        key2 = photon.revision,
    ) {
        value = model.loadImagePreview(photon)
    }

    when (val current = previewState) {
        ImagePreviewState.Loading -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Lokales Bild wird entschlüsselt …", style = MaterialTheme.typography.bodySmall)
        }

        is ImagePreviewState.Ready -> {
            val preview = current.preview
            val transformed = "transformed" in photon.tags
            Image(
                bitmap = preview.bitmap.asImageBitmap(),
                contentDescription = if (transformed) "Lokal bearbeitetes Bild" else "Lokal erzeugtes Bild",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(preview.width.toFloat() / preview.height.toFloat()),
                contentScale = ContentScale.Fit,
            )
            Text(
                "${if (transformed) "Offline bearbeitet" else "Offline erzeugt"} · ${preview.width}×${preview.height} · ${preview.rendererId}",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        is ImagePreviewState.Failed -> {
            Text("Bild nicht verfügbar", color = MaterialTheme.colorScheme.error)
            Text(current.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun requiresReminderNotificationPermission(context: Context, draft: String): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
    return REMINDER_DRAFT_REGEX.containsMatchIn(draft)
}

private val REMINDER_DRAFT_REGEX = Regex(
    "\\b(erinnere|termin|plane|planen|schedule|remind|appointment|calendar)\\b",
    RegexOption.IGNORE_CASE,
)
