package app.lifeos.core.model

/**
 * Immutable mathematical projection of a Photon into the LIFEOS world-state space.
 *
 * This is deliberately not a second source of truth. The canonical Photon remains authoritative;
 * this value only exposes bounded state variables that field/runtime physics can consume.
 */
data class WorldStateSignature(
    val semanticMass: Double,
    val energy: Double,
    val phase: Double,
    val polarity: Double,
    val entropy: Double,
    val coherence: Double,
    val coupling: Double,
    val temporalDepth: Double,
    val potential: Double,
) {
    init {
        require(semanticMass.isFinite() && semanticMass >= 0.0) { "semanticMass must be finite and non-negative" }
        require(energy.isFinite() && energy >= 0.0) { "energy must be finite and non-negative" }
        require(phase in 0.0..1.0) { "phase must be in 0..1" }
        require(polarity in -1.0..1.0) { "polarity must be in -1..1" }
        require(entropy in 0.0..1.0) { "entropy must be in 0..1" }
        require(coherence in 0.0..1.0) { "coherence must be in 0..1" }
        require(coupling in 0.0..1.0) { "coupling must be in 0..1" }
        require(temporalDepth.isFinite() && temporalDepth >= 0.0) { "temporalDepth must be finite and non-negative" }
        require(potential.isFinite()) { "potential must be finite" }
    }
}

/** Deterministic default projection from the canonical Photon state. */
fun Photon.toWorldStateSignature(
    polarity: Double = 0.0,
    temporalDepth: Double = 0.0,
    potential: Double = energy,
): WorldStateSignature = WorldStateSignature(
    semanticMass = semanticMass,
    energy = energy,
    phase = when (phase) {
        PhotonPhase.CREATED -> 0.0
        PhotonPhase.ACTIVE -> 0.25
        PhotonPhase.REFLECTING -> 0.50
        PhotonPhase.CONVERGED -> 0.75
        PhotonPhase.ARCHIVED -> 1.0
    },
    polarity = polarity,
    entropy = (1.0 - confidence).coerceIn(0.0, 1.0),
    coherence = confidence,
    coupling = confidence,
    temporalDepth = temporalDepth,
    potential = potential,
)
