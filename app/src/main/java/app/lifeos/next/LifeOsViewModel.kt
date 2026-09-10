package app.lifeos.next

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lifeos.core.image.ImageAssetDescriptor
import app.lifeos.core.image.ImagePhotonFactory
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.LanguageContext
import app.lifeos.core.language.LanguageContextItem
import app.lifeos.core.model.Photon
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.capability.CapabilityGap
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatus
import app.lifeos.core.runtime.goal.LocalSharePreparation
import app.lifeos.next.kernel.AndroidLocalReminderScheduler
import app.lifeos.next.kernel.GoalResumeExecutionResult
import app.lifeos.next.kernel.ImageGenerationResult
import app.lifeos.next.kernel.KernelBootstrapStatus
import app.lifeos.next.kernel.LocalCommunicationExecutionResult
import app.lifeos.next.kernel.LocalDeepSearchExecutionResult
import app.lifeos.next.kernel.LocalKnowledgeExecutionResult
import app.lifeos.next.kernel.LocalScheduleActionExecutor
import app.lifeos.next.kernel.LocalScheduleExecutionResult
import app.lifeos.next.kernel.LocalShareIntentFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VoiceCapturePhase { IDLE, RECORDING, PROCESSING }

data class LifeOsState(
    val photons: List<Photon> = emptyList(),
    val draft: String = "",
    val loading: Boolean = true,
    val saving: Boolean = false,
    val loadFailed: Boolean = false,
    val unreadable: Int = 0,
    val error: String? = null,
    val lastGoal: GoalFrame? = null,
    val lastCapabilityGaps: List<CapabilityGap> = emptyList(),
    val capabilityRequestSaving: Boolean = false,
    val capabilityRequestStatus: String? = null,
    val generatedToolStatus: GeneratedToolRuntimeStatus? = null,
    val generatedToolStatusLoading: Boolean = false,
    val generatedToolStatusError: String? = null,
    val pendingShare: LocalSharePreparation? = null,
    val shareStatus: String? = null,
    val voicePhase: VoiceCapturePhase = VoiceCapturePhase.IDLE,
    val voiceStatus: String? = null,
)

data class ImagePreview(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val rendererId: String,
)

sealed interface ImagePreviewState {
    data object Loading : ImagePreviewState
    data class Ready(val preview: ImagePreview) : ImagePreviewState
    data class Failed(val message: String) : ImagePreviewState
}

class LifeOsViewModel(application: Application) : AndroidViewModel(application) {
    private val owner = application as LifeOsApplication
    private val kernel = owner.kernel
    private val generatedToolStatusReader = owner.generatedToolStatusReader
    private val voiceCapture = AndroidVoiceCaptureEngine(application.applicationContext)
    private val localScheduleExecutor = LocalScheduleActionExecutor(
        AndroidLocalReminderScheduler(application.applicationContext)
    )
    private val localShareIntentFactory = LocalShareIntentFactory(application.applicationContext, kernel)
    private val voiceStopRequested = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(LifeOsState())
    private val previewCache = object : LruCache<String, Bitmap>(IMAGE_PREVIEW_CACHE_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    val state = mutableState.asStateFlow()
    val runtimeState = kernel.runtime.state
    val matrixState = kernel.matrix.state

    init {
        observeKernel()
    }

    fun editDraft(text: String) {
        val current = mutableState.value
        if (!current.saving && current.voicePhase != VoiceCapturePhase.PROCESSING) {
            mutableState.update { it.copy(draft = text) }
        }
    }

    fun retryLoad() {
        val current = mutableState.value
        if (!current.loadFailed || current.loading) return

        mutableState.update {
            it.copy(
                loading = true,
                loadFailed = false,
                error = null,
            )
        }
        kernel.retryBootstrap()
    }

    fun dismissError() {
        mutableState.update { it.copy(error = null) }
    }

    fun notificationPermissionDenied() {
        mutableState.update {
            it.copy(error = "Benachrichtigungsberechtigung fehlt. Ohne sie kann LIFEOS keine lokale Erinnerung anzeigen.")
        }
    }

    fun refreshGeneratedToolStatus() {
        if (mutableState.value.generatedToolStatusLoading) return
        viewModelScope.launch { loadGeneratedToolStatus() }
    }

    fun requestCapabilityGaps() {
        val current = mutableState.value
        val gaps = current.lastCapabilityGaps
        if (
            current.loading ||
            current.loadFailed ||
            current.capabilityRequestSaving ||
            gaps.isEmpty()
        ) return

        mutableState.update {
            it.copy(
                capabilityRequestSaving = true,
                capabilityRequestStatus = null,
            )
        }
        viewModelScope.launch {
            try {
                val results = gaps.map { gap ->
                    kernel.persistAndIngest(
                        Photon(
                            content = gap.toToolRequestContent(),
                            provenance = Provenance("local-capability-gap-request", "user"),
                            tags = setOf("capability-gap", "tool-request", "user-approved"),
                        )
                    )
                }
                val allQueued = results.all { it.processingQueued }
                mutableState.update {
                    it.copy(
                        capabilityRequestStatus = if (allQueued) {
                            "${gaps.size} Tool-Anforderung(en) wurden lokal gespeichert und dauerhaft zur Verarbeitung eingereiht."
                        } else {
                            "Die Tool-Anforderung wurde lokal gespeichert, konnte aber nicht vollständig zur Verarbeitung eingereiht werden."
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(capabilityRequestStatus = "Tool-Anforderung konnte nicht gespeichert werden.")
                }
            } finally {
                mutableState.update { it.copy(capabilityRequestSaving = false) }
            }
        }
    }

    fun dismissCapabilityRequestStatus() {
        mutableState.update { it.copy(capabilityRequestStatus = null) }
    }

    fun voicePermissionDenied() {
        mutableState.update {
            it.copy(
                voicePhase = VoiceCapturePhase.IDLE,
                voiceStatus = "Mikrofonberechtigung wurde nicht erteilt.",
            )
        }
    }

    fun startVoiceCapture() {
        val current = mutableState.value
        if (current.loading || current.loadFailed || current.saving || current.voicePhase != VoiceCapturePhase.IDLE) return
        if (!voiceCapture.hasPermission()) {
            voicePermissionDenied()
            return
        }

        voiceStopRequested.set(false)
        val context = buildLanguageContext(current.photons)
        mutableState.update {
            it.copy(
                voicePhase = VoiceCapturePhase.RECORDING,
                voiceStatus = "Lokale Sprachaufnahme läuft …",
                error = null,
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result = voiceCapture.capture(voiceStopRequested, context)
            mutableState.update { state -> applyVoiceResult(state, result) }
        }
    }

    fun stopVoiceCapture() {
        if (mutableState.value.voicePhase != VoiceCapturePhase.RECORDING) return
        voiceStopRequested.set(true)
        mutableState.update {
            it.copy(
                voicePhase = VoiceCapturePhase.PROCESSING,
                voiceStatus = "Sprachfeld wird lokal ausgewertet …",
            )
        }
    }

    fun dismissVoiceStatus() {
        mutableState.update { it.copy(voiceStatus = null) }
    }

    suspend fun createShareIntent(share: LocalSharePreparation): Intent =
        localShareIntentFactory.create(share)

    fun communicationShareOpened(share: LocalSharePreparation) {
        if (mutableState.value.pendingShare != share) return
        mutableState.update { it.copy(pendingShare = null, shareStatus = "Android-Teilen wurde geöffnet.") }
        viewModelScope.launch {
            val receipt = kernel.recordCommunicationHandoff(share)
            if (!receipt.processingQueued) {
                mutableState.update {
                    it.copy(shareStatus = "Android-Teilen wurde geöffnet; der lokale Handoff-Beleg konnte aber nicht vollständig eingereiht werden.")
                }
            }
        }
    }

    fun communicationShareFailed(share: LocalSharePreparation) {
        if (mutableState.value.pendingShare != share) return
        mutableState.update {
            it.copy(
                pendingShare = null,
                error = "Das Android-Teilen konnte nicht geöffnet werden.",
            )
        }
    }

    fun dismissShareStatus() {
        mutableState.update { it.copy(shareStatus = null) }
    }

    fun saveDraft() {
        val current = mutableState.value
        if (current.loading || current.loadFailed || current.saving || current.voicePhase != VoiceCapturePhase.IDLE || current.draft.isBlank()) return

        val photon = Photon(
            content = current.draft.trim(),
            provenance = Provenance("local-chat", "user"),
            tags = setOf("chat"),
        )
        mutableState.update { it.copy(saving = true, error = null) }

        viewModelScope.launch {
            try {
                val baseResult = kernel.persistUserUtterance(photon)
                val schedule = localScheduleExecutor.execute(kernel, baseResult)
                val result = if (schedule == null) baseResult else baseResult.copy(localSchedule = schedule)
                val retainDraft = result.localSchedule is LocalScheduleExecutionResult.Blocked
                mutableState.update {
                    it.copy(
                        draft = if (retainDraft) photon.content else "",
                        lastGoal = result.effectiveGoal ?: it.lastGoal,
                        lastCapabilityGaps = result.effectiveRouting?.blockingGaps.orEmpty(),
                        capabilityRequestStatus = null,
                        pendingShare = (result.localCommunication as? LocalCommunicationExecutionResult.Prepared)?.share,
                        shareStatus = null,
                        error = when {
                            result.languageFailure != null ->
                                "Gedanke wurde gespeichert, aber das lokale Sprachverständnis ist fehlgeschlagen."
                            !result.source.processingQueued ->
                                "Gedanke wurde gespeichert, konnte aber nicht zur Verarbeitung eingereiht werden."
                            result.goal?.processingQueued != true ->
                                "Gedanke wurde verstanden und gespeichert, das abgeleitete Ziel konnte aber nicht zur Verarbeitung eingereiht werden."
                            result.goalResume is GoalResumeExecutionResult.Blocked ->
                                "Das vorherige Ziel konnte nicht sicher fortgesetzt werden: ${result.goalResume.message}"
                            result.goalResume is GoalResumeExecutionResult.Failed ->
                                "Das vorherige Ziel konnte nicht fortgesetzt werden: ${result.goalResume.message}"
                            result.goalResume is GoalResumeExecutionResult.Resumed &&
                                !result.goalResume.resumedGoal.processingQueued ->
                                "Das vorherige Ziel wurde wiederaufgenommen und gespeichert, konnte aber nicht dauerhaft zur Verarbeitung eingereiht werden."
                            result.localKnowledge is LocalKnowledgeExecutionResult.Failed ->
                                "Die lokale Wissensaktion ist fehlgeschlagen: ${result.localKnowledge.message}"
                            result.localKnowledge is LocalKnowledgeExecutionResult.Produced &&
                                !result.localKnowledge.output.processingQueued ->
                                "Die lokale Wissensantwort wurde gespeichert, konnte aber nicht dauerhaft zur Verarbeitung eingereiht werden."
                            result.localDeepSearch is LocalDeepSearchExecutionResult.Failed ->
                                "Die lokale DeepSearch-Suche ist fehlgeschlagen: ${result.localDeepSearch.message}"
                            result.localDeepSearch is LocalDeepSearchExecutionResult.Produced &&
                                !result.localDeepSearch.output.processingQueued ->
                                "Das lokale DeepSearch-Ergebnis wurde gespeichert, konnte aber nicht dauerhaft zur Verarbeitung eingereiht werden."
                            result.localSchedule is LocalScheduleExecutionResult.Blocked -> when (result.localSchedule.reason) {
                                "notification-permission-required" ->
                                    "Für lokale Erinnerungen muss die Benachrichtigungsberechtigung erteilt werden."
                                "reminder-time-missing" ->
                                    "Für die Erinnerung fehlt eine Uhrzeit. Der Entwurf bleibt zum Ergänzen erhalten."
                                else -> "Die lokale Erinnerung konnte nicht geplant werden: ${result.localSchedule.reason}"
                            }
                            result.localSchedule is LocalScheduleExecutionResult.Failed ->
                                "Die lokale Erinnerung ist fehlgeschlagen: ${result.localSchedule.message}"
                            result.localSchedule is LocalScheduleExecutionResult.Scheduled &&
                                !result.localSchedule.output.processingQueued ->
                                "Die Erinnerung wurde lokal geplant, ihr Photon konnte aber nicht dauerhaft zur Verarbeitung eingereiht werden."
                            result.localCommunication is LocalCommunicationExecutionResult.Blocked ->
                                "Es gibt kein eindeutig teilbares lokales Ergebnis."
                            result.localCommunication is LocalCommunicationExecutionResult.Failed ->
                                "Das lokale Teilen konnte nicht vorbereitet werden: ${result.localCommunication.message}"
                            result.imageGeneration is ImageGenerationResult.Blocked ->
                                "Das Bildziel wurde verstanden, kann mit den lokalen Fähigkeiten aber noch nicht vollständig ausgeführt werden."
                            result.imageGeneration is ImageGenerationResult.Failed ->
                                "Das Bild konnte lokal nicht erzeugt werden: ${result.imageGeneration.message}"
                            else -> null
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        error = "Gedanke konnte nicht gespeichert werden. Die Eingabe bleibt im Textfeld.",
                    )
                }
            } finally {
                mutableState.update { it.copy(saving = false) }
            }
        }
    }

    suspend fun loadImagePreview(photon: Photon): ImagePreviewState {
        if (photon.mimeType != ImagePhotonFactory.IMAGE_REFERENCE_MIME) {
            return ImagePreviewState.Failed("Photon ist keine Bildreferenz.")
        }
        val descriptor = runCatching { ImageAssetDescriptor.decode(photon.content) }.getOrElse {
            return ImagePreviewState.Failed("Bildreferenz ist beschädigt.")
        }
        val cacheKey = "${photon.id.value}:${photon.revision}:${descriptor.asset.sha256}"
        previewCache.get(cacheKey)?.let { bitmap ->
            return ImagePreviewState.Ready(
                ImagePreview(bitmap, descriptor.width, descriptor.height, descriptor.rendererId),
            )
        }

        return try {
            val bytes = kernel.loadImageAsset(photon)
                ?: return ImagePreviewState.Failed("Verschlüsseltes Bild-Asset fehlt oder ist nicht lesbar.")
            val bitmap = withContext(Dispatchers.Default) {
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                )
            } ?: return ImagePreviewState.Failed("PNG konnte lokal nicht dekodiert werden.")

            if (bitmap.width != descriptor.width || bitmap.height != descriptor.height) {
                bitmap.recycle()
                return ImagePreviewState.Failed("Bildabmessungen stimmen nicht mit der Photon-Referenz überein.")
            }
            previewCache.put(cacheKey, bitmap)
            ImagePreviewState.Ready(
                ImagePreview(bitmap, descriptor.width, descriptor.height, descriptor.rendererId),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ImagePreviewState.Failed("Bild konnte nicht aus dem lokalen Asset-Vault geladen werden.")
        }
    }

    private suspend fun loadGeneratedToolStatus() {
        mutableState.update { it.copy(generatedToolStatusLoading = true, generatedToolStatusError = null) }
        try {
            val status = withContext(Dispatchers.IO) { generatedToolStatusReader.snapshot() }
            mutableState.update {
                it.copy(
                    generatedToolStatus = status,
                    generatedToolStatusError = null,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update {
                it.copy(generatedToolStatusError = "Generated-Tool-Status konnte nicht gelesen werden.")
            }
        } finally {
            mutableState.update { it.copy(generatedToolStatusLoading = false) }
        }
    }

    private fun CapabilityGap.toToolRequestContent(): String = buildString {
        appendLine("LIFEOS_CAPABILITY_GAP_REQUEST_V1")
        append("capability=").appendLine(requirement.capabilityId.value)
        append("severity=").appendLine(requirement.severity.name)
        append("gapType=").appendLine(type.name)
        append("requiredInputs=").appendLine(requirement.requiredInputs.sorted().joinToString(","))
        append("requiredOutputs=").appendLine(requirement.requiredOutputs.sorted().joinToString(","))
        append("candidateProviders=").append(candidateProviderIds.sorted().joinToString(","))
    }

    private fun applyVoiceResult(state: LifeOsState, result: LocalVoiceCaptureResult): LifeOsState = when (result) {
        is LocalVoiceCaptureResult.Success -> {
            val transcript = result.transcript.trim()
            val combinedDraft = when {
                transcript.isBlank() -> state.draft
                state.draft.isBlank() -> transcript
                else -> "${state.draft.trimEnd()} $transcript"
            }
            val confidence = result.words.map { it.confidence }.average()
            state.copy(
                draft = combinedDraft,
                voicePhase = VoiceCapturePhase.IDLE,
                voiceStatus = buildString {
                    append("Lokales Sprachfeld: ")
                    append(result.words.size).append(" Wortkandidat(en), ")
                    append(result.segmentCount).append(" Sprachsegment(e), ")
                    append("Konfidenz ").append("%.0f".format(confidence * 100.0)).append(" %")
                    if (result.stoppedByLimit) append(" · 20-s-Limit erreicht")
                },
            )
        }
        LocalVoiceCaptureResult.NoSpeech -> state.copy(
            voicePhase = VoiceCapturePhase.IDLE,
            voiceStatus = "Keine ausreichend stabile Sprache im lokalen Akustikfeld erkannt.",
        )
        LocalVoiceCaptureResult.PermissionMissing -> state.copy(
            voicePhase = VoiceCapturePhase.IDLE,
            voiceStatus = "Mikrofonberechtigung fehlt.",
        )
        is LocalVoiceCaptureResult.Failed -> state.copy(
            voicePhase = VoiceCapturePhase.IDLE,
            voiceStatus = result.message,
        )
    }

    private fun buildLanguageContext(photons: List<Photon>): LanguageContext = LanguageContext(
        items = photons.take(MAX_VOICE_CONTEXT_PHOTONS).mapIndexed { index, photon ->
            LanguageContextItem(
                photonId = photon.id,
                kind = photon.mimeType,
                tags = photon.tags,
                createdAt = photon.provenance.createdAt,
                active = index < ACTIVE_VOICE_CONTEXT_PHOTONS,
                contentTerms = CONTEXT_TERM_REGEX.findAll(photon.content)
                    .map { it.value.lowercase() }
                    .filter { it.length >= 2 }
                    .take(MAX_TERMS_PER_PHOTON)
                    .toSet(),
                confidence = photon.confidence,
            )
        },
    )

    private fun observeKernel() {
        viewModelScope.launch {
            kernel.bootstrapState.collect { bootstrap ->
                mutableState.update { current ->
                    val loadError = bootstrap.status == KernelBootstrapStatus.FAILED
                    current.copy(
                        photons = bootstrap.photons
                            .filter { it.isUserVisiblePhoton() }
                            .asReversed(),
                        loading = bootstrap.status == KernelBootstrapStatus.CREATED ||
                            bootstrap.status == KernelBootstrapStatus.LOADING,
                        loadFailed = loadError,
                        unreadable = bootstrap.unreadableFiles,
                        error = when {
                            loadError -> LOAD_ERROR_MESSAGE
                            current.error == LOAD_ERROR_MESSAGE -> null
                            else -> current.error
                        },
                    )
                }
                if (
                    bootstrap.status == KernelBootstrapStatus.READY ||
                    bootstrap.status == KernelBootstrapStatus.DEGRADED
                ) {
                    loadGeneratedToolStatus()
                }
            }
        }
    }

    override fun onCleared() {
        voiceStopRequested.set(true)
        previewCache.evictAll()
        super.onCleared()
    }

    private fun Photon.isUserVisiblePhoton(): Boolean =
        "goal" !in tags && "scene-graph" !in tags

    private companion object {
        const val LOAD_ERROR_MESSAGE = "Speicher konnte nicht geladen werden. Bitte erneut versuchen."
        const val IMAGE_PREVIEW_CACHE_KIB = 16 * 1024
        const val MAX_VOICE_CONTEXT_PHOTONS = 24
        const val ACTIVE_VOICE_CONTEXT_PHOTONS = 6
        const val MAX_TERMS_PER_PHOTON = 32
        val CONTEXT_TERM_REGEX = Regex("[\\p{L}\\p{N}]+")
    }
}
