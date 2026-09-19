package app.lifeos.next

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lifeos.core.language.LanguageContext
import app.lifeos.core.language.LanguageContextItem
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.runtime.chat.ChatEvent
import app.lifeos.core.runtime.chat.ConversationProjector
import app.lifeos.core.runtime.life.LifeOsIntegratedCognitionSuiteRegistry
import app.lifeos.core.runtime.life.LifeOsReadinessSnapshot
import app.lifeos.core.runtime.life.LifeOsSelfValidation
import app.lifeos.core.runtime.life.PerceptionCandidate
import app.lifeos.core.runtime.life.SpeechObservation
import app.lifeos.core.runtime.life.SpeechWordObservation
import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import app.lifeos.core.runtime.topology.LifeOsSubsystemState
import app.lifeos.next.kernel.KernelBootstrapStatus
import app.lifeos.next.ui.chat.ChatImagePreviewLoader
import app.lifeos.next.ui.chat.ChatImagePreviewState
import app.lifeos.next.ui.chat.ChatTimelineItem
import app.lifeos.next.ui.chat.ChatTurnProcessingState
import app.lifeos.next.ui.chat.ChatVoicePhase
import app.lifeos.next.ui.chat.ChatVoicePolicy
import app.lifeos.next.ui.chat.ChatVoiceStartResult
import app.lifeos.next.ui.chat.ChatVoiceUiState
import app.lifeos.next.ui.chat.VoiceDraftResolution
import app.lifeos.next.ui.components.RuntimeTopologyUiEvidence
import app.lifeos.next.ui.components.SelfStateUiEvidence
import app.lifeos.next.ui.perf.StableChatProjection
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LifeOsChatUiState(
    val events: List<ChatEvent> = emptyList(),
    val timeline: List<ChatTimelineItem> = emptyList(),
    val draft: String = "",
    val turnProcessing: ChatTurnProcessingState = ChatTurnProcessingState.idle(),
    val voice: ChatVoiceUiState = ChatVoiceUiState(),
    val bootStatus: KernelBootstrapStatus = KernelBootstrapStatus.CREATED,
    val registeredSubsystems: Int = 0,
    val unavailableSubsystems: Int = 0,
    val capabilityProviders: Int = 0,
    val generatedProviders: Int = 0,
    val runtimeTopology: RuntimeTopologyUiEvidence? = null,
    val selfState: SelfStateUiEvidence? = null,
    val readiness: LifeOsReadinessSnapshot? = null,
    val error: String? = null,
)

class LifeOsChatViewModel(application: Application) : AndroidViewModel(application) {
    private val owner = application as LifeOsApplication
    private val kernel = owner.kernel
    private val multimodalPerception = owner.multimodalPerception
    private val voiceCapture = AndroidVoiceCaptureEngine(application.applicationContext)
    private val voiceStopRequested = AtomicBoolean(false)
    private val imagePreviewLoader = ChatImagePreviewLoader(kernel)
    private val stableChatProjection = StableChatProjection()
    private val mutableState = MutableStateFlow(LifeOsChatUiState())

    private var latestPhotons: List<Photon> = emptyList()
    private var pendingVoiceRecognitions: List<Photon> = emptyList()
    private var stagedVoiceRecognition: Photon? = null

    val state = mutableState.asStateFlow()

    init {
        observeKernel()
        observeSelfState()
    }

    fun editDraft(text: String) {
        if (text.isBlank()) {
            pendingVoiceRecognitions = emptyList()
        }
        val hasAcceptedVoice = pendingVoiceRecognitions.isNotEmpty()
        mutableState.update {
            it.copy(
                draft = text,
                voice = it.voice.copy(
                    voiceDraftBaseline = if (hasAcceptedVoice) it.voice.voiceDraftBaseline else null,
                    voiceInputEdited = if (hasAcceptedVoice) {
                        it.voice.voiceDraftBaseline?.let { baseline -> text != baseline } ?: true
                    } else {
                        false
                    },
                ),
            )
        }
    }

    fun dismissError() {
        mutableState.update { current ->
            current.copy(
                error = null,
                turnProcessing = if (current.turnProcessing.failureMessage != null) {
                    ChatTurnProcessingState.idle()
                } else {
                    current.turnProcessing
                },
            )
        }
    }

    fun beginVoiceCapture(): ChatVoiceStartResult {
        val current = mutableState.value
        if (!ChatVoicePolicy.canStartVoice(current.bootStatus, current.turnProcessing, current.voice)) {
            return ChatVoiceStartResult.BLOCKED
        }
        if (!voiceCapture.hasPermission()) {
            return ChatVoiceStartResult.PERMISSION_REQUIRED
        }
        startVoiceCapture(current)
        return ChatVoiceStartResult.STARTED
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        if (!granted) {
            mutableState.update {
                it.copy(
                    voice = it.voice.copy(
                        phase = ChatVoicePhase.IDLE,
                        status = "Mikrofonberechtigung wurde nicht erteilt.",
                        draftAtCaptureStart = null,
                    )
                )
            }
            return
        }
        when (beginVoiceCapture()) {
            ChatVoiceStartResult.STARTED -> Unit
            ChatVoiceStartResult.PERMISSION_REQUIRED -> mutableState.update {
                it.copy(voice = it.voice.copy(status = "Mikrofonberechtigung ist weiterhin nicht verfügbar."))
            }
            ChatVoiceStartResult.BLOCKED -> mutableState.update {
                it.copy(voice = it.voice.copy(status = "Sprachaufnahme ist im aktuellen Zustand nicht verfügbar."))
            }
        }
    }

    fun stopVoiceCapture() {
        val current = mutableState.value
        if (current.voice.phase != ChatVoicePhase.RECORDING) return
        voiceStopRequested.set(true)
        mutableState.update {
            it.copy(
                voice = it.voice.copy(
                    phase = ChatVoicePhase.PROCESSING,
                    status = "Sprachfeld wird lokal ausgewertet …",
                )
            )
        }
    }

    fun acceptStagedVoiceTranscript() {
        val current = mutableState.value
        val transcript = current.voice.stagedTranscript ?: return
        val recognition = stagedVoiceRecognition ?: return
        val merged = ChatVoicePolicy.mergeDraft(current.draft, transcript)
        pendingVoiceRecognitions = addRecognition(pendingVoiceRecognitions, recognition)
        stagedVoiceRecognition = null
        mutableState.update {
            it.copy(
                draft = merged,
                voice = it.voice.copy(
                    stagedTranscript = null,
                    voiceDraftBaseline = merged,
                    voiceInputEdited = true,
                    status = "Sprachtext wurde in den Entwurf übernommen.",
                )
            )
        }
    }

    fun discardStagedVoiceTranscript() {
        if (mutableState.value.voice.stagedTranscript == null) return
        stagedVoiceRecognition = null
        mutableState.update {
            it.copy(
                voice = it.voice.copy(
                    stagedTranscript = null,
                    status = "Sprachtext wurde verworfen.",
                )
            )
        }
    }

    fun sendMessage() {
        val current = mutableState.value
        val text = current.draft.trim()
        if (!ChatVoicePolicy.canSend(text, current.bootStatus, current.turnProcessing, current.voice)) return

        val turnId = UUID.randomUUID().toString()
        val conversationTag = "${ConversationProjector.CONVERSATION_PREFIX}${ConversationProjector.DEFAULT_CONVERSATION_ID}"
        val turnTag = "${ConversationProjector.TURN_PREFIX}$turnId"
        val voiceRecognitions = pendingVoiceRecognitions.toList()
        val recognitionParentIds = voiceRecognitions.map { it.id }.toSet()
        val userPhoton = Photon(
            content = text,
            provenance = Provenance(
                source = if (recognitionParentIds.isEmpty()) "lifeos-chat" else "lifeos-chat:speech-derived",
                actor = "user",
                parentIds = recognitionParentIds,
            ),
            relations = recognitionParentIds.mapTo(linkedSetOf()) { parentId ->
                PhotonRelation(
                    target = parentId,
                    type = RelationType.DERIVED_FROM,
                )
            },
            tags = buildSet {
                add("chat")
                add("chat:user")
                add(conversationTag)
                add(turnTag)
                if (recognitionParentIds.isNotEmpty()) {
                    add("perception-derived-utterance")
                    add(if (current.voice.voiceInputEdited) "input:speech-edited" else "input:speech")
                }
            },
        )

        pendingVoiceRecognitions = emptyList()
        stagedVoiceRecognition = null
        mutableState.update {
            it.copy(
                draft = "",
                turnProcessing = ChatTurnProcessingState.submitting(turnId),
                voice = it.voice.copy(
                    phase = ChatVoicePhase.IDLE,
                    draftAtCaptureStart = null,
                    stagedTranscript = null,
                    voiceDraftBaseline = null,
                    voiceInputEdited = false,
                ),
                error = null,
            )
        }

        viewModelScope.launch {
            var userTurnPersisted = false
            try {
                val turn = kernel.submitConversationTurn(userPhoton)
                userTurnPersisted = true
                mutableState.update {
                    it.copy(
                        turnProcessing = ChatTurnProcessingState.persistingResponse(turnId),
                    )
                }

                mutableState.update { state ->
                    state.copy(
                        turnProcessing = ChatTurnProcessingState.idle(),
                        error = turn.assistant.processingFailure ?: state.error,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val failure = error.message ?: error::class.simpleName ?: "Chat-Verarbeitung fehlgeschlagen"
                mutableState.update {
                    it.copy(
                        turnProcessing = ChatTurnProcessingState.failed(
                            turnId = turnId,
                            userTurnPersisted = userTurnPersisted,
                            message = failure,
                        ),
                        error = if (userTurnPersisted) {
                            "Deine Nachricht ist gespeichert, aber die LIFEOS-Antwort konnte nicht abgeschlossen werden: $failure"
                        } else {
                            "Die Nachricht konnte nicht gespeichert werden: $failure"
                        },
                    )
                }
            }
        }
    }

    suspend fun loadChatImagePreview(photon: Photon): ChatImagePreviewState =
        imagePreviewLoader.load(photon)

    private fun startVoiceCapture(current: LifeOsChatUiState) {
        voiceStopRequested.set(false)
        val languageContext = buildVoiceLanguageContext(latestPhotons)
        mutableState.update {
            it.copy(
                voice = it.voice.copy(
                    phase = ChatVoicePhase.RECORDING,
                    status = "Lokale Sprachaufnahme läuft …",
                    draftAtCaptureStart = current.draft,
                    stagedTranscript = null,
                ),
                error = null,
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                voiceCapture.capture(voiceStopRequested, languageContext)
            }
            applyVoiceCaptureResult(result)
        }
    }

    private suspend fun applyVoiceCaptureResult(result: LocalVoiceCaptureResult) {
        if (result !is LocalVoiceCaptureResult.Success) {
            mutableState.update { current ->
                current.copy(
                    voice = when (result) {
                        LocalVoiceCaptureResult.NoSpeech -> current.voice.copy(
                            phase = ChatVoicePhase.IDLE,
                            status = "Keine ausreichend stabile Sprache im lokalen Akustikfeld erkannt.",
                            draftAtCaptureStart = null,
                        )
                        LocalVoiceCaptureResult.PermissionMissing -> current.voice.copy(
                            phase = ChatVoicePhase.IDLE,
                            status = "Mikrofonberechtigung fehlt.",
                            draftAtCaptureStart = null,
                        )
                        is LocalVoiceCaptureResult.Failed -> current.voice.copy(
                            phase = ChatVoicePhase.IDLE,
                            status = result.message,
                            draftAtCaptureStart = null,
                        )
                        is LocalVoiceCaptureResult.Success -> current.voice
                    }
                )
            }
            return
        }

        val recognition = try {
            multimodalPerception.observeSpeech(
                observation = buildSpeechObservation(result),
                sampleRateHz = AndroidVoiceCaptureEngine.SAMPLE_RATE_HZ,
                capturedMillis = result.capturedMillis,
            ).recognition?.photon
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }

        if (recognition == null) {
            mutableState.update {
                it.copy(
                    voice = it.voice.copy(
                        phase = ChatVoicePhase.IDLE,
                        status = "Sprache wurde erkannt, aber die Photon-Lineage konnte nicht sicher persistiert werden.",
                        draftAtCaptureStart = null,
                    )
                )
            }
            return
        }

        val current = mutableState.value
        val captureDraft = current.voice.draftAtCaptureStart ?: current.draft
        val resolution = ChatVoicePolicy.resolveTranscript(
            draftAtCaptureStart = captureDraft,
            currentDraft = current.draft,
            transcript = result.transcript,
        )
        val confidence = result.words.map { it.confidence }.average()
        val status = buildString {
            append("Lokales Sprachfeld: ")
            append(result.words.size).append(" Wortkandidat(en), ")
            append(result.segmentCount).append(" Sprachsegment(e), ")
            append("Konfidenz ").append("%.0f".format(confidence * 100.0)).append(" %")
            if (result.stoppedByLimit) append(" · 20-s-Limit erreicht")
            append(" · Observation & Recognition als Photonen persistiert")
        }

        when (resolution) {
            is VoiceDraftResolution.Applied -> {
                val mixedWithTypedDraft = captureDraft.isNotBlank() &&
                    (
                        pendingVoiceRecognitions.isEmpty() ||
                            current.voice.voiceDraftBaseline != captureDraft ||
                            current.voice.voiceInputEdited
                        )
                pendingVoiceRecognitions = addRecognition(pendingVoiceRecognitions, recognition)
                stagedVoiceRecognition = null
                mutableState.value = current.copy(
                    draft = resolution.draft,
                    voice = current.voice.copy(
                        phase = ChatVoicePhase.IDLE,
                        status = status,
                        draftAtCaptureStart = null,
                        stagedTranscript = null,
                        voiceDraftBaseline = resolution.draft,
                        voiceInputEdited = current.voice.voiceInputEdited || mixedWithTypedDraft,
                    ),
                )
            }
            is VoiceDraftResolution.Staged -> {
                stagedVoiceRecognition = recognition
                mutableState.value = current.copy(
                    voice = current.voice.copy(
                        phase = ChatVoicePhase.IDLE,
                        status = "$status · Entwurf wurde zwischenzeitlich geändert; Sprachtext wartet auf Freigabe.",
                        draftAtCaptureStart = null,
                        stagedTranscript = resolution.transcript,
                    ),
                )
            }
        }
    }

    private fun buildSpeechObservation(result: LocalVoiceCaptureResult.Success): SpeechObservation = SpeechObservation(
        sourceId = "android-microphone",
        observedAt = result.observedAt,
        observedUntil = result.observedUntil,
        words = result.words.mapIndexed { index, word ->
            val candidates = buildList {
                add(PerceptionCandidate(word.canonical, word.confidence, word.semanticTag))
                word.alternatives.forEach { (value, confidence) ->
                    if (value != word.canonical) add(PerceptionCandidate(value, confidence))
                }
            }.distinctBy { candidate -> candidate.value to candidate.semanticTag }
            SpeechWordObservation(index = index, candidates = candidates)
        },
        tags = setOf(
            "conversation:default",
            "input:speech",
            "privacy:local-only",
        ),
    )

    private fun buildVoiceLanguageContext(photons: List<Photon>): LanguageContext {
        val eligible = photons
            .asSequence()
            .filter(::voiceContextEligible)
            .sortedWith(compareByDescending<Photon> { it.provenance.createdAt }.thenByDescending { it.id.value })
            .take(MAX_VOICE_CONTEXT_PHOTONS)
            .toList()
        return LanguageContext(
            items = eligible.mapIndexed { index, photon ->
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
    }

    private fun voiceContextEligible(photon: Photon): Boolean =
        "goal" !in photon.tags &&
            "scene-graph" !in photon.tags &&
            "tool-request" !in photon.tags &&
            "tool-generation-approval" !in photon.tags &&
            "perception" !in photon.tags &&
            "perception-raw-source" !in photon.tags

    private fun addRecognition(existing: List<Photon>, recognition: Photon): List<Photon> =
        (existing.filterNot { it.id == recognition.id } + recognition)

    private fun observeKernel() {
        viewModelScope.launch {
            kernel.bootstrapState.collect { boot ->
                latestPhotons = boot.photons
                val topology = if (boot.ready) LifeOsProcessTopology.snapshot() else null
                val topologyEvidence = topology?.let { snapshot ->
                    RuntimeTopologyUiEvidence(
                        observed = true,
                        registeredSubsystems = snapshot.registeredSubsystemCount,
                        operationalSubsystems = snapshot.operationalSubsystemCount,
                        unavailableSubsystems = snapshot.unavailableSubsystems.size,
                        unboundSubsystems = snapshot.unboundSubsystems.size,
                        degradedSubsystems = snapshot.subsystems.count { it.state == LifeOsSubsystemState.DEGRADED },
                        capabilityProviders = snapshot.capabilityProviderCount,
                        generatedProviders = snapshot.generatedProviderCount,
                        fullyConnected = snapshot.fullyConnected,
                        fullyOperational = snapshot.fullyOperational,
                    )
                }
                val readiness = if (boot.ready && mutableState.value.readiness == null) {
                    LifeOsIntegratedCognitionSuiteRegistry.current()?.let { suite ->
                        LifeOsSelfValidation(suite).validate().first
                    }
                } else {
                    mutableState.value.readiness
                }
                val chatProjection = stableChatProjection.project(boot.photons)
                mutableState.update { current ->
                    current.copy(
                        events = chatProjection.events,
                        timeline = chatProjection.timeline,
                        bootStatus = boot.status,
                        registeredSubsystems = topology?.registeredSubsystemCount ?: 0,
                        unavailableSubsystems = topology?.unavailableSubsystems?.size ?: 0,
                        capabilityProviders = topology?.capabilityProviderCount ?: 0,
                        generatedProviders = topology?.generatedProviderCount ?: 0,
                        runtimeTopology = topologyEvidence,
                        readiness = readiness,
                        error = boot.failureMessage ?: current.error,
                    )
                }
            }
        }
    }

    private fun observeSelfState() {
        viewModelScope.launch {
            owner.selfObservationAnalysis.collect { analysis ->
                mutableState.update { current ->
                    current.copy(
                        selfState = analysis?.let { observed ->
                            val snapshot = observed.cycle.snapshot
                            SelfStateUiEvidence(
                                authorityFingerprintShort = snapshot.authorityFingerprint.take(16),
                                worldRevision = snapshot.world.worldHeadRevision,
                                equationVersion = snapshot.world.worldEquationVersion,
                                photonCount = snapshot.photon.livePhotonCount,
                                memoryNodeCount = snapshot.memory.graphNodeCount,
                                healthyNodes = snapshot.health.healthy,
                                degradedNodes = snapshot.health.degraded,
                                unknownHealthNodes = snapshot.health.unknown,
                                resourceCapabilityReadiness = snapshot.resource.capabilityReadiness,
                                activeRepairs = snapshot.recovery.activeRepairs,
                                activeTools = snapshot.tools.activeTools,
                                liveSources = snapshot.liveSources.sourceCount,
                                observationBand = observed.assessment.band.name,
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onCleared() {
        voiceStopRequested.set(true)
        imagePreviewLoader.clear()
        stableChatProjection.clear()
        super.onCleared()
    }

    private companion object {
        const val MAX_VOICE_CONTEXT_PHOTONS = 24
        const val ACTIVE_VOICE_CONTEXT_PHOTONS = 6
        const val MAX_TERMS_PER_PHOTON = 32
        val CONTEXT_TERM_REGEX = Regex("[\\p{L}\\p{N}]+")
    }
}