package app.lifeos.core.model

/** Domain semantics select context; canonical coupling kinds continue to describe world dynamics. */
enum class LifeWorldRelation {
    MATTER_TO_DEADLINE,
    DEADLINE_TO_GOAL,
    PAYMENT_TO_FINANCIAL_STATE,
    EVIDENCE_CONTRADICTION,
    MATTER_DEPENDENCY,
}

object LifeWorldCouplingMapper {
    fun couplingKind(relation: LifeWorldRelation): WorldCouplingKind = when (relation) {
        LifeWorldRelation.MATTER_TO_DEADLINE -> WorldCouplingKind.TEMPORAL
        LifeWorldRelation.DEADLINE_TO_GOAL -> WorldCouplingKind.GOAL
        LifeWorldRelation.PAYMENT_TO_FINANCIAL_STATE -> WorldCouplingKind.CAUSAL
        LifeWorldRelation.EVIDENCE_CONTRADICTION -> WorldCouplingKind.CONTRADICTION
        LifeWorldRelation.MATTER_DEPENDENCY -> WorldCouplingKind.DEPENDENCY
    }
}
