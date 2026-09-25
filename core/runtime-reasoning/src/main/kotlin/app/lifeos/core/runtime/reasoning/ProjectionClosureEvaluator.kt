package app.lifeos.core.runtime.reasoning

import app.lifeos.core.field.StableFieldIds

enum class ProjectionClosureStatus {
    CLOSED,
    NOT_CLOSED,
    UNRESOLVED,
}

data class ProjectionTransitionSample(
    val realizationProfileFingerprint: String,
    val projectionId: String,
    val sourceRevisionId: String,
    val sourceProjectionFingerprint: String,
    val successorRevisionId: String,
    val successorProjectionFingerprint: String,
    val evidenceFingerprint: String,
) {
    init {
        require(realizationProfileFingerprint.isNotBlank())
        require(projectionId.isNotBlank())
        require(sourceRevisionId.isNotBlank())
        require(successorRevisionId.isNotBlank())
        require(sourceRevisionId != successorRevisionId) {
            "Projection transition must connect distinct realization revisions"
        }
        require(sourceProjectionFingerprint.isNotBlank())
        require(successorProjectionFingerprint.isNotBlank())
        require(evidenceFingerprint.isNotBlank())
    }

    fun fingerprint(): String = StableFieldIds.fingerprint(
        "projection-transition-sample/v1",
        realizationProfileFingerprint,
        projectionId,
        sourceRevisionId,
        sourceProjectionFingerprint,
        successorRevisionId,
        successorProjectionFingerprint,
        evidenceFingerprint,
    )
}

data class ProjectionClosureConflict(
    val sourceProjectionFingerprint: String,
    val sourceRevisionIds: List<String>,
    val successorProjectionFingerprints: List<String>,
    val evidenceFingerprints: List<String>,
    val fingerprint: String,
) {
    init {
        require(sourceProjectionFingerprint.isNotBlank())
        require(sourceRevisionIds.size >= 2)
        require(sourceRevisionIds == sourceRevisionIds.distinct().sorted())
        require(successorProjectionFingerprints.size >= 2)
        require(
            successorProjectionFingerprints ==
                successorProjectionFingerprints.distinct().sorted()
        )
        require(evidenceFingerprints.isNotEmpty())
        require(evidenceFingerprints == evidenceFingerprints.distinct().sorted())
        require(
            fingerprint == expectedFingerprint(
                sourceProjectionFingerprint,
                sourceRevisionIds,
                successorProjectionFingerprints,
                evidenceFingerprints,
            )
        )
    }

    companion object {
        fun create(
            sourceProjectionFingerprint: String,
            sourceRevisionIds: Collection<String>,
            successorProjectionFingerprints: Collection<String>,
            evidenceFingerprints: Collection<String>,
        ): ProjectionClosureConflict {
            val sources = sourceRevisionIds.distinct().sorted()
            val successors = successorProjectionFingerprints.distinct().sorted()
            val evidence = evidenceFingerprints.distinct().sorted()
            require(sources.size >= 2)
            require(successors.size >= 2)
            return ProjectionClosureConflict(
                sourceProjectionFingerprint = sourceProjectionFingerprint,
                sourceRevisionIds = sources,
                successorProjectionFingerprints = successors,
                evidenceFingerprints = evidence,
                fingerprint = expectedFingerprint(
                    sourceProjectionFingerprint,
                    sources,
                    successors,
                    evidence,
                ),
            )
        }

        private fun expectedFingerprint(
            sourceProjectionFingerprint: String,
            sourceRevisionIds: List<String>,
            successorProjectionFingerprints: List<String>,
            evidenceFingerprints: List<String>,
        ): String = StableFieldIds.fingerprint(
            "projection-closure-conflict/v1",
            sourceProjectionFingerprint,
            *sourceRevisionIds.map { "source:$it" }.toTypedArray(),
            *successorProjectionFingerprints.map { "successor:$it" }.toTypedArray(),
            *evidenceFingerprints.map { "evidence:$it" }.toTypedArray(),
        )
    }
}

data class ProjectionClosureResult(
    val realizationProfileFingerprint: String,
    val projectionId: String,
    val status: ProjectionClosureStatus,
    val comparableSourceProjectionFingerprints: List<String>,
    val conflicts: List<ProjectionClosureConflict>,
    val evidenceFingerprints: List<String>,
    val fingerprint: String,
) {
    init {
        require(realizationProfileFingerprint.isNotBlank())
        require(projectionId.isNotBlank())
        require(
            comparableSourceProjectionFingerprints ==
                comparableSourceProjectionFingerprints.distinct().sorted()
        )
        require(conflicts == conflicts.distinctBy { it.fingerprint }.sortedBy { it.fingerprint })
        require(evidenceFingerprints == evidenceFingerprints.distinct().sorted())
        when (status) {
            ProjectionClosureStatus.CLOSED -> {
                require(comparableSourceProjectionFingerprints.isNotEmpty())
                require(conflicts.isEmpty())
            }
            ProjectionClosureStatus.NOT_CLOSED -> require(conflicts.isNotEmpty())
            ProjectionClosureStatus.UNRESOLVED -> {
                require(comparableSourceProjectionFingerprints.isEmpty())
                require(conflicts.isEmpty())
            }
        }
    }

    val autonomousProjectionEstablished: Boolean
        get() = status == ProjectionClosureStatus.CLOSED

    val truthAuthority: Boolean
        get() = false
}

/**
 * B518 projection non-closure evaluator.
 *
 * If distinct realization revisions collapse to the same current projection while their projected
 * successors diverge, that projection cannot be treated as an autonomous state for this frozen
 * profile. Lack of a comparable pair remains UNRESOLVED rather than being read as closure.
 */
class ProjectionClosureEvaluator {
    fun evaluate(
        samples: Collection<ProjectionTransitionSample>,
    ): ProjectionClosureResult {
        require(samples.isNotEmpty()) {
            "Projection closure evaluation requires transition samples"
        }
        val canonical = samples
            .distinctBy { it.fingerprint() }
            .sortedBy { it.fingerprint() }
        val profile = canonical.first().realizationProfileFingerprint
        val projectionId = canonical.first().projectionId
        require(canonical.all { it.realizationProfileFingerprint == profile }) {
            "Projection closure samples must use one frozen realization profile"
        }
        require(canonical.all { it.projectionId == projectionId }) {
            "Projection closure samples must target one projection"
        }

        val comparable = canonical
            .groupBy { it.sourceProjectionFingerprint }
            .filterValues {
                it.map(ProjectionTransitionSample::sourceRevisionId).distinct().size >= 2
            }

        val conflicts = comparable
            .mapNotNull { (sourceProjection, group) ->
                val successors = group
                    .map(ProjectionTransitionSample::successorProjectionFingerprint)
                    .distinct()
                    .sorted()
                if (successors.size <= 1) {
                    null
                } else {
                    ProjectionClosureConflict.create(
                        sourceProjectionFingerprint = sourceProjection,
                        sourceRevisionIds = group.map(ProjectionTransitionSample::sourceRevisionId),
                        successorProjectionFingerprints = successors,
                        evidenceFingerprints = group.map(ProjectionTransitionSample::evidenceFingerprint),
                    )
                }
            }
            .sortedBy { it.fingerprint }

        val comparableFingerprints = comparable.keys.sorted()
        val status = when {
            conflicts.isNotEmpty() -> ProjectionClosureStatus.NOT_CLOSED
            comparableFingerprints.isEmpty() -> ProjectionClosureStatus.UNRESOLVED
            else -> ProjectionClosureStatus.CLOSED
        }
        val evidence = canonical
            .map(ProjectionTransitionSample::evidenceFingerprint)
            .distinct()
            .sorted()
        val fingerprint = StableFieldIds.fingerprint(
            "projection-closure-result/v1",
            profile,
            projectionId,
            status.name,
            *comparableFingerprints.map { "comparable:$it" }.toTypedArray(),
            *conflicts.map { "conflict:${it.fingerprint}" }.toTypedArray(),
            *evidence.map { "evidence:$it" }.toTypedArray(),
        )
        return ProjectionClosureResult(
            realizationProfileFingerprint = profile,
            projectionId = projectionId,
            status = status,
            comparableSourceProjectionFingerprints = comparableFingerprints,
            conflicts = conflicts,
            evidenceFingerprints = evidence,
            fingerprint = fingerprint,
        )
    }
}
