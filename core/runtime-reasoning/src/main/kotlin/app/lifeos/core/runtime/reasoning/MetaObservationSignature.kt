package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds

enum class MetaObservableRole {
    CHANNEL,
    OBSERVABLE,
    INVARIANT,
}

data class MetaObservableCoordinate private constructor(
    val semanticId: String,
    val role: MetaObservableRole,
    val valueFingerprint: String,
    val fingerprint: String,
) {
    init {
        require(semanticId.isNotBlank())
        require(valueFingerprint.isNotBlank())
        require(
            fingerprint == StableFieldIds.fingerprint(
                "meta-observable-coordinate/v1",
                role.name,
                semanticId,
                valueFingerprint,
            )
        )
    }

    companion object {
        fun create(
            semanticId: String,
            role: MetaObservableRole,
            valueFingerprint: String,
        ): MetaObservableCoordinate = MetaObservableCoordinate(
            semanticId = semanticId,
            role = role,
            valueFingerprint = valueFingerprint,
            fingerprint = StableFieldIds.fingerprint(
                "meta-observable-coordinate/v1",
                role.name,
                semanticId,
                valueFingerprint,
            ),
        )
    }
}

data class MetaObservationSignature private constructor(
    val domain: MetaDomainFamily,
    val coordinates: List<MetaObservableCoordinate>,
    val fingerprint: String,
) {
    init {
        require(
            coordinates ==
                coordinates
                    .distinctBy { it.fingerprint }
                    .sortedWith(
                        compareBy<MetaObservableCoordinate> { it.role.name }
                            .thenBy { it.semanticId }
                            .thenBy { it.fingerprint }
                    )
        )
        require(
            fingerprint == StableFieldIds.fingerprint(
                "meta-observation-signature/v1",
                domain.name,
                *coordinates.map { it.fingerprint }.toTypedArray(),
            )
        )
    }

    fun values(role: MetaObservableRole): List<String> =
        coordinates.asSequence()
            .filter { it.role == role }
            .map { it.valueFingerprint }
            .distinct()
            .sorted()
            .toList()

    companion object {
        fun create(
            domain: MetaDomainFamily,
            coordinates: Collection<MetaObservableCoordinate>,
        ): MetaObservationSignature {
            val canonical = coordinates
                .distinctBy { it.fingerprint }
                .sortedWith(
                    compareBy<MetaObservableCoordinate> { it.role.name }
                        .thenBy { it.semanticId }
                        .thenBy { it.fingerprint }
                )
            return MetaObservationSignature(
                domain = domain,
                coordinates = canonical,
                fingerprint = StableFieldIds.fingerprint(
                    "meta-observation-signature/v1",
                    domain.name,
                    *canonical.map { it.fingerprint }.toTypedArray(),
                ),
            )
        }
    }
}
