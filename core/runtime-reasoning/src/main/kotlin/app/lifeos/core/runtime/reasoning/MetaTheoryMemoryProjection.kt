package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.RelationType
import app.lifeos.core.runtime.thought.ThoughtProjectionInput
import app.lifeos.core.runtime.thought.ThoughtVerificationStatus

/**
 * Typed R0 meta-grammar used by the kernel first-read.
 *
 * The descriptor separates state, transition, channels, observables, invariants, candidate
 * equivalence and deformation. Optional structures stay optional; absence never becomes a fabricated
 * value. Nothing here grants truth, merge, causal or execution authority.
 */
enum class MetaDomainFamily {
    DOCUMENT,
    MEDIA,
    CONTACT,
    CALENDAR,
    APP,
    SENSOR,
    GOAL,
    PROJECT,
    SYSTEM,
    GENERIC,
}

data class MetaDomainDescriptor(
    val domain: MetaDomainFamily,
    val stateFingerprint: String,
    val transitionFingerprint: String?,
    val observationSignature: MetaObservationSignature,
    val equivalenceCandidateFingerprint: String,
    val deformationFingerprint: String?,
) {
    init {
        require(stateFingerprint.isNotBlank())
        require(transitionFingerprint?.isNotBlank() != false)
        require(observationSignature.domain == domain)
        require(equivalenceCandidateFingerprint.isNotBlank())
        require(deformationFingerprint?.isNotBlank() != false)
    }

    val channelFingerprints: List<String>
        get() = observationSignature.values(MetaObservableRole.CHANNEL)

    val observableFingerprints: List<String>
        get() = observationSignature.values(MetaObservableRole.OBSERVABLE)

    val invariantFingerprints: List<String>
        get() = observationSignature.values(MetaObservableRole.INVARIANT)

    val truthAuthority: Boolean get() = false
    val mergeAuthority: Boolean get() = false
    val causalAuthority: Boolean get() = false
    val executionAuthority: Boolean get() = false
}

data class MetaTheoryMemoryProjection(
    val descriptor: MetaDomainDescriptor,
    val projection: ThoughtProjectionInput,
)

/**
 * Converts heterogeneous Photon truth into one common meta-grammar before Gedankenmatrix projection.
 *
 * Source Photons are never mutated. Provenance parentage is materialized as projection-only
 * DERIVED_FROM relations so file/source/context lineage remains traversable in ThoughtMatrixV2.
 */
class MetaTheoryMemoryProjector {
    fun project(photon: Photon): MetaTheoryMemoryProjection {
        val domain = classifyDomain(photon)
        val contentFingerprint = StableFieldIds.fingerprint(
            "metatheory-content/v1",
            photon.mimeType,
            photon.content,
        )
        val stateFingerprint = StableFieldIds.fingerprint(
            "metatheory-state/v1",
            photon.id.value,
            photon.revision.toString(),
            photon.phase.name,
            contentFingerprint,
            photon.provenance.source,
            photon.provenance.actor,
            photon.provenance.createdAt.toString(),
            *photon.tags.sorted().map { "tag:$it" }.toTypedArray(),
        )
        val transitionFingerprint = transitionFingerprint(photon)
        val channelCoordinates = channels(photon).map { raw ->
            MetaObservableCoordinate.create(
                semanticId = raw.substringBefore(':'),
                role = MetaObservableRole.CHANNEL,
                valueFingerprint = StableFieldIds.fingerprint(
                    "metatheory-channel/v1",
                    domain.name,
                    raw,
                ),
            )
        }
        val observableCoordinates = observables(photon).map { raw ->
            MetaObservableCoordinate.create(
                semanticId = raw.substringBefore(':'),
                role = MetaObservableRole.OBSERVABLE,
                valueFingerprint = StableFieldIds.fingerprint(
                    "metatheory-observable/v1",
                    domain.name,
                    raw,
                ),
            )
        }
        val invariantCoordinates = listOf(
            MetaObservableCoordinate.create(
                semanticId = "source-identity",
                role = MetaObservableRole.INVARIANT,
                valueFingerprint = StableFieldIds.fingerprint(
                    "metatheory-invariant/source-identity/v1",
                    photon.id.value,
                ),
            )
        )
        val observationSignature = MetaObservationSignature.create(
            domain = domain,
            coordinates =
                channelCoordinates + observableCoordinates + invariantCoordinates,
        )
        val channels = observationSignature.values(MetaObservableRole.CHANNEL)
        val observables = observationSignature.values(MetaObservableRole.OBSERVABLE)
        val invariants = observationSignature.values(MetaObservableRole.INVARIANT)
        val equivalenceCandidate = StableFieldIds.fingerprint(
            "metatheory-equivalence-candidate/v1",
            domain.name,
            photon.mimeType,
            contentFingerprint,
        )
        val deformation = if (photon.revision > 1L) {
            StableFieldIds.fingerprint(
                "metatheory-deformation/revision/v1",
                photon.id.value,
                photon.revision.toString(),
                transitionFingerprint.orEmpty(),
                stateFingerprint,
            )
        } else {
            null
        }

        val descriptor = MetaDomainDescriptor(
            domain = domain,
            stateFingerprint = stateFingerprint,
            transitionFingerprint = transitionFingerprint,
            observationSignature = observationSignature,
            equivalenceCandidateFingerprint = equivalenceCandidate,
            deformationFingerprint = deformation,
        )
        val projectionTags = buildSet {
            addAll(photon.tags)
            add("metatheory:domain:${domain.name.lowercase()}")
            add("metatheory:state:$stateFingerprint")
            add("metatheory:equivalence-candidate:$equivalenceCandidate")
            transitionFingerprint?.let { add("metatheory:transition:$it") }
            deformation?.let { add("metatheory:deformation:$it") }
            invariants.forEach { add("metatheory:invariant:$it") }
            channels.forEach { add("metatheory:channel:$it") }
            observables.forEach { add("metatheory:observable:$it") }
        }
        val lineageRelations = photon.provenance.parentIds.mapTo(linkedSetOf()) { parent ->
            PhotonRelation(
                target = parent,
                type = RelationType.DERIVED_FROM,
                weight = 1.0,
            )
        }
        val projectedPhoton = photon.copy(
            relations = photon.relations + lineageRelations,
            tags = projectionTags,
        )
        val projection = ThoughtProjectionInput(
            photon = projectedPhoton,
            fieldDomainId = StableFieldIds.domain(
                "lifeos.metatheory.${domain.name.lowercase()}"
            ),
            semanticKey =
                "meta:${domain.name.lowercase()}:$equivalenceCandidate",
            verification = ThoughtVerificationStatus.OBSERVED,
        )
        return MetaTheoryMemoryProjection(descriptor, projection)
    }

    private fun classifyDomain(photon: Photon): MetaDomainFamily {
        val tags = photon.tags
        val mime = photon.mimeType.lowercase()
        return when {
            // Media adapters intentionally also expose document tags for cross-domain retrieval;
            // media identity therefore has priority over the generic document projection.
            "media" in tags ||
                mime.startsWith("image/") ||
                mime.startsWith("audio/") ||
                mime.startsWith("video/") -> MetaDomainFamily.MEDIA

            "document" in tags ||
                "file" in tags ||
                tags.any {
                    it.startsWith("document:") ||
                        it.startsWith("file:") ||
                        it == "archive" ||
                        it == "database" ||
                        it == "backup"
                } -> MetaDomainFamily.DOCUMENT

            "contact" in tags ||
                tags.any { it.startsWith("person:") } -> MetaDomainFamily.CONTACT

            "calendar-event" in tags ||
                tags.any { it.startsWith("location:") } -> MetaDomainFamily.CALENDAR

            tags.any {
                it.startsWith("app:") ||
                    it.startsWith("notification") ||
                    it.startsWith("usage:")
            } -> MetaDomainFamily.APP

            tags.any { it == "sensor" || it.startsWith("sensor:") } ->
                MetaDomainFamily.SENSOR

            tags.any { it == "goal" || it.startsWith("goal:") } ->
                MetaDomainFamily.GOAL

            tags.any { it == "project" || it.startsWith("project:") } ->
                MetaDomainFamily.PROJECT

            "life-memory-management" in tags ||
                photon.provenance.source.startsWith("lifeos") ->
                MetaDomainFamily.SYSTEM

            else -> MetaDomainFamily.GENERIC
        }
    }

    private fun transitionFingerprint(photon: Photon): String? {
        val lineage = buildList {
            photon.provenance.parentIds
                .sortedBy { it.value }
                .forEach { add("parent:${it.value}") }
            photon.relations
                .sortedWith(
                    compareBy<PhotonRelation> { it.target.value }
                        .thenBy { it.type.name }
                        .thenBy { it.weight }
                )
                .forEach {
                    add(
                        "relation:${it.target.value}:${it.type.name}:" +
                            java.lang.Double.toHexString(it.weight)
                    )
                }
        }
        if (lineage.isEmpty()) return null
        return StableFieldIds.fingerprint(
            "metatheory-transition/v1",
            photon.id.value,
            photon.revision.toString(),
            *lineage.toTypedArray(),
        )
    }

    private fun channels(photon: Photon): List<String> = buildList {
        add("source:${photon.provenance.source}")
        photon.tags.sorted().forEach { tag ->
            if (
                tag.startsWith("source:") ||
                tag.startsWith("context:") ||
                tag.startsWith("file:") ||
                tag.startsWith("media:") ||
                tag.startsWith("sensor:")
            ) {
                add(tag)
            }
        }
    }.distinct().sorted().take(MAX_META_AXES)

    private fun observables(photon: Photon): List<String> = buildList {
        add("mime:${photon.mimeType.lowercase()}")
        add("phase:${photon.phase.name}")
        add("confidence:${java.lang.Double.toHexString(photon.confidence)}")
        add("semantic-mass:${java.lang.Double.toHexString(photon.semanticMass)}")
        add("energy:${java.lang.Double.toHexString(photon.energy)}")
        photon.tags.sorted().forEach { tag ->
            if (
                tag.startsWith("extension:") ||
                tag.startsWith("mime:") ||
                tag.startsWith("file-decode:") ||
                tag.startsWith("file-content:")
            ) {
                add(tag)
            }
        }
    }.distinct().sorted().take(MAX_META_AXES)

    private companion object {
        const val MAX_META_AXES = 32
    }
}
