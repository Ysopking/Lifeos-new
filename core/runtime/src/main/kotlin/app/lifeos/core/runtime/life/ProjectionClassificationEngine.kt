package app.lifeos.core.runtime.life

/**
 * B454 explicit structural claims supplied by one adapter contract for a single observation.
 *
 * These claims are not authority on their own. The classifier combines them with the concrete
 * observation surface and source authority and defaults conservatively to PROJECTED.
 */
data class ProjectionClassificationContext(
    val directStateRead: Boolean = false,
    val sourceStateClosedForContract: Boolean = false,
    val historicalRecord: Boolean = false,
    val distributionValued: Boolean = false,
    val externallyForced: Boolean = false,
    val schedulerMediated: Boolean = false,
    val inferred: Boolean = false,
    val ownerConfirmed: Boolean = false,
    val predicted: Boolean = false,
) {
    init {
        require(!(ownerConfirmed && inferred)) {
            "Owner confirmation and inference are distinct epistemic states"
        }
        require(!(predicted && historicalRecord)) {
            "One classified observation cannot be both predicted and historical"
        }
    }
}

data class ProjectionClassificationResult(
    val observation: InformationObservation,
    val reasons: List<String>,
) {
    init {
        require(reasons.isNotEmpty())
    }

    val descriptor: RealizationDescriptor
        get() = observation.realization
}

/**
 * Conservative classifier for the WELTFORMEL realization dimensions.
 *
 * A notification, usage event, visual UI observation or browser surface can never be promoted to
 * ACTUAL solely because the adapter reports a direct read. ACTUAL requires a source surface capable
 * of state authority, sufficient source authority and an explicit closed-state contract claim.
 */
class ProjectionClassificationEngine {
    fun classify(
        observation: InformationObservation,
        context: ProjectionClassificationContext = ProjectionClassificationContext(),
    ): ProjectionClassificationResult {
        val reasons = mutableListOf<String>()

        val representation = when {
            context.distributionValued -> {
                reasons += "distribution-valued"
                RepresentationLevel.DISTRIBUTION
            }
            context.externallyForced -> {
                reasons += "externally-forced"
                RepresentationLevel.FORCED
            }
            actualAllowed(observation, context) -> {
                reasons += "authoritative-closed-direct-state"
                RepresentationLevel.ACTUAL
            }
            else -> {
                reasons += when (observation.surface) {
                    ObservationSurfaceKind.NOTIFICATION -> "notification-is-projection"
                    ObservationSurfaceKind.APP_USAGE -> "usage-is-projection"
                    ObservationSurfaceKind.APP_UI -> "ui-is-projection"
                    ObservationSurfaceKind.WEB -> "web-surface-is-projection"
                    else -> "state-closure-not-established"
                }
                RepresentationLevel.PROJECTED
            }
        }

        val epistemicStatus = when {
            context.ownerConfirmed -> {
                reasons += "owner-confirmed"
                EpistemicStatus.OWNER_CONFIRMED
            }
            context.inferred -> {
                reasons += "adapter-inference"
                EpistemicStatus.INFERRED
            }
            else -> EpistemicStatus.OBSERVED
        }

        val temporalStatus = when {
            context.predicted -> {
                reasons += "predicted"
                TemporalStatus.PREDICTED
            }
            context.historicalRecord -> {
                reasons += "historical-record"
                TemporalStatus.HISTORY
            }
            else -> TemporalStatus.CURRENT
        }

        val controlStatus = if (context.schedulerMediated) {
            reasons += "scheduler-mediated"
            ControlStatus.SCHEDULED
        } else {
            observation.realization.controlStatus
        }

        return ProjectionClassificationResult(
            observation = observation.copy(
                realization = RealizationDescriptor(
                    representation = representation,
                    epistemicStatus = epistemicStatus,
                    temporalStatus = temporalStatus,
                    controlStatus = controlStatus,
                )
            ),
            reasons = reasons.distinct(),
        )
    }

    private fun actualAllowed(
        observation: InformationObservation,
        context: ProjectionClassificationContext,
    ): Boolean {
        if (!context.directStateRead || !context.sourceStateClosedForContract) return false
        if (observation.authority.rank < ObservationAuthorityClass.PLATFORM_PROVIDER.rank) return false
        return when (observation.surface) {
            ObservationSurfaceKind.CONTENT_PROVIDER,
            ObservationSurfaceKind.FILE,
            ObservationSurfaceKind.API,
            ObservationSurfaceKind.OWNER_INPUT,
            -> true

            ObservationSurfaceKind.NOTIFICATION,
            ObservationSurfaceKind.SENSOR,
            ObservationSurfaceKind.WEB,
            ObservationSurfaceKind.APP_UI,
            ObservationSurfaceKind.APP_USAGE,
            -> false
        }
    }
}
