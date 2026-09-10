package app.lifeos.core.field

import app.lifeos.core.model.PhotonId
import java.time.Duration
import java.time.Instant

enum class FieldContextScope {
    CURRENT_TASK,
    CURRENT_CONVERSATION,
    CURRENT_PROJECT,
    CURRENT_GOAL,
    PERSON_CONTEXT,
    DOCUMENT_CONTEXT,
    FINANCIAL_CONTEXT,
    LEGAL_CONTEXT,
    SCIENTIFIC_CONTEXT,
    GLOBAL_MEMORY,
}

data class TemporalContext(
    val now: Instant,
    val eventTime: Instant? = null,
    val queryTime: Instant = now,
) {
    fun ageOf(observedAt: Instant): Duration = Duration.between(observedAt, queryTime)
}

data class DomainContext(
    val domainId: FieldDomainId,
    val attributes: Map<String, String> = emptyMap(),
) {
    init { require(attributes.keys.none { it.isBlank() }) { "Domain context keys must not be blank" } }
}

data class PhotonContextReference(
    val photonId: PhotonId,
    val revision: Long,
    val scopes: Set<FieldContextScope>,
    val semanticTerms: Set<String>,
    val confidence: Double,
    val observedAt: Instant,
) {
    init {
        require(revision > 0) { "Photon context revision must be positive" }
        require(scopes.isNotEmpty()) { "Photon context requires at least one scope" }
        require(semanticTerms.none { it.isBlank() }) { "Photon context terms must not be blank" }
        require(confidence in 0.0..1.0) { "Photon context confidence must be in 0..1" }
    }
}

data class FieldContext(
    val temporal: TemporalContext,
    val domain: DomainContext,
    val photonReferences: List<PhotonContextReference> = emptyList(),
    val activeScopes: Set<FieldContextScope> = setOf(FieldContextScope.CURRENT_TASK),
) {
    init {
        require(activeScopes.isNotEmpty()) { "Field context requires at least one active scope" }
        require(photonReferences.map { it.photonId to it.revision }.distinct().size == photonReferences.size) {
            "Field context cannot contain duplicate photon revisions"
        }
    }

    fun relevantReferences(): List<PhotonContextReference> = photonReferences
        .asSequence()
        .filter { reference -> reference.scopes.any(activeScopes::contains) }
        .sortedWith(
            compareByDescending<PhotonContextReference> { it.confidence }
                .thenByDescending { it.observedAt }
                .thenBy { it.photonId.value }
                .thenBy { it.revision },
        )
        .toList()

    fun semanticSupport(term: String): Double {
        val normalized = normalizeContextTerm(term)
        if (normalized.isBlank()) return 0.0
        return relevantReferences()
            .filter { ref -> ref.semanticTerms.any { normalizeContextTerm(it) == normalized } }
            .maxOfOrNull { it.confidence }
            ?: 0.0
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        domain.domainId.value,
        temporal.now.toString(),
        temporal.eventTime?.toString().orEmpty(),
        temporal.queryTime.toString(),
        *activeScopes.map { it.name }.sorted().toTypedArray(),
        *domain.attributes.toSortedMap().flatMap { (key, value) -> listOf(key, value) }.toTypedArray(),
        *relevantReferences().flatMap { reference ->
            listOf(
                reference.photonId.value,
                reference.revision.toString(),
                reference.confidence.toString(),
                reference.observedAt.toString(),
            ) + reference.scopes.map { "scope:${it.name}" }.sorted() +
                reference.semanticTerms.map(::normalizeContextTerm).sorted()
        }.toTypedArray(),
    )
}

private fun normalizeContextTerm(value: String): String = value
    .trim()
    .lowercase()
    .replace("ß", "ss")
