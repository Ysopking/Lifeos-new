package app.lifeos.core.runtime.web

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

@JvmInline
value class WebWatchId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val PREFIX = "web-watch:"
    }
}

enum class WebWatchCycleOutcome {
    INITIALIZED,
    UNCHANGED,
    REPRESENTATION_CHANGED,
    CONTENT_CHANGED,
    RESOURCE_CHANGED,
    NOT_MODIFIED,
    ACQUISITION_FAILED,
}

data class WebWatchDefinition(
    val id: WebWatchId,
    val acquisitionRequest: WebAcquisitionRequest,
    val ingestPolicy: WebIngestPolicy,
    val pollInterval: Duration,
) {
    init {
        require(!pollInterval.isZero && !pollInterval.isNegative)
        require(pollInterval >= MIN_POLL_INTERVAL)
        require(pollInterval <= MAX_POLL_INTERVAL)
        require(id == expectedId())
    }

    val schedulingAuthority: Boolean
        get() = false

    val permissionAuthority: Boolean
        get() = false

    fun fingerprint(): String = watchFingerprint(
        "web-watch-definition/v1",
        acquisitionRequest.id.value,
        ingestPolicy.fingerprint(),
        pollInterval.seconds.toString(),
        pollInterval.nano.toString(),
    )

    private fun expectedId(): WebWatchId =
        WebWatchId(WebWatchId.PREFIX + fingerprint())

    companion object {
        fun create(
            acquisitionRequest: WebAcquisitionRequest,
            ingestPolicy: WebIngestPolicy = WebIngestPolicy(),
            pollInterval: Duration = Duration.ofMinutes(15),
        ): WebWatchDefinition {
            val fingerprint = watchFingerprint(
                "web-watch-definition/v1",
                acquisitionRequest.id.value,
                ingestPolicy.fingerprint(),
                pollInterval.seconds.toString(),
                pollInterval.nano.toString(),
            )
            return WebWatchDefinition(
                id = WebWatchId(WebWatchId.PREFIX + fingerprint),
                acquisitionRequest = acquisitionRequest,
                ingestPolicy = ingestPolicy,
                pollInterval = pollInterval,
            )
        }
    }
}

data class WebWatchState(
    val watchId: WebWatchId,
    val cycleRevision: Long,
    val latestDocument: WebIngestDocument?,
    val lastAcquisitionReceiptFingerprint: String?,
    val lastChangeDetectionFingerprint: String?,
    val fingerprint: String,
) {
    init {
        require(cycleRevision >= 0L)
        lastAcquisitionReceiptFingerprint?.let {
            require(it.matches(Regex("[0-9a-f]{64}")))
        }
        lastChangeDetectionFingerprint?.let {
            require(it.matches(Regex("[0-9a-f]{64}")))
        }
        if (cycleRevision == 0L) {
            require(latestDocument == null)
            require(lastAcquisitionReceiptFingerprint == null)
            require(lastChangeDetectionFingerprint == null)
        } else {
            require(lastAcquisitionReceiptFingerprint != null)
        }
        require(
            fingerprint == stateFingerprint(
                watchId = watchId,
                cycleRevision = cycleRevision,
                latestDocument = latestDocument,
                lastAcquisitionReceiptFingerprint = lastAcquisitionReceiptFingerprint,
                lastChangeDetectionFingerprint = lastChangeDetectionFingerprint,
            )
        )
    }

    companion object {
        fun initial(definition: WebWatchDefinition): WebWatchState {
            val fingerprint = stateFingerprint(
                watchId = definition.id,
                cycleRevision = 0L,
                latestDocument = null,
                lastAcquisitionReceiptFingerprint = null,
                lastChangeDetectionFingerprint = null,
            )
            return WebWatchState(
                watchId = definition.id,
                cycleRevision = 0L,
                latestDocument = null,
                lastAcquisitionReceiptFingerprint = null,
                lastChangeDetectionFingerprint = null,
                fingerprint = fingerprint,
            )
        }
    }
}

data class WebWatchCycleResult(
    val watchId: WebWatchId,
    val previousStateFingerprint: String,
    val acquisitionReceiptFingerprint: String,
    val outcome: WebWatchCycleOutcome,
    val change: WebChangeDetection?,
    val nextState: WebWatchState,
    val fingerprint: String,
) {
    init {
        require(previousStateFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(acquisitionReceiptFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(nextState.watchId == watchId)
        when (outcome) {
            WebWatchCycleOutcome.INITIALIZED,
            WebWatchCycleOutcome.RESOURCE_CHANGED,
            WebWatchCycleOutcome.NOT_MODIFIED,
            WebWatchCycleOutcome.ACQUISITION_FAILED -> require(change == null)

            WebWatchCycleOutcome.UNCHANGED -> require(change?.kind == WebChangeKind.UNCHANGED)
            WebWatchCycleOutcome.REPRESENTATION_CHANGED ->
                require(change?.kind == WebChangeKind.REPRESENTATION_ONLY)
            WebWatchCycleOutcome.CONTENT_CHANGED ->
                require(change?.kind == WebChangeKind.CONTENT_CHANGED)
        }
        require(
            fingerprint == cycleResultFingerprint(
                watchId = watchId,
                previousStateFingerprint = previousStateFingerprint,
                acquisitionReceiptFingerprint = acquisitionReceiptFingerprint,
                outcome = outcome,
                change = change,
                nextState = nextState,
            )
        )
    }

    val truthAuthority: Boolean
        get() = false

    val notificationAuthority: Boolean
        get() = false

    val schedulingAuthority: Boolean
        get() = false

    val permissionAuthority: Boolean
        get() = false
}

/**
 * B398 executes exactly one watch observation cycle.
 *
 * Recurrence remains owned by the existing DurableTaskEngine/TaskScheduler stack. Real network
 * permission remains owned by the existing OwnerPolicyEffectGate around the host transport.
 * B398 only composes B392 acquisition, B393 ingest and B397 change detection into a revisioned
 * watch state. It does not create its own timer loop, permission system, notifications or truth.
 */
class WebWatchRuntime(
    private val acquisitionRuntime: WebAcquisitionRuntime,
    private val ingestor: WebMultiFormatIngestor = WebMultiFormatIngestor(),
    private val changeDetector: WebChangeDetector = WebChangeDetector(),
) {
    suspend fun runOnce(
        definition: WebWatchDefinition,
        previousState: WebWatchState = WebWatchState.initial(definition),
    ): WebWatchCycleResult {
        require(previousState.watchId == definition.id) {
            "Web watch state belongs to another watch definition"
        }

        val acquisition = acquisitionRuntime.acquire(definition.acquisitionRequest)
        val receipt = acquisition.receipt
        val nextRevision = previousState.cycleRevision + 1L

        return when (receipt.outcome) {
            WebAcquisitionOutcome.NOT_MODIFIED -> {
                require(previousState.latestDocument != null) {
                    "Web watch cannot accept NOT_MODIFIED without an initialized document"
                }
                cycleResult(
                    definition = definition,
                    previousState = previousState,
                    acquisitionReceiptFingerprint = receipt.fingerprint,
                    outcome = WebWatchCycleOutcome.NOT_MODIFIED,
                    change = null,
                    latestDocument = previousState.latestDocument,
                    nextRevision = nextRevision,
                )
            }

            WebAcquisitionOutcome.HTTP_ERROR -> cycleResult(
                definition = definition,
                previousState = previousState,
                acquisitionReceiptFingerprint = receipt.fingerprint,
                outcome = WebWatchCycleOutcome.ACQUISITION_FAILED,
                change = null,
                latestDocument = previousState.latestDocument,
                nextRevision = nextRevision,
            )

            WebAcquisitionOutcome.ACQUIRED -> {
                val currentDocument = ingestor.ingest(
                    acquisition = acquisition,
                    policy = definition.ingestPolicy,
                )
                val previousDocument = previousState.latestDocument
                when {
                    previousDocument == null -> cycleResult(
                        definition = definition,
                        previousState = previousState,
                        acquisitionReceiptFingerprint = receipt.fingerprint,
                        outcome = WebWatchCycleOutcome.INITIALIZED,
                        change = null,
                        latestDocument = currentDocument,
                        nextRevision = nextRevision,
                    )

                    previousDocument.resourceId != currentDocument.resourceId -> cycleResult(
                        definition = definition,
                        previousState = previousState,
                        acquisitionReceiptFingerprint = receipt.fingerprint,
                        outcome = WebWatchCycleOutcome.RESOURCE_CHANGED,
                        change = null,
                        latestDocument = currentDocument,
                        nextRevision = nextRevision,
                    )

                    else -> {
                        val change = changeDetector.detect(previousDocument, currentDocument)
                        val outcome = when (change.kind) {
                            WebChangeKind.UNCHANGED -> WebWatchCycleOutcome.UNCHANGED
                            WebChangeKind.REPRESENTATION_ONLY ->
                                WebWatchCycleOutcome.REPRESENTATION_CHANGED
                            WebChangeKind.CONTENT_CHANGED ->
                                WebWatchCycleOutcome.CONTENT_CHANGED
                        }
                        cycleResult(
                            definition = definition,
                            previousState = previousState,
                            acquisitionReceiptFingerprint = receipt.fingerprint,
                            outcome = outcome,
                            change = change,
                            latestDocument = currentDocument,
                            nextRevision = nextRevision,
                        )
                    }
                }
            }
        }
    }

    private fun cycleResult(
        definition: WebWatchDefinition,
        previousState: WebWatchState,
        acquisitionReceiptFingerprint: String,
        outcome: WebWatchCycleOutcome,
        change: WebChangeDetection?,
        latestDocument: WebIngestDocument?,
        nextRevision: Long,
    ): WebWatchCycleResult {
        val stateFp = stateFingerprint(
            watchId = definition.id,
            cycleRevision = nextRevision,
            latestDocument = latestDocument,
            lastAcquisitionReceiptFingerprint = acquisitionReceiptFingerprint,
            lastChangeDetectionFingerprint = change?.fingerprint,
        )
        val nextState = WebWatchState(
            watchId = definition.id,
            cycleRevision = nextRevision,
            latestDocument = latestDocument,
            lastAcquisitionReceiptFingerprint = acquisitionReceiptFingerprint,
            lastChangeDetectionFingerprint = change?.fingerprint,
            fingerprint = stateFp,
        )
        val fingerprint = cycleResultFingerprint(
            watchId = definition.id,
            previousStateFingerprint = previousState.fingerprint,
            acquisitionReceiptFingerprint = acquisitionReceiptFingerprint,
            outcome = outcome,
            change = change,
            nextState = nextState,
        )
        return WebWatchCycleResult(
            watchId = definition.id,
            previousStateFingerprint = previousState.fingerprint,
            acquisitionReceiptFingerprint = acquisitionReceiptFingerprint,
            outcome = outcome,
            change = change,
            nextState = nextState,
            fingerprint = fingerprint,
        )
    }
}

private fun stateFingerprint(
    watchId: WebWatchId,
    cycleRevision: Long,
    latestDocument: WebIngestDocument?,
    lastAcquisitionReceiptFingerprint: String?,
    lastChangeDetectionFingerprint: String?,
): String = watchFingerprint(
    "web-watch-state/v1",
    watchId.value,
    cycleRevision.toString(),
    latestDocument?.fingerprint.orEmpty(),
    lastAcquisitionReceiptFingerprint.orEmpty(),
    lastChangeDetectionFingerprint.orEmpty(),
)

private fun cycleResultFingerprint(
    watchId: WebWatchId,
    previousStateFingerprint: String,
    acquisitionReceiptFingerprint: String,
    outcome: WebWatchCycleOutcome,
    change: WebChangeDetection?,
    nextState: WebWatchState,
): String = watchFingerprint(
    "web-watch-cycle-result/v1",
    watchId.value,
    previousStateFingerprint,
    acquisitionReceiptFingerprint,
    outcome.name,
    change?.fingerprint.orEmpty(),
    nextState.fingerprint,
)

private fun watchFingerprint(
    domain: String,
    vararg parts: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            )
        )
        digest.update(bytes)
    }
    update(domain)
    parts.forEach(::update)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private val MIN_POLL_INTERVAL: Duration = Duration.ofSeconds(1)
private val MAX_POLL_INTERVAL: Duration = Duration.ofDays(30)
