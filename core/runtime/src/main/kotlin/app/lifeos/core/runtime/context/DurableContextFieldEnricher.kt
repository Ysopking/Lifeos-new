package app.lifeos.core.runtime.context

import app.lifeos.core.field.FieldContextScope
import app.lifeos.core.field.PhotonContextReference
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonRepository
import app.lifeos.core.runtime.field.FieldRequestEnricher

/**
 * Loads G01 context records before convergence and projects only currently valid active bindings
 * into immutable field context. Persistence IO stays outside [ContextDomainField.evaluate].
 */
class DurableContextFieldEnricher(
    private val repository: PhotonRepository,
) : FieldRequestEnricher {
    override suspend fun enrich(
        request: app.lifeos.core.field.FieldConvergenceRequest,
    ): app.lifeos.core.field.FieldConvergenceRequest {
        val report = repository.loadReport()
        val issues = report.unreadableFiles.map { "vault:$it" }.toMutableList()
        val targets = report.photons
            .filterNot(::isContextRecord)
            .associateBy { it.id }
        val activeEntries = mutableListOf<ContextEntry>()

        report.photons
            .asSequence()
            .filter(::isContextRecord)
            .sortedBy { it.id.value }
            .forEach { photon ->
                val entry = try {
                    ContextRecordCodec.decode(photon)
                } catch (_: Exception) {
                    issues += "malformed:${photon.id.value}"
                    null
                }
                if (entry == null) {
                    issues += "malformed:${photon.id.value}"
                    return@forEach
                }
                if (!entry.active) return@forEach

                val target = targets[entry.targetPhotonId]
                if (target == null) {
                    issues += "missing-target:${entry.recordPhotonId.value}"
                    return@forEach
                }
                if (
                    target.revision != entry.targetRevision ||
                    ContextRecordCodec.targetFingerprint(target) != entry.targetFingerprint
                ) {
                    issues += "stale-target:${entry.recordPhotonId.value}"
                    return@forEach
                }
                activeEntries += entry
            }

        val durableReferences = activeEntries
            .groupBy { it.targetPhotonId to it.targetRevision }
            .values
            .map { entries -> entries.sortedWith(entryPreference()).first() }
            .map(::toFieldReference)

        val references = mergeReferences(
            request.context.photonReferences + durableReferences
        )
        val activeScopes = buildSet {
            addAll(request.context.activeScopes)
            references.forEach { reference -> addAll(reference.scopes) }
        }
        val normalizedIssues = issues.distinct().sorted()
        val attributes = request.context.domain.attributes.toMutableMap().apply {
            put("context.g02.projection", "durable-context/v1")
            put("context.g02.referenceCount", durableReferences.size.toString())
            put("context.g02.complete", normalizedIssues.isEmpty().toString())
            put(
                "context.g02.integrityFingerprint",
                if (normalizedIssues.isEmpty()) {
                    "complete"
                } else {
                    StableFieldIds.fingerprint(
                        "durable-context-integrity/v1",
                        *normalizedIssues.toTypedArray(),
                    )
                }
            )
        }.toSortedMap()

        val contextField = ContextDomainField(
            domainId = request.domainId,
            hypotheses = request.hypotheses,
        )
        val explicitFields = request.domainFields
            .filterNot { field ->
                field.descriptor.name.trim().lowercase() == ContextDomainField.NAME &&
                    field.descriptor.version == ContextDomainField.VERSION
            } + contextField

        return request.copy(
            context = request.context.copy(
                domain = request.context.domain.copy(attributes = attributes),
                photonReferences = references,
                activeScopes = activeScopes,
            ),
            domainFields = explicitFields,
        )
    }

    private fun isContextRecord(photon: Photon): Boolean =
        photon.mimeType == ContextRecordCodec.MIME_TYPE || "context-record" in photon.tags

    private fun entryPreference(): Comparator<ContextEntry> =
        compareByDescending<ContextEntry> { scopePriority(it.scope) }
            .thenByDescending { it.confidence }
            .thenByDescending { it.recordedAt }
            .thenByDescending { it.recordRevision }
            .thenBy { it.recordPhotonId.value }

    private fun scopePriority(scope: ContextScope): Int = when (scope) {
        ContextScope.GOAL -> 3
        ContextScope.CONVERSATION -> 2
        ContextScope.PROJECT -> 1
    }

    private fun toFieldReference(entry: ContextEntry): PhotonContextReference =
        PhotonContextReference(
            photonId = entry.targetPhotonId,
            revision = entry.targetRevision,
            scopes = setOf(fieldScope(entry.scope)),
            semanticTerms = buildSet {
                add(entry.kind.trim().lowercase())
                add("kind:${entry.kind.trim().lowercase()}")
                entry.tags.sorted().forEach { tag ->
                    val normalized = tag.trim().lowercase()
                    if (normalized.isNotBlank()) {
                        add(normalized)
                        add("tag:$normalized")
                    }
                }
                entry.contentTerms.sorted().forEach { term ->
                    val normalized = term.trim().lowercase().replace("ß", "ss")
                    if (normalized.isNotBlank()) add(normalized)
                }
            },
            confidence = entry.confidence,
            observedAt = entry.recordedAt,
        )

    private fun fieldScope(scope: ContextScope): FieldContextScope = when (scope) {
        ContextScope.CONVERSATION -> FieldContextScope.CURRENT_CONVERSATION
        ContextScope.PROJECT -> FieldContextScope.CURRENT_PROJECT
        ContextScope.GOAL -> FieldContextScope.CURRENT_GOAL
    }

    private fun mergeReferences(
        references: List<PhotonContextReference>,
    ): List<PhotonContextReference> = references
        .groupBy { it.photonId to it.revision }
        .entries
        .sortedWith(
            compareBy<Map.Entry<Pair<PhotonId, Long>, List<PhotonContextReference>>> { it.key.first.value }
                .thenBy { it.key.second }
        )
        .map { (_, grouped) ->
            val ordered = grouped.sortedWith(
                compareByDescending<PhotonContextReference> { it.confidence }
                    .thenByDescending { it.observedAt }
                    .thenBy { it.photonId.value }
            )
            val strongest = ordered.first()
            PhotonContextReference(
                photonId = strongest.photonId,
                revision = strongest.revision,
                scopes = grouped
                    .flatMap { it.scopes }
                    .toSortedSet(compareBy<FieldContextScope> { it.name }),
                semanticTerms = grouped.flatMap { it.semanticTerms }.toSortedSet(),
                confidence = grouped.maxOf { it.confidence },
                observedAt = grouped.maxOf { it.observedAt },
            )
        }
}
