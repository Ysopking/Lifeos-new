package app.lifeos.next.ui.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Private-device speech output. Network-required TTS voices are never selected. */
internal class AndroidSpeechOutput(
    context: Context,
) : TextToSpeech.OnInitListener {
    private data class PendingSpeech(
        val id: String,
        val text: String,
    )

    private var engine: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready: Boolean = false
    private var pending: PendingSpeech? = null

    override fun onInit(status: Int) {
        val current = engine ?: return
        if (status != TextToSpeech.SUCCESS) return

        val localVoice = selectLocalVoice(current) ?: return
        current.voice = localVoice
        current.setSpeechRate(DEFAULT_SPEECH_RATE)
        current.setPitch(DEFAULT_PITCH)
        ready = true

        pending?.also {
            pending = null
            enqueue(current, it.id, it.text)
        }
    }

    fun speak(
        id: String,
        text: String,
    ) {
        if (id.isBlank() || text.isBlank()) return
        val current = engine ?: return
        if (!ready) {
            pending = PendingSpeech(id, text)
            return
        }
        enqueue(current, id, text)
    }

    fun stop() {
        pending = null
        engine?.stop()
    }

    fun close() {
        pending = null
        ready = false
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    private fun enqueue(
        tts: TextToSpeech,
        id: String,
        text: String,
    ) {
        val chunks = speechChunks(text, TextToSpeech.getMaxSpeechInputLength())
        chunks.forEachIndexed { index, chunk ->
            tts.speak(
                chunk,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                "$id:$index",
            )
        }
    }

    private fun selectLocalVoice(tts: TextToSpeech): Voice? {
        val localVoices = tts.voices
            ?.asSequence()
            ?.filterNot(Voice::isNetworkConnectionRequired)
            ?.sortedBy { it.name }
            ?.toList()
            .orEmpty()
        if (localVoices.isEmpty()) return null

        val preferred = Locale.getDefault()
        return localVoices.firstOrNull {
            it.locale.language == preferred.language &&
                (preferred.country.isBlank() || it.locale.country == preferred.country)
        } ?: localVoices.firstOrNull {
            it.locale.language == preferred.language
        } ?: localVoices.firstOrNull {
            it.locale.language == Locale.GERMAN.language
        } ?: localVoices.first()
    }

    internal companion object {
        const val DEFAULT_SPEECH_RATE = 0.96f
        const val DEFAULT_PITCH = 1.0f

        fun speechChunks(
            text: String,
            maxInputLength: Int,
        ): List<String> {
            require(maxInputLength > 64)
            val maxChunk = (maxInputLength - 32).coerceAtLeast(64)
            val normalized = text.trim()
            if (normalized.isEmpty()) return emptyList()
            if (normalized.length <= maxChunk) return listOf(normalized)

            val chunks = mutableListOf<String>()
            var remaining = normalized
            while (remaining.isNotEmpty()) {
                if (remaining.length <= maxChunk) {
                    chunks += remaining
                    break
                }
                val window = remaining.take(maxChunk)
                val split = maxOf(
                    window.lastIndexOf(". "),
                    window.lastIndexOf("! "),
                    window.lastIndexOf("? "),
                    window.lastIndexOf("; "),
                    window.lastIndexOf(", "),
                    window.lastIndexOf(' '),
                ).takeIf { it >= maxChunk / 3 } ?: maxChunk
                val end = if (split < maxChunk) split + 1 else split
                chunks += remaining.take(end).trim()
                remaining = remaining.drop(end).trimStart()
            }
            return chunks.filter(String::isNotBlank)
        }
    }
}
