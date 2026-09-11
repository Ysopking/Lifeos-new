package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldEquationSpec
import app.lifeos.core.field.world.WorldSignalDimension
import app.lifeos.core.field.world.WorldTransferCoefficient
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.task.TaskId
import app.lifeos.core.runtime.field.FieldWorldSignalLink
import app.lifeos.core.runtime.field.FieldWorldSignalLinkKind
import app.lifeos.core.runtime.field.FieldWorldSignalProjection
import java.time.Instant

/**
 * Explicit informational V4 analysis profile. It computes reproducible field-of-fields relations
 * for reporting only. Nothing in this profile grants write authority over cognition, tasks, goals,
 * hypothesis state, capability routing or actions.
 */
class IntegratedWorldEquationProfile {
    val spec: WorldEquationSpec = WorldEquationSpec(
        version = VERSION,
        coefficients = ALL_COEFFICIENTS,
    )

    fun interactions(links: List<FieldWorldSignalLink>): List<WorldFormulaInteraction> = links
        .sortedBy { it.fingerprint }
        .flatMap(::interactionsFor)
        .sortedBy { it.fingerprint() }

    fun request(
        projection: FieldWorldSignalProjection,
        links: List<FieldWorldSignalLink>,
        observedAt: Instant,
        config: WorldFormulaConfig = WorldFormulaConfig(),
        sourceTaskId: TaskId? = null,
        photonId: PhotonId? = null,
    ): WorldFormulaRequest = WorldFormulaRequest(
        inputs = projection.inputs,
        interactions = interactions(links),
        equationVersion = spec.version,
        observedAt = observedAt,
        config = config,
        sourceTaskId = sourceTaskId,
        photonId = photonId,
    )

    private fun interactionsFor(link: FieldWorldSignalLink): List<WorldFormulaInteraction> = when (link.kind) {
        FieldWorldSignalLinkKind.SOURCE_EVIDENCE -> listOf(
            interaction(link, SOURCE_RELIABILITY, "source reliability provenance"),
            interaction(link, SOURCE_AUTHORITY, "source authority provenance"),
        )
        FieldWorldSignalLinkKind.EVIDENCE_SUPPORTS_HYPOTHESIS -> listOf(
            interaction(link, SUPPORT_EVIDENCE, "evidence support"),
            interaction(link, SUPPORT_RELIABILITY, "reliability support"),
            interaction(link, SUPPORT_AUTHORITY, "authority support"),
            interaction(link, SUPPORT_TEMPORAL, "temporal support"),
            interaction(link, SUPPORT_SEMANTIC, "semantic support"),
        )
        FieldWorldSignalLinkKind.EVIDENCE_CONTRADICTS_HYPOTHESIS -> listOf(
            interaction(link, CONTRADICTION_EVIDENCE, "contradiction pressure"),
            interaction(link, CONTRADICTION_AUTHORITY, "authoritative contradiction pressure"),
        )
        FieldWorldSignalLinkKind.EVIDENCE_REFINES_HYPOTHESIS -> listOf(
            interaction(link, REFINEMENT_SUPPORT, "refinement support"),
            interaction(link, SUPPORT_SEMANTIC, "refinement semantic support"),
        )
        FieldWorldSignalLinkKind.EVIDENCE_DERIVED_HYPOTHESIS -> listOf(
            interaction(link, DERIVED_RELIABILITY, "derived evidence reliability"),
        )
        FieldWorldSignalLinkKind.EVIDENCE_DUPLICATES_HYPOTHESIS -> listOf(
            interaction(link, DUPLICATE_SUPPORT, "duplicate evidence bounded support"),
        )
        FieldWorldSignalLinkKind.HYPOTHESIS_CONFLICT -> listOf(
            interaction(link, HYPOTHESIS_CONFLICT, "competing hypothesis analysis pressure"),
        )
        FieldWorldSignalLinkKind.GOAL_CONTEXT -> listOf(
            interaction(link, GOAL_SALIENCE, "goal relevance raises analytic salience"),
        )
        FieldWorldSignalLinkKind.DOMAIN_CONTEXT -> listOf(
            interaction(link, DOMAIN_UNCERTAINTY, "domain uncertainty context"),
            interaction(link, DOMAIN_CONFLICT, "domain conflict context"),
            interaction(link, DOMAIN_CONTEXT, "domain semantic context"),
        )
    }

    private fun interaction(
        link: FieldWorldSignalLink,
        coefficient: WorldTransferCoefficient,
        label: String,
    ): WorldFormulaInteraction = WorldFormulaInteraction(
        source = link.source,
        target = link.target,
        sourceDimension = coefficient.sourceDimension,
        targetDimension = coefficient.targetDimension,
        coefficientId = coefficient.id,
        strength = link.strength,
        explanation = "$label:${link.kind.name.lowercase()}:${link.provenanceFingerprint}",
    )

    companion object {
        const val VERSION = "lifeos-world-informational-v1"

        private fun coefficient(
            key: String,
            source: WorldSignalDimension,
            target: WorldSignalDimension,
            multiplier: Double,
            cap: Double = 0.5,
        ) = WorldTransferCoefficient.create(
            semanticKey = key,
            sourceDimension = source,
            targetDimension = target,
            multiplier = multiplier,
            maxAbsoluteContribution = cap,
            explanation = "LIFEOS V4 informational world-analysis coefficient $key",
        )

        private val SOURCE_RELIABILITY = coefficient(
            "source-reliability",
            WorldSignalDimension.RELIABILITY,
            WorldSignalDimension.RELIABILITY,
            0.25,
            0.25,
        )
        private val SOURCE_AUTHORITY = coefficient(
            "source-authority",
            WorldSignalDimension.AUTHORITY,
            WorldSignalDimension.AUTHORITY,
            0.25,
            0.25,
        )
        private val SUPPORT_EVIDENCE = coefficient(
            "support-evidence",
            WorldSignalDimension.EVIDENCE_SUPPORT,
            WorldSignalDimension.EVIDENCE_SUPPORT,
            0.35,
            0.35,
        )
        private val SUPPORT_RELIABILITY = coefficient(
            "support-reliability",
            WorldSignalDimension.RELIABILITY,
            WorldSignalDimension.EVIDENCE_SUPPORT,
            0.20,
            0.20,
        )
        private val SUPPORT_AUTHORITY = coefficient(
            "support-authority",
            WorldSignalDimension.AUTHORITY,
            WorldSignalDimension.EVIDENCE_SUPPORT,
            0.15,
            0.15,
        )
        private val SUPPORT_TEMPORAL = coefficient(
            "support-temporal",
            WorldSignalDimension.TEMPORAL_FRESHNESS,
            WorldSignalDimension.EVIDENCE_SUPPORT,
            0.15,
            0.15,
        )
        private val SUPPORT_SEMANTIC = coefficient(
            "support-semantic",
            WorldSignalDimension.SEMANTIC_RELEVANCE,
            WorldSignalDimension.EVIDENCE_SUPPORT,
            0.15,
            0.15,
        )
        private val CONTRADICTION_EVIDENCE = coefficient(
            "contradiction-evidence",
            WorldSignalDimension.EVIDENCE_SUPPORT,
            WorldSignalDimension.CONFLICT_PRESSURE,
            0.55,
            0.55,
        )
        private val CONTRADICTION_AUTHORITY = coefficient(
            "contradiction-authority",
            WorldSignalDimension.AUTHORITY,
            WorldSignalDimension.CONFLICT_PRESSURE,
            0.25,
            0.25,
        )
        private val REFINEMENT_SUPPORT = coefficient(
            "refinement-support",
            WorldSignalDimension.EVIDENCE_SUPPORT,
            WorldSignalDimension.EVIDENCE_SUPPORT,
            0.20,
            0.20,
        )
        private val DERIVED_RELIABILITY = coefficient(
            "derived-reliability",
            WorldSignalDimension.RELIABILITY,
            WorldSignalDimension.EVIDENCE_SUPPORT,
            0.15,
            0.15,
        )
        private val DUPLICATE_SUPPORT = coefficient(
            "duplicate-support",
            WorldSignalDimension.EVIDENCE_SUPPORT,
            WorldSignalDimension.EVIDENCE_SUPPORT,
            0.05,
            0.05,
        )
        private val HYPOTHESIS_CONFLICT = coefficient(
            "hypothesis-conflict",
            WorldSignalDimension.ANALYTIC_SALIENCE,
            WorldSignalDimension.CONFLICT_PRESSURE,
            0.35,
            0.35,
        )
        private val GOAL_SALIENCE = coefficient(
            "goal-salience",
            WorldSignalDimension.GOAL_RELEVANCE,
            WorldSignalDimension.ANALYTIC_SALIENCE,
            0.50,
            0.50,
        )
        private val DOMAIN_UNCERTAINTY = coefficient(
            "domain-uncertainty",
            WorldSignalDimension.UNCERTAINTY,
            WorldSignalDimension.UNCERTAINTY,
            0.30,
            0.30,
        )
        private val DOMAIN_CONFLICT = coefficient(
            "domain-conflict",
            WorldSignalDimension.CONFLICT_PRESSURE,
            WorldSignalDimension.CONFLICT_PRESSURE,
            0.30,
            0.30,
        )
        private val DOMAIN_CONTEXT = coefficient(
            "domain-context",
            WorldSignalDimension.CONTEXT_RELEVANCE,
            WorldSignalDimension.CONTEXT_RELEVANCE,
            0.25,
            0.25,
        )

        private val ALL_COEFFICIENTS = listOf(
            SOURCE_RELIABILITY,
            SOURCE_AUTHORITY,
            SUPPORT_EVIDENCE,
            SUPPORT_RELIABILITY,
            SUPPORT_AUTHORITY,
            SUPPORT_TEMPORAL,
            SUPPORT_SEMANTIC,
            CONTRADICTION_EVIDENCE,
            CONTRADICTION_AUTHORITY,
            REFINEMENT_SUPPORT,
            DERIVED_RELIABILITY,
            DUPLICATE_SUPPORT,
            HYPOTHESIS_CONFLICT,
            GOAL_SALIENCE,
            DOMAIN_UNCERTAINTY,
            DOMAIN_CONFLICT,
            DOMAIN_CONTEXT,
        ).sortedBy { it.id.value }
    }
}
