package app.lifeos.core.runtime.boot

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.model.Provenance
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val BOOT_COGNITION_REPORT_SCHEMA_VERSION = 1
private const val BOOT_COGNITION_REPORT_HEADER = "LIFEOS_BOOT_COGNITION_REPORT_V1"

enum class BootCognitionDisposition {
    CLEAN,
    DEGRADED,
    RECOVERY_REQUIRED,
}

enum class BootCognitionEvidenceKind {
    SNAPSHOT,
    INTEGRITY,
    CONTEXT,
    CONTEXT_MISSING_SOURCE,
    DELTA_CONTEXT,
    DELTA_PHOTON,
    DELTA_TASK,
    DELTA_FIELD,
    DELTA_CAPABILITY,
    DELTA_TOOL,
}

data class BootCognitionEvidence(
    val kind: BootCognitionEvidenceKind,
    val key: String,
    val value: String,
) {
    init {
        require(key.isNotBlank()) { "Boot cognition evidence key must not be blank" }
        require(key.length <= 1024) { "Boot cognition evidence key is too long" }
        require(value.isNotBlank()) { "Boot cognition evidence value must not be blank" }
        require(value.length <= 16_384) { "Boot cognition evidence value is too long" }
    }
}

data class BootCognitionReport(
    val schemaVersion: Int = BOOT_COGNITION_REPORT_SCHEMA_VERSION,
    val currentGenerationId: BootGenerationId,
    val previousGenerationId: BootGenerationId?,
    val disposition: BootCognitionDisposition,
    val snapshotPartial: Boolean,
    val sourcePhotonIds: List<PhotonId>,
    val evidence: List<BootCognitionEvidence>,
) {
    init {
        require(schemaVersion == BOOT_COGNITION_REPORT_SCHEMA_VERSION) {
            "Unsupported boot cognition report schema"
        }
        require(sourcePhotonIds == sourcePhotonIds.distinct().sortedBy { it.value }) {
            "Boot cognition source Photon ids must be unique and canonical"
        }
        require(evidence == evidence.distinct().sortedWith(bootCognitionEvidenceComparator)) {
            "Boot cognition evidence must be unique and canonical"
        }
    }

    fun fingerprint(): String = sha256(BootCognitionReportCodec.encode(this))
}

object BootCognitionReportCodec {
    fun encode(report: BootCognitionReport): String = buildString {
        appendLine(BOOT_COGNITION_REPORT_HEADER)
        append("M|")
        append(report.schemaVersion); append('|')
        append(report.currentGenerationId.value); append('|')
        append(report.previousGenerationId?.value ?: "-"); append('|')
        append(report.disposition.name); append('|')
        appendLine(if (report.snapshotPartial) "1" else "0")
        report.sourcePhotonIds.forEach { photonId ->
            append("P|"); appendLine(token(photonId.value))
        }
        report.evidence.forEach { item ->
            append("E|"); append(item.kind.name); append('|')
            append(token(item.key)); append('|')
            appendLine(token(item.value))
        }
    }

    fun decode(encoded: String): BootCognitionReport {
        val lines = encoded.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.firstOrNull() == BOOT_COGNITION_REPORT_HEADER) {
            "Unsupported boot cognition report format"
        }
        val meta = lines.getOrNull(1)?.split('|') ?: error("Missing boot cognition metadata")
        require(meta.size == 6 && meta[0] == "M") { "Malformed boot cognition metadata" }
        val version = meta[1].toInt()
        require(version == BOOT_COGNITION_REPORT_SCHEMA_VERSION) {
            "Unsupported boot cognition report schema"
        }
        val current = BootGenerationId(meta[2])
        val previous = meta[3].takeUnless { it == "-" }?.let(::BootGenerationId)
        val disposition = BootCognitionDisposition.valueOf(meta[4])
        val partial = when (meta[5]) {
            "1" -> true
            "0" -> false
            else -> error("Malformed boot cognition partial flag")
        }

        val sources = mutableListOf<PhotonId>()
        val evidence = mutableListOf<BootCognitionEvidence>()
        lines.drop(2).forEach { line ->
            val parts = line.split('|')
            when (parts.firstOrNull()) {
                "P" -> {
                    require(parts.size == 2) { "Malformed boot cognition source" }
                    sources += PhotonId(untoken(parts[1]))
                }
                "E" -> {
                    require(parts.size == 4) { "Malformed boot cognition evidence" }
                    evidence += BootCognitionEvidence(
                        kind = BootCognitionEvidenceKind.valueOf(parts[1]),
                        key = untoken(parts[2]),
                        value = untoken(parts[3]),
                    )
                }
                else -> error("Unknown boot cognition record")
            }
        }

        return BootCognitionReport(
            schemaVersion = version,
            currentGenerationId = current,
            previousGenerationId = previous,
            disposition = disposition,
            snapshotPartial = partial,
            sourcePhotonIds = sources.distinct().sortedBy { it.value },
            evidence = evidence.distinct().sortedWith(bootCognitionEvidenceComparator),
        )
    }

    private fun token(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun untoken(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8,
    )
}

class BootCognitionReportFactory {
    fun create(
        snapshot: DurableBootSnapshot,
        integrity: BootIntegrityReport,
        context: BootContextRehydrationResult,
        delta: BootDeltaAnalysis,
    ): BootCognitionReport {
        require(integrity.generationId == snapshot.generationId) {
            "Integrity report generation must match boot snapshot"
        }
        require(context.generationId == snapshot.generationId) {
            "Context rehydration generation must match boot snapshot"
        }
        require(delta.currentGenerationId == snapshot.generationId) {
            "Delta analysis generation must match boot snapshot"
        }

        val unresolvedContext = BootContextKind.entries.any { kind ->
            context.resolve(kind).status == BootContextResolutionStatus.UNRESOLVED
        }
        val disposition = when {
            integrity.requiresRecovery -> BootCognitionDisposition.RECOVERY_REQUIRED
            snapshot.partial || !integrity.canProceedNormally || unresolvedContext ||
                context.missingSourcePhotonIds.isNotEmpty() -> BootCognitionDisposition.DEGRADED
            else -> BootCognitionDisposition.CLEAN
        }

        val evidence = buildList {
            add(
                BootCognitionEvidence(
                    kind = BootCognitionEvidenceKind.SNAPSHOT,
                    key = "counts",
                    value = listOf(
                        "photons=${snapshot.photons.size}",
                        "tasks=${snapshot.tasks.size}",
                        "checkpoints=${snapshot.checkpoints.size}",
                        "contexts=${snapshot.contexts.size}",
                        "capabilities=${snapshot.capabilities.size}",
                        "workerLeases=${snapshot.workerLeases.size}",
                        "tools=${snapshot.tools.size}",
                        "fieldSnapshots=${snapshot.fieldSnapshots.size}",
                        "readFailures=${snapshot.readFailures.size}",
                    ).joinToString(";"),
                )
            )
            integrity.findings.forEach { finding ->
                add(
                    BootCognitionEvidence(
                        kind = BootCognitionEvidenceKind.INTEGRITY,
                        key = listOf(finding.code, finding.entityId ?: "-").joinToString(":"),
                        value = listOf(
                            "severity=${finding.severity.name}",
                            "area=${finding.area.name}",
                            "repairability=${finding.repairability.name}",
                            "message=${finding.message}",
                        ).joinToString(";"),
                    )
                )
            }
            context.contexts.forEach { item ->
                add(
                    BootCognitionEvidence(
                        kind = BootCognitionEvidenceKind.CONTEXT,
                        key = "${item.kind.name}:${item.contextId}",
                        value = listOf(
                            "source=${item.sourcePhotonId.value}",
                            "revision=${item.sourcePhotonRevision}",
                            "createdAt=${item.sourceCreatedAt}",
                            "relevant=${item.relevantPhotonIds.joinToString(",") { it.value }}",
                        ).joinToString(";"),
                    )
                )
            }
            context.missingSourcePhotonIds.forEach { id ->
                add(
                    BootCognitionEvidence(
                        kind = BootCognitionEvidenceKind.CONTEXT_MISSING_SOURCE,
                        key = id.value,
                        value = "missing-source-photon",
                    )
                )
            }
            delta.contextChanges.forEach { item ->
                add(
                    BootCognitionEvidence(
                        kind = BootCognitionEvidenceKind.DELTA_CONTEXT,
                        key = "${item.kind.name}:${item.contextId}",
                        value = "change=${item.change.name};before=${item.previousSourceRevision ?: "-"};after=${item.currentSourceRevision ?: "-"}",
                    )
                )
            }
            delta.photonChanges.forEach { item ->
                add(
                    BootCognitionEvidence(
                        kind = BootCognitionEvidenceKind.DELTA_PHOTON,
                        key = item.photonId.value,
                        value = "change=${item.change.name};before=${item.previousRevision ?: "-"};after=${item.currentRevision ?: "-"}",
                    )
                )
            }
            delta.missedDurableTasks.forEach { item ->
                add(
                    BootCognitionEvidence(
                        kind = BootCognitionEvidenceKind.DELTA_TASK,
                        key = item.taskId,
                        value = listOf(
                            "state=${item.state.name}",
                            "reason=${item.reason.name}",
                            "scheduledAt=${item.scheduledAt ?: "-"}",
                            "leaseExpiresAt=${item.leaseExpiresAt ?: "-"}",
                        ).joinToString(";"),
                    )
                )
            }
            delta.fieldWatermarkChanges.forEach { item ->
                add(
                    BootCognitionEvidence(
                        kind = BootCognitionEvidenceKind.DELTA_FIELD,
                        key = item.domainId,
                        value = "before=${item.previousWatermark ?: "-"};after=${item.currentWatermark ?: "-"}",
                    )
                )
            }
            delta.capabilityChanges.forEach { item ->
                add(
                    BootCognitionEvidence(
                        kind = BootCognitionEvidenceKind.DELTA_CAPABILITY,
                        key = "${item.capabilityId}:${item.providerId}",
                        value = "before=${item.previousFingerprint ?: "-"};after=${item.currentFingerprint ?: "-"}",
                    )
                )
            }
            delta.toolChanges.forEach { item ->
                add(
                    BootCognitionEvidence(
                        kind = BootCognitionEvidenceKind.DELTA_TOOL,
                        key = item.toolId,
                        value = listOf(
                            "before=${item.previousFingerprint ?: "-"}",
                            "after=${item.currentFingerprint ?: "-"}",
                            "addedPermissions=${item.addedPermissions.joinToString(",")}",
                            "removedPermissions=${item.removedPermissions.joinToString(",")}",
                        ).joinToString(";"),
                    )
                )
            }
        }.distinct().sortedWith(bootCognitionEvidenceComparator)

        val sourceIds = buildSet {
            context.contexts.forEach { add(it.sourcePhotonId) }
            delta.photonChanges.forEach { add(it.photonId) }
        }.sortedBy { it.value }

        return BootCognitionReport(
            currentGenerationId = snapshot.generationId,
            previousGenerationId = delta.previousGenerationId,
            disposition = disposition,
            snapshotPartial = snapshot.partial,
            sourcePhotonIds = sourceIds,
            evidence = evidence,
        )
    }
}

class BootCognitionReportPhotonFactory {
    fun create(report: BootCognitionReport, createdAt: Instant): Photon {
        val encoded = BootCognitionReportCodec.encode(report)
        val fingerprint = report.fingerprint()
        return Photon(
            id = PhotonId("bootreport_$fingerprint"),
            revision = 1,
            content = encoded,
            mimeType = MIME_TYPE,
            phase = PhotonPhase.CONVERGED,
            semanticMass = 1.0,
            energy = 1.0,
            confidence = 1.0,
            provenance = Provenance(
                source = "boot-cognition-report",
                actor = "lifeos-runtime",
                createdAt = createdAt,
                parentIds = report.sourcePhotonIds.toSet(),
            ),
            tags = setOf(
                "boot-cognition-report",
                "boot:disposition:${report.disposition.name.lowercase()}",
                "boot:generation:${report.currentGenerationId.value}",
            ),
        )
    }

    companion object {
        const val MIME_TYPE = "application/vnd.lifeos.boot-cognition-report+text"
    }
}

class BootCognitionReportPublisher(
    private val repository: PhotonRepository,
    private val photonFactory: BootCognitionReportPhotonFactory = BootCognitionReportPhotonFactory(),
) {
    suspend fun publish(report: BootCognitionReport, createdAt: Instant): Photon {
        val candidate = photonFactory.create(report, createdAt)
        repository.load(candidate.id)?.let { existing ->
            require(existing.mimeType == candidate.mimeType && existing.content == candidate.content) {
                "Boot cognition report id collision"
            }
            return existing
        }

        repository.save(candidate)
        val persisted = requireNotNull(repository.load(candidate.id)) {
            "Boot cognition report was not durably readable after save"
        }
        require(persisted.content == candidate.content && persisted.mimeType == candidate.mimeType) {
            "Persisted boot cognition report failed read-after-write verification"
        }
        return persisted
    }
}

data class BootCognitionRecordResult(
    val report: BootCognitionReport,
    val photon: Photon?,
) {
    val persisted: Boolean get() = photon != null
}

/**
 * Produces inspectable durable boot evidence from D01/D02/D03 without becoming a source of boot
 * truth itself. The previous snapshot is only a process-local retry baseline; durable restart
 * truth always comes from [BootSnapshotLoader].
 */
class BootCognitionEvidenceRecorder(
    private val loader: BootSnapshotLoader,
    private val scanner: BootIntegrityScanner,
    private val contextRehydrator: BootContextRehydrator,
    private val deltaAnalyzer: BootDeltaAnalyzer,
    private val publisher: BootCognitionReportPublisher,
    private val reportFactory: BootCognitionReportFactory = BootCognitionReportFactory(),
) {
    private val mutex = Mutex()
    private var previousSnapshot: DurableBootSnapshot? = null

    suspend fun record(): BootCognitionRecordResult = mutex.withLock {
        val current = loader.load()
        val integrity = scanner.scan(current)
        val context = contextRehydrator.rehydrate(current)
        val delta = deltaAnalyzer.analyze(previousSnapshot, current)
        val report = reportFactory.create(current, integrity, context, delta)

        val photon = if (isNontrivial(current, integrity, context, delta)) {
            try {
                publisher.publish(report, current.capturedAt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        } else {
            null
        }
        previousSnapshot = current
        BootCognitionRecordResult(report, photon)
    }

    private fun isNontrivial(
        snapshot: DurableBootSnapshot,
        integrity: BootIntegrityReport,
        context: BootContextRehydrationResult,
        delta: BootDeltaAnalysis,
    ): Boolean = snapshot.photons.isNotEmpty() || snapshot.tasks.isNotEmpty() ||
        snapshot.checkpoints.isNotEmpty() || snapshot.contexts.isNotEmpty() ||
        snapshot.capabilities.isNotEmpty() || snapshot.workerLeases.isNotEmpty() ||
        snapshot.tools.isNotEmpty() || snapshot.fieldSnapshots.isNotEmpty() || snapshot.partial ||
        integrity.findings.isNotEmpty() || context.contexts.isNotEmpty() ||
        context.missingSourcePhotonIds.isNotEmpty() || delta.hasMeaningfulDelta
}

private val bootCognitionEvidenceComparator = compareBy<BootCognitionEvidence>(
    { it.kind.name }, { it.key }, { it.value },
)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
