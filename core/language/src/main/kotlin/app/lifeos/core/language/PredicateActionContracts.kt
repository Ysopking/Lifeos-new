package app.lifeos.core.language

/**
 * Deterministic semantic contract for one predicate.
 *
 * Local readiness describes whether LIFEOS may construct/prepare the semantic action. External
 * readiness may require additional roles and is evaluated separately by [SemanticExecutionGate].
 * Intent metadata is deliberately absent from this contract.
 */
data class PredicateActionContract(
    val predicate: PredicateConcept,
    val localRoleAlternatives: List<Set<SemanticRole>>,
    val externalRoleAlternatives: List<Set<SemanticRole>> = localRoleAlternatives,
    val referenceRole: SemanticRole? = null,
    val externalSideEffect: Boolean = false,
) {
    init {
        require(localRoleAlternatives.isNotEmpty())
        require(externalRoleAlternatives.isNotEmpty())
        require(localRoleAlternatives.none { SemanticRole.ACTION in it })
        require(externalRoleAlternatives.none { SemanticRole.ACTION in it })
    }

    fun requiredRoles(
        roles: Map<SemanticRole, SemanticValue>,
        external: Boolean,
    ): Set<SemanticRole> {
        val alternatives = if (external) externalRoleAlternatives else localRoleAlternatives
        return alternatives
            .sortedWith(
                compareByDescending<Set<SemanticRole>> { alternative ->
                    alternative.count { roles[it]?.resolved == true }
                }.thenBy { it.size }
                    .thenBy { alternative -> alternative.map { it.name }.sorted().joinToString(",") }
            )
            .first()
    }
}

/**
 * Closed built-in baseline. Future domain extensions may project candidate contracts through the
 * extension lifecycle, but productive execution always resolves a concrete immutable contract.
 */
class PredicateContractRegistry(
    contracts: Collection<PredicateActionContract> = builtIns(),
) {
    private val byPredicate = contracts.associateBy { it.predicate }.also {
        require(it.size == contracts.size) { "Duplicate predicate action contract" }
    }

    fun contract(predicate: PredicateConcept): PredicateActionContract =
        byPredicate[predicate] ?: EMPTY_CONTRACTS.getValue(predicate)

    companion object {
        private val NONE = listOf(emptySet<SemanticRole>())

        private val EMPTY_CONTRACTS: Map<PredicateConcept, PredicateActionContract> =
            PredicateConcept.entries.associateWith { predicate ->
                PredicateActionContract(predicate, NONE)
            }

        fun builtIns(): List<PredicateActionContract> = listOf(
            PredicateActionContract(
                PredicateConcept.CREATE_IMAGE,
                listOf(setOf(SemanticRole.OBJECT)),
            ),
            PredicateActionContract(
                PredicateConcept.TRANSFORM_IMAGE,
                listOf(setOf(SemanticRole.OBJECT)),
                referenceRole = SemanticRole.OBJECT,
            ),
            PredicateActionContract(
                PredicateConcept.SEARCH,
                listOf(setOf(SemanticRole.OBJECT)),
            ),
            PredicateActionContract(
                PredicateConcept.CONTINUE,
                NONE,
            ),
            PredicateActionContract(
                PredicateConcept.BUILD,
                listOf(setOf(SemanticRole.OBJECT)),
            ),
            PredicateActionContract(
                PredicateConcept.QUERY,
                NONE,
            ),
            PredicateActionContract(
                PredicateConcept.SCHEDULE,
                listOf(setOf(SemanticRole.OBJECT)),
                externalSideEffect = true,
            ),
            PredicateActionContract(
                PredicateConcept.COMMUNICATE,
                localRoleAlternatives = listOf(setOf(SemanticRole.OBJECT)),
                externalRoleAlternatives = listOf(
                    setOf(SemanticRole.OBJECT, SemanticRole.RECIPIENT),
                ),
                referenceRole = SemanticRole.OBJECT,
                externalSideEffect = true,
            ),
            PredicateActionContract(
                PredicateConcept.STORE_MEMORY,
                listOf(setOf(SemanticRole.OBJECT)),
            ),
            PredicateActionContract(
                PredicateConcept.OWE,
                listOf(setOf(SemanticRole.DEBTOR, SemanticRole.CREDITOR, SemanticRole.AMOUNT)),
            ),
            PredicateActionContract(
                PredicateConcept.PAY,
                localRoleAlternatives = listOf(setOf(SemanticRole.AMOUNT)),
                externalRoleAlternatives = listOf(
                    setOf(SemanticRole.AMOUNT, SemanticRole.RECIPIENT),
                ),
                externalSideEffect = true,
            ),
            PredicateActionContract(
                PredicateConcept.DELETE,
                listOf(setOf(SemanticRole.OBJECT)),
                referenceRole = SemanticRole.OBJECT,
            ),
            PredicateActionContract(
                PredicateConcept.UPLOAD,
                listOf(setOf(SemanticRole.OBJECT)),
                referenceRole = SemanticRole.OBJECT,
                externalSideEffect = true,
            ),
            PredicateActionContract(
                PredicateConcept.SELECT,
                listOf(setOf(SemanticRole.OBJECT)),
                referenceRole = SemanticRole.OBJECT,
            ),
            PredicateActionContract(
                PredicateConcept.CONDITION_CHECK,
                NONE,
            ),
        )
    }
}
