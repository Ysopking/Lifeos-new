package app.lifeos.next

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.lifeos.core.runtime.capability.GeneratedToolState

enum class OutcomeAction {
    NONE,
    RETRY_LOAD,
    SAVE_DRAFT,
    CREATE_TOOL,
    ACTIVATE_TRIAL,
    STOP_RECORDING,
}

enum class OutcomeTone {
    READY,
    ACTIVE,
    ATTENTION,
}

data class OutcomeOverview(
    val eyebrow: String,
    val title: String,
    val summary: String,
    val nextStep: String,
    val action: OutcomeAction,
    val actionLabel: String? = null,
    val tone: OutcomeTone = OutcomeTone.READY,
)

/**
 * V17 outcome-first projection for the private owner UI.
 * It deliberately exposes the user's next useful step before implementation diagnostics.
 * No authority is created here: every action still goes through the existing owner/runtime gates.
 */
fun buildOutcomeOverview(state: LifeOsState): OutcomeOverview {
    val firstTrial = state.generatedToolStatus?.tools?.firstOrNull { it.state == GeneratedToolState.TRIAL }
    val activeTools = state.generatedToolStatus?.activeTools ?: 0

    return when {
        state.loadFailed -> OutcomeOverview(
            eyebrow = "Handlung nötig",
            title = "Deine lokalen Daten konnten nicht vollständig geladen werden",
            summary = "LIFEOS bleibt geschlossen statt mit einem unvollständigen Zustand weiterzuarbeiten.",
            nextStep = "Ladevorgang erneut sicher starten.",
            action = OutcomeAction.RETRY_LOAD,
            actionLabel = "Erneut laden",
            tone = OutcomeTone.ATTENTION,
        )

        state.loading -> OutcomeOverview(
            eyebrow = "Systemstart",
            title = "LIFEOS stellt deinen lokalen Zustand wieder her",
            summary = "Gedanken, Ziele, Tools und Recovery-Zustände werden geprüft, bevor Aktionen freigegeben werden.",
            nextStep = "Danach erscheint automatisch dein nächster sinnvoller Schritt.",
            action = OutcomeAction.NONE,
            tone = OutcomeTone.ACTIVE,
        )

        state.saving -> OutcomeOverview(
            eyebrow = "Wird gesichert",
            title = "Dein Gedanke wird dauerhaft gespeichert",
            summary = "Die lokale Persistenz läuft bereits.",
            nextStep = "Nach erfolgreichem Abschluss übernimmt LIFEOS den nächsten Schritt.",
            action = OutcomeAction.NONE,
            tone = OutcomeTone.ACTIVE,
        )

        state.voicePhase == VoiceCapturePhase.RECORDING -> OutcomeOverview(
            eyebrow = "Spracheingabe aktiv",
            title = "LIFEOS hört lokal zu",
            summary = "Die Aufnahme bleibt auf deinem Gerät.",
            nextStep = "Aufnahme beenden, sobald dein Gedanke vollständig ist.",
            action = OutcomeAction.STOP_RECORDING,
            actionLabel = "Aufnahme stoppen",
            tone = OutcomeTone.ACTIVE,
        )

        state.voicePhase == VoiceCapturePhase.PROCESSING -> OutcomeOverview(
            eyebrow = "Lokale Verarbeitung",
            title = "Deine Sprache wird in einen nutzbaren Gedanken überführt",
            summary = "Akustik-, Wort- und Kontextfelder werden lokal zusammengeführt.",
            nextStep = "Das Ergebnis erscheint anschließend im Eingabefeld.",
            action = OutcomeAction.NONE,
            tone = OutcomeTone.ACTIVE,
        )

        state.capabilityActivationSaving -> OutcomeOverview(
            eyebrow = "Sicherheitsprüfung",
            title = "Ein neues Tool wird vor Aktivierung geprüft",
            summary = "Canaries, Readiness, Promotion-Seal und Owner-Evidence müssen bestehen.",
            nextStep = "Nur ein vollständig akzeptierter Provider wird aktiv.",
            action = OutcomeAction.NONE,
            tone = OutcomeTone.ACTIVE,
        )

        state.capabilityRequestSaving -> OutcomeOverview(
            eyebrow = "Fähigkeit wird aufgebaut",
            title = "LIFEOS prüft eine fehlende lokale Fähigkeit",
            summary = "Der ToolWorkshop arbeitet isoliert und darf das Tool noch nicht produktiv aktivieren.",
            nextStep = "Ein bestandener Kandidat landet zunächst in TRIAL.",
            action = OutcomeAction.NONE,
            tone = OutcomeTone.ACTIVE,
        )

        state.draft.isNotBlank() -> OutcomeOverview(
            eyebrow = "Dein Fokus",
            title = "Dieser Gedanke ist bereit zur Verarbeitung",
            summary = state.draft.trim().replace(Regex("\\s+"), " ").take(140),
            nextStep = "Jetzt lokal sichern und in den LIFEOS-Kreislauf geben.",
            action = OutcomeAction.SAVE_DRAFT,
            actionLabel = "Gedanken sichern",
            tone = OutcomeTone.READY,
        )

        firstTrial != null -> OutcomeOverview(
            eyebrow = "Nächster sinnvoller Schritt",
            title = "Eine neue Fähigkeit ist bereit für deine Freigabeprüfung",
            summary = "${firstTrial.capabilityId} hat ${firstTrial.trials} isolierte Trial-Läufe erreicht und ist noch nicht produktiv aktiv.",
            nextStep = "TRIAL prüfen und nur bei vollständig grüner Evidence aktivieren.",
            action = OutcomeAction.ACTIVATE_TRIAL,
            actionLabel = "TRIAL prüfen & aktivieren",
            tone = OutcomeTone.READY,
        )

        state.lastCapabilityGaps.isNotEmpty() -> OutcomeOverview(
            eyebrow = "Ziel noch nicht vollständig lösbar",
            title = "Für dein letztes Ziel fehlt LIFEOS noch eine lokale Fähigkeit",
            summary = state.lastCapabilityGaps.first().requirement.capabilityId.value,
            nextStep = "Die fehlende Fähigkeit kontrolliert im lokalen ToolWorkshop erzeugen.",
            action = OutcomeAction.CREATE_TOOL,
            actionLabel = "Fähigkeit lokal aufbauen",
            tone = OutcomeTone.ATTENTION,
        )

        state.error != null -> OutcomeOverview(
            eyebrow = "Hinweis",
            title = "Ein Teilvorgang braucht Aufmerksamkeit",
            summary = state.error,
            nextStep = "Die Details stehen direkt darunter; bestehende lokale Daten bleiben unangetastet.",
            action = OutcomeAction.NONE,
            tone = OutcomeTone.ATTENTION,
        )

        state.unreadable > 0 -> OutcomeOverview(
            eyebrow = "Integritätsprüfung",
            title = "${state.unreadable} lokale Datei(en) wurden nicht als sicher lesbar akzeptiert",
            summary = "LIFEOS bewahrt die Originale und erzeugt daraus keine Autorität.",
            nextStep = "Du kannst weiterarbeiten; die Diagnose enthält die technischen Details.",
            action = OutcomeAction.NONE,
            tone = OutcomeTone.ATTENTION,
        )

        state.photons.isEmpty() -> OutcomeOverview(
            eyebrow = "Startklar",
            title = "LIFEOS ist bereit für deinen ersten Gedanken",
            summary = "Text oder Sprache reicht — die technische Komplexität bleibt im Hintergrund.",
            nextStep = "Unten einen Gedanken eingeben oder lokal einsprechen.",
            action = OutcomeAction.NONE,
            tone = OutcomeTone.READY,
        )

        else -> OutcomeOverview(
            eyebrow = "Für dich bereit",
            title = "Dein LIFEOS ist aktuell handlungsbereit",
            summary = "${state.photons.size} Gedanken lokal gesichert · $activeTools aktive lokale Tools.",
            nextStep = "Neuen Gedanken festhalten, suchen oder ein bestehendes Ergebnis weiterverwenden.",
            action = OutcomeAction.NONE,
            tone = OutcomeTone.READY,
        )
    }
}

@Composable
fun OutcomeOverviewCard(
    overview: OutcomeOverview,
    onAction: (OutcomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(overview.eyebrow, style = MaterialTheme.typography.labelMedium)
            Text(overview.title, style = MaterialTheme.typography.titleMedium)
            Text(overview.summary, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Nächster Schritt: ${overview.nextStep}",
                style = MaterialTheme.typography.bodySmall,
            )
            overview.actionLabel?.let { label ->
                Button(
                    onClick = { onAction(overview.action) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(label)
                }
            }
        }
    }
}
