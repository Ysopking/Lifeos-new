package app.lifeos.next.kernel

object LifeOsResponseComposer {
    fun compose(result: LanguageSubmissionResult): String {
        (result.localKnowledge as? LocalKnowledgeExecutionResult.Produced)?.let {
            return it.output.photon.content
        }
        (result.localDeepSearch as? LocalDeepSearchExecutionResult.Produced)?.let {
            return it.output.photon.content
        }
        when (val scheduled = result.localSchedule) {
            is LocalScheduleExecutionResult.Scheduled ->
                return "Ich habe die geplante Aktion lokal erstellt und als LIFEOS-Photon gespeichert."
            is LocalScheduleExecutionResult.Blocked ->
                return "Ich konnte die geplante Aktion nicht ausführen: ${scheduled.reason}"
            is LocalScheduleExecutionResult.Failed ->
                return "Die Planung ist fehlgeschlagen: ${scheduled.message}"
            null -> Unit
        }
        when (val transformed = result.localImageTransform) {
            is LocalImageTransformExecutionResult.Transformed ->
                return "Ich habe das Bild lokal verarbeitet und das Ergebnis wieder als LIFEOS-Photon gespeichert."
            is LocalImageTransformExecutionResult.Blocked ->
                return "Ich konnte die Bildverarbeitung nicht ausführen: ${transformed.reason}"
            is LocalImageTransformExecutionResult.Failed ->
                return "Die Bildverarbeitung ist fehlgeschlagen: ${transformed.message}"
            null -> Unit
        }
        when (val communication = result.localCommunication) {
            is LocalCommunicationExecutionResult.Prepared ->
                return "Ich habe die Freigabe vorbereitet. Die eigentliche Übergabe bleibt unter deiner Kontrolle."
            is LocalCommunicationExecutionResult.Blocked ->
                return "Ich konnte die Freigabe nicht vorbereiten: ${communication.reason}"
            is LocalCommunicationExecutionResult.Failed ->
                return "Die Freigabevorbereitung ist fehlgeschlagen: ${communication.message}"
            null -> Unit
        }
        when (val resume = result.goalResume) {
            is GoalResumeExecutionResult.Resumed ->
                if (result.localKnowledge == null && result.localDeepSearch == null &&
                    result.localSchedule == null && result.localImageTransform == null &&
                    result.localCommunication == null && result.imageGeneration == null
                ) {
                    return "Ich habe das bestehende Ziel wieder aufgenommen und den nächsten LIFEOS-Schritt aktiviert."
                }
            is GoalResumeExecutionResult.Blocked ->
                return "Ich konnte das Ziel nicht fortsetzen: ${resume.message}"
            is GoalResumeExecutionResult.Failed ->
                return "Das Wiederaufnehmen des Ziels ist fehlgeschlagen: ${resume.message}"
            null -> Unit
        }
        when (val image = result.imageGeneration) {
            is ImageGenerationResult.Generated ->
                return "Ich habe das Bild lokal erzeugt und als LIFEOS-Photon gespeichert."
            is ImageGenerationResult.Blocked ->
                return "Ich konnte das Bild nicht erzeugen: ${image.reasons.joinToString("; ")}"
            is ImageGenerationResult.Failed ->
                return "Die Bilderzeugung ist fehlgeschlagen: ${image.message}"
            null -> Unit
        }
        result.languageFailure?.let { failure ->
            return "Ich habe deine Nachricht gespeichert, konnte sie aber nicht vollständig verarbeiten: $failure"
        }
        val gaps = result.effectiveRouting?.blockingGaps.orEmpty()
        if (gaps.isNotEmpty()) {
            val missing = gaps
                .map { it.requirement.capabilityId.value }
                .distinct()
                .joinToString(", ")
            return "Ich habe dein Ziel verstanden. Der produktive Provider für $missing fehlt noch. " +
                "Genesis wählt dafür den kleinsten sicheren Erweiterungspfad; den konkreten Handoff " +
                "und nachfolgenden ToolWorkshop-/BuildStudio-/Evolution-Status siehst du direkt im Systemstrom."
        }
        val goal = result.effectiveGoal
        return if (goal != null) {
            "Ich habe deine Nachricht verarbeitet und als ${goal.intent.name.lowercase()}-Ziel in LIFEOS übernommen."
        } else {
            "Ich habe deine Nachricht verarbeitet und im LIFEOS-Gedächtnis verankert."
        }
    }
}
