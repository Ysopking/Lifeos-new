package app.lifeos.next.kernel

object LifeOsResponseComposer {
    fun compose(result: LanguageSubmissionResult): String {
        (result.localKnowledge as? LocalKnowledgeExecutionResult.Produced)?.let {
            return it.output.photon.content
        }
        (result.localDeepSearch as? LocalDeepSearchExecutionResult.Produced)?.let {
            return it.output.photon.content
        }
        result.languageFailure?.let { failure ->
            return "Ich habe deine Nachricht gespeichert, konnte sie aber nicht vollständig verarbeiten: $failure"
        }
        if (result.generatedImage != null) {
            return "Ich habe das Bild lokal erzeugt und als LIFEOS-Photon gespeichert."
        }
        val gaps = result.effectiveRouting?.blockingGaps.orEmpty()
        if (gaps.isNotEmpty()) {
            val missing = gaps
                .map { it.requirement.capabilityId.value }
                .distinct()
                .joinToString(", ")
            return "Ich habe dein Ziel verstanden. Für die vollständige Ausführung fehlt noch: $missing."
        }
        val goal = result.effectiveGoal
        return if (goal != null) {
            "Ich habe deine Nachricht verarbeitet und als ${goal.intent.name.lowercase()}-Ziel in LIFEOS übernommen."
        } else {
            "Ich habe deine Nachricht verarbeitet und im LIFEOS-Gedächtnis verankert."
        }
    }
}
