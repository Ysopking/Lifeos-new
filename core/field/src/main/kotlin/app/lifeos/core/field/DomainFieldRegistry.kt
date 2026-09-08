package app.lifeos.core.field

data class DomainFieldKey(
    val domainId: FieldDomainId,
    val name: String,
    val version: Int,
) {
    init {
        require(name.isNotBlank()) { "Domain field key name must not be blank" }
        require(version > 0) { "Domain field key version must be positive" }
    }

    companion object {
        fun from(descriptor: DomainFieldDescriptor): DomainFieldKey = DomainFieldKey(
            domainId = descriptor.domainId,
            name = descriptor.name.trim().lowercase(),
            version = descriptor.version,
        )
    }
}

/**
 * Immutable registry for domain-specific field physics.
 *
 * Registration is explicit and deterministic: one canonical (domain, name, version) key may only
 * exist once. Request-local fields may deliberately override an identically keyed registered field
 * for one convergence run; all other registered fields remain active.
 */
class DomainFieldRegistry(
    fields: Iterable<DomainField> = emptyList(),
) {
    private val registered: Map<DomainFieldKey, DomainField>

    init {
        val byKey = linkedMapOf<DomainFieldKey, DomainField>()
        fields.forEach { field ->
            val key = DomainFieldKey.from(field.descriptor)
            require(key !in byKey) { "Duplicate domain field registration: $key" }
            byKey[key] = field
        }
        registered = byKey.toMap()
    }

    fun fieldsFor(domainId: FieldDomainId): List<DomainField> = registered
        .filterKeys { it.domainId == domainId }
        .values
        .toList()
        .stableDomainFieldOrder()

    fun resolve(
        domainId: FieldDomainId,
        explicitFields: List<DomainField> = emptyList(),
    ): List<DomainField> {
        require(explicitFields.all { it.descriptor.domainId == domainId }) {
            "Explicit domain fields must match requested domain"
        }
        val explicitKeys = explicitFields.map { DomainFieldKey.from(it.descriptor) }
        require(explicitKeys.distinct().size == explicitKeys.size) {
            "Explicit domain fields must have unique descriptor keys"
        }

        val merged = linkedMapOf<DomainFieldKey, DomainField>()
        fieldsFor(domainId).forEach { merged[DomainFieldKey.from(it.descriptor)] = it }
        explicitFields.forEach { merged[DomainFieldKey.from(it.descriptor)] = it }
        return merged.values.toList().stableDomainFieldOrder()
    }

    fun plus(field: DomainField): DomainFieldRegistry = DomainFieldRegistry(registered.values + field)

    fun keys(): List<DomainFieldKey> = registered.keys.sortedWith(
        compareBy<DomainFieldKey> { it.domainId.value }
            .thenBy { it.name }
            .thenBy { it.version },
    )

    fun fingerprint(): String = fingerprintOf(registered.values)

    companion object {
        val EMPTY = DomainFieldRegistry()

        fun fingerprintOf(fields: Iterable<DomainField>): String {
            val descriptors = fields.map { it.descriptor }.sortedWith(
                compareByDescending<DomainFieldDescriptor> { it.priority }
                    .thenBy { it.domainId.value }
                    .thenBy { it.name.trim().lowercase() }
                    .thenBy { it.version },
            )
            val parts = buildList {
                add("domain-field-registry/v1")
                descriptors.forEach { descriptor ->
                    add(descriptor.domainId.value)
                    add(descriptor.name.trim().lowercase())
                    add(descriptor.version.toString())
                    add(descriptor.priority.toString())
                }
            }
            return StableFieldIds.fingerprint(*parts.toTypedArray())
        }
    }
}
