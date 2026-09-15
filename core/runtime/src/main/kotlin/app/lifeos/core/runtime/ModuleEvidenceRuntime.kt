package app.lifeos.core.runtime

import app.lifeos.core.model.ModuleCoupling
import app.lifeos.core.model.ModuleCouplingRecord
import app.lifeos.core.model.ModuleIdentity
import app.lifeos.core.model.ModuleOutcome
import app.lifeos.core.model.ModuleProcessingId
import app.lifeos.core.model.ModuleProcessingRecord
import app.lifeos.core.model.ModuleReplayEnvelope
import app.lifeos.core.model.ModuleUtilitySnapshot
import app.lifeos.core.model.ModuleWorkspaceEntry
import app.lifeos.core.model.ModuleWorkspaceSnapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared canonical evidence layer for module coupling, learning, workspace and replay. */
class ModuleEvidenceRuntime(
    private val snapshotStore: ModuleEvidenceSnapshotStore? = null,
) {
    private val mutex = Mutex()
    private val processing = linkedMapOf<String, ModuleProcessingRecord>()
    private val couplings = linkedMapOf<String, ModuleCouplingRecord>()
    private val outcomes = linkedMapOf<String, ModuleOutcome>()
    private val utilities = linkedMapOf<String, ModuleUtilitySnapshot>()

    suspend fun restore() {
        val snapshot = snapshotStore?.load() ?: return
        mutex.withLock {
            validateSnapshot(snapshot)
            processing.clear(); couplings.clear(); outcomes.clear(); utilities.clear()
            snapshot.processing.sortedBy { it.processingId.value }.forEach { processing[it.processingId.value] = it }
            snapshot.couplings.sortedBy { it.coupling.stableFingerprint }.forEach { couplings[it.coupling.stableFingerprint] = it }
            snapshot.outcomes.sortedBy { it.stableFingerprint }.forEach { outcomes[it.stableFingerprint] = it }
            snapshot.utilities.sortedBy { it.module.stableFingerprint }.forEach { utilities[it.module.stableFingerprint] = it }
        }
    }

    suspend fun snapshot(): ModuleEvidenceSnapshot = mutex.withLock { snapshotLocked() }

    suspend fun recordProcessing(record: ModuleProcessingRecord) = mutate {
        val key = record.processingId.value
        val existing = processing[key]
        require(existing == null || existing == record) { "Processing evidence is immutable: $key" }
        processing[key] = record
        utilities.putIfAbsent(record.module.stableFingerprint, ModuleUtilitySnapshot.empty(record.module))
    }

    suspend fun processing(id: ModuleProcessingId): ModuleProcessingRecord? = mutex.withLock { processing[id.value] }

    suspend fun recordCoupling(coupling: ModuleCoupling, record: ModuleProcessingRecord) = mutate {
        require(processing[record.processingId.value] == record) { "Coupling requires canonical processing evidence" }
        val evidence = ModuleCouplingRecord(coupling, record)
        val key = coupling.stableFingerprint
        val existing = couplings[key]
        require(existing == null || existing == evidence) { "Coupling evidence is immutable: $key" }
        couplings[key] = evidence
    }

    suspend fun recordOutcome(outcome: ModuleOutcome) = mutate {
        val processingKey = outcome.processing.processingId.value
        require(processing[processingKey] == outcome.processing) { "Outcome requires canonical recorded processing evidence" }
        val key = outcome.stableFingerprint
        val existing = outcomes[key]
        require(existing == null || existing == outcome) { "Outcome evidence is immutable: $key" }
        if (existing == null) {
            outcomes[key] = outcome
            val fingerprint = outcome.processing.module.stableFingerprint
            utilities[fingerprint] = (utilities[fingerprint] ?: ModuleUtilitySnapshot.empty(outcome.processing.module)).observe(outcome)
        }
    }

    suspend fun workspace(activeModules: Collection<ModuleIdentity>): ModuleWorkspaceSnapshot = mutex.withLock {
        ModuleWorkspaceSnapshot(activeModules.associateBy { it.stableFingerprint }.values.map { module ->
            val latest = processing.values.asSequence().filter { it.module.stableFingerprint == module.stableFingerprint }
                .maxByOrNull { it.processingId.value }
            ModuleWorkspaceEntry(module, latest?.processingId, latest?.traceId, latest?.outputPhotonIds.orEmpty(),
                utilities[module.stableFingerprint] ?: ModuleUtilitySnapshot.empty(module))
        })
    }

    suspend fun replayEnvelopes(): List<ModuleReplayEnvelope> = mutex.withLock {
        processing.values.sortedBy { it.processingId.value }.map { ModuleReplayEnvelope(it, it.outputStateHash, it.outputPhotonIds) }
    }

    suspend fun verifyReplay(replayed: Collection<ModuleProcessingRecord>): Boolean = mutex.withLock {
        val byId = replayed.associateBy { it.processingId.value }
        byId.size == processing.size && processing.values.all { original ->
            byId[original.processingId.value]?.let { ModuleReplayEnvelope(original, original.outputStateHash, original.outputPhotonIds).matches(it) } == true
        }
    }

    private suspend fun mutate(block: () -> Unit) {
        val snapshot = mutex.withLock { block(); snapshotLocked() }
        snapshotStore?.save(snapshot)
    }

    private fun snapshotLocked() = ModuleEvidenceSnapshot(
        processing = processing.values.sortedBy { it.processingId.value },
        couplings = couplings.values.sortedBy { it.coupling.stableFingerprint },
        outcomes = outcomes.values.sortedBy { it.stableFingerprint },
        utilities = utilities.values.sortedBy { it.module.stableFingerprint },
    )

    private fun validateSnapshot(snapshot: ModuleEvidenceSnapshot) {
        val processingById = snapshot.processing.associateBy { it.processingId.value }
        snapshot.couplings.forEach { require(processingById[it.processing.processingId.value] == it.processing) { "Recovered coupling references unknown processing" } }
        snapshot.outcomes.forEach { require(processingById[it.processing.processingId.value] == it.processing) { "Recovered outcome references unknown processing" } }
        val recomputed = linkedMapOf<String, ModuleUtilitySnapshot>()
        snapshot.processing.forEach { recomputed.putIfAbsent(it.module.stableFingerprint, ModuleUtilitySnapshot.empty(it.module)) }
        snapshot.outcomes.sortedBy { it.stableFingerprint }.forEach { outcome ->
            val key = outcome.processing.module.stableFingerprint
            recomputed[key] = (recomputed[key] ?: ModuleUtilitySnapshot.empty(outcome.processing.module)).observe(outcome)
        }
        require(recomputed == snapshot.utilities.associateBy { it.module.stableFingerprint }) { "Recovered utility state does not match outcome evidence" }
    }
}
