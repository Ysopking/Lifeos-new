package app.lifeos.core.language

enum class PragmaticActType {
    DIRECT_REQUEST,
    INDIRECT_REQUEST,
    DESIRE,
    PREFERENCE,
    SUGGESTION,
    CONFIRMATION,
    CORRECTION,
    NONE,
}

data class PragmaticAct(
    val type: PragmaticActType,
    val confidence: Double,
    val descriptiveOnly: Boolean,
) {
    init { require(confidence.isFinite() && confidence in 0.0..1.0) }

    companion object {
        fun none(): PragmaticAct = PragmaticAct(PragmaticActType.NONE, 1.0, true)
    }
}

class PragmaticActResolver {
    fun resolve(utterance: NormalizedUtterance): PragmaticAct {
        val text = utterance.normalized
        return when {
            text.startsWith("ich brauche ") ||
                text.startsWith("ich bräuchte ") ||
                text.startsWith("ich braeuchte ") ->
                PragmaticAct(PragmaticActType.DESIRE, 0.92, true)

            text.startsWith("es wäre gut wenn ") ||
                text.startsWith("es waere gut wenn ") ||
                text.startsWith("it would be good if ") ->
                PragmaticAct(PragmaticActType.INDIRECT_REQUEST, 0.88, true)

            text.startsWith("könntest du ") ||
                text.startsWith("koenntest du ") ||
                text.startsWith("could you ") ->
                PragmaticAct(PragmaticActType.INDIRECT_REQUEST, 0.94, true)

            text.startsWith("lieber ") || text.startsWith("rather ") ->
                PragmaticAct(PragmaticActType.PREFERENCE, 0.88, true)

            text.startsWith("genau") || text.startsWith("exactly") ->
                PragmaticAct(PragmaticActType.CONFIRMATION, 0.96, true)

            text.startsWith("nein") || text.startsWith("no,") ->
                PragmaticAct(PragmaticActType.CORRECTION, 0.92, true)

            else -> PragmaticAct.none()
        }
    }
}
