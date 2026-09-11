package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import kotlinx.coroutines.CancellationException

/** Durable final product returned by one V12 mission. */
data class DeepSearchMissionProduct(
    val photon: Photon,
    val result: DeepSearchResult,
    val evidencePhotonIds: List<PhotonId>,
    val missionId: DeepSearchMissionId,
)

/** Minimal persistence surface needed to close/recover a DeepSearch result Photon. */
interface DeepSearchResultPhotonPersistence {
    suspend fun save(photon: Photon)
    suspend fun load(id: PhotonId): Photon?
    suspend fun findForMission(missionId: DeepSearchMissionId): Photon?
}

/**
 * Coordinates V12 mission state, planner checkpoints and the durable result Photon.
 *
 * Ordering is deliberate:
 * EXPLORING -> final checkpoint -> SYNTHESIZING -> verify -> Photon.save -> VERIFYING -> terminal.
 * A terminal ledger entry therefore never points at an unpersisted or unverified result Photon.
 */
class DeepSearchMissionCoordinator(
    private val ledger: DeepSearchMissionLedger,
    private val checkpoints: DeepSearchCheckpointStore,
    private val resultPhotons: DeepSearchResultPhotonPersistence,
    private val projector: DeepSearchCheckpointResultProjector = DeepSearchCheckpointResultProjector(),
    private val verifier: DeepSearchMissionVerifier = DeepSearchMissionVerifier(projector),
) {
    suspend fun run(
        definition: DeepSearchMissionDefinition,
        search: suspend (
            resume: DeepSearchPlannerCheckpoint?,
            checkpointSink: DeepSearchCheckpointSink,
            missionId: DeepSearchMissionId,
        ) -> DeepSearchMissionProduct,
    ): DeepSearchMissionProduct {
        var snapshot = ledger.create(definition)

        if (snapshot.terminal) {
            return recoverTerminal(snapshot)
        }

        if (snapshot.state == DeepSearchMissionState.PLANNED) {
            snapshot = ledger.startExploring(snapshot)
        }

        val storedAtEntry = loadCheckpointStrict(snapshot)

        if (snapshot.state == DeepSearchMissionState.VERIFYING) {
            val existing = requireNotNull(resultPhotons.findForMission(definition.id)) {
                "DeepSearch VERIFYING mission is missing its persisted result Photon"
            }
            val checkpoint = requireNotNull(storedAtEntry) {
                "DeepSearch VERIFYING mission is missing its final checkpoint"
            }.checkpoint
            val status = statusFromPhoton(existing)
            val recovered = product(existing, checkpoint, status, definition.id)
            verifier.requireVerified(snapshot.definition, checkpoint, recovered)
            terminalize(snapshot, existing.id, status)
            return recovered
        }

        if (snapshot.state == DeepSearchMissionState.SYNTHESIZING) {
            val checkpoint = requireNotNull(storedAtEntry) {
                "DeepSearch SYNTHESIZING mission is missing its final checkpoint"
            }.checkpoint
            require(projector.isTerminal(checkpoint)) {
                "DeepSearch SYNTHESIZING mission checkpoint is not terminal"
            }
            val existing = resultPhotons.findForMission(definition.id)
            if (existing != null) {
                val status = statusFromPhoton(existing)
                val recovered = product(existing, checkpoint, status, definition.id)
                verifier.requireVerified(snapshot.definition, checkpoint, recovered)
                snapshot = ledger.startVerifying(snapshot)
                terminalize(snapshot, existing.id, status)
                return recovered
            }
        }

        require(
            snapshot.state == DeepSearchMissionState.EXPLORING ||
                snapshot.state == DeepSearchMissionState.SYNTHESIZING
        ) { "Unsupported active DeepSearch mission state: ${snapshot.state}" }

        var latestSnapshot = snapshot
        var latestStored = storedAtEntry
        val sink = DeepSearchCheckpointSink { checkpoint ->
            if (latestSnapshot.state != DeepSearchMissionState.EXPLORING) {
                require(latestStored?.checkpoint?.fingerprint() == checkpoint.fingerprint()) {
                    "DeepSearch synthesis attempted to mutate its frozen final checkpoint"
                }
                return@DeepSearchCheckpointSink
            }
            val stored = checkpoints.persist(definition.id, checkpoint)
            latestStored = stored
            latestSnapshot = ledger.checkpoint(latestSnapshot, checkpoint.fingerprint())
        }

        val searched = try {
            search(latestStored?.checkpoint, sink, definition.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        require(searched.missionId == definition.id) { "DeepSearch search returned another mission id" }

        val finalStored = checkpoints.load(definition.id)
            ?: error("DeepSearch planner completed without a durable final checkpoint")
        require(projector.isTerminal(finalStored.checkpoint)) {
            "DeepSearch planner returned before durable terminal checkpoint"
        }
        if (latestSnapshot.state == DeepSearchMissionState.EXPLORING) {
            latestSnapshot = ledger.checkpoint(latestSnapshot, finalStored.checkpoint.fingerprint())
            latestSnapshot = ledger.startSynthesizing(latestSnapshot)
        }
        require(latestSnapshot.state == DeepSearchMissionState.SYNTHESIZING)

        verifier.requireVerified(latestSnapshot.definition, finalStored.checkpoint, searched)
        resultPhotons.save(searched.photon)
        latestSnapshot = ledger.startVerifying(latestSnapshot)
        terminalize(latestSnapshot, searched.photon.id, searched.result.status)
        return searched
    }

    private suspend fun loadCheckpointStrict(
        snapshot: DeepSearchMissionSnapshot,
    ): DeepSearchStoredCheckpoint? {
        val stored = checkpoints.load(snapshot.definition.id)
        val ledgerFingerprint = snapshot.checkpointFingerprint
        when {
            ledgerFingerprint == null -> Unit
            stored == null -> error("DeepSearch ledger references a missing checkpoint")
            stored.checkpoint.fingerprint() != ledgerFingerprint -> error(
                "DeepSearch ledger/checkpoint fingerprint mismatch"
            )
        }
        return stored
    }

    private suspend fun recoverTerminal(snapshot: DeepSearchMissionSnapshot): DeepSearchMissionProduct {
        require(
            snapshot.state == DeepSearchMissionState.COMPLETED ||
                snapshot.state == DeepSearchMissionState.UNRESOLVED
        ) { "DeepSearch terminal mission has no reusable result: ${snapshot.state}" }
        val resultId = requireNotNull(snapshot.resultPhotonId) {
            "Terminal DeepSearch mission is missing result Photon id"
        }
        val photon = requireNotNull(resultPhotons.load(resultId)) {
            "Terminal DeepSearch result Photon is missing"
        }
        val stored = requireNotNull(checkpoints.load(snapshot.definition.id)) {
            "Terminal DeepSearch mission is missing final checkpoint"
        }
        require(
            snapshot.checkpointFingerprint == null ||
                snapshot.checkpointFingerprint == stored.checkpoint.fingerprint()
        ) { "Terminal DeepSearch checkpoint fingerprint mismatch" }
        val status = statusFromTerminal(snapshot)
        require(status == statusFromPhoton(photon)) {
            "DeepSearch terminal ledger/result Photon status mismatch"
        }
        val recovered = product(photon, stored.checkpoint, status, snapshot.definition.id)
        verifier.requireVerified(snapshot.definition, stored.checkpoint, recovered)
        return recovered
    }

    private suspend fun terminalize(
        snapshot: DeepSearchMissionSnapshot,
        resultPhotonId: PhotonId,
        status: DeepSearchStatus,
    ): DeepSearchMissionSnapshot {
        require(snapshot.state == DeepSearchMissionState.VERIFYING)
        val detail = terminalDetail(status)
        return if (status == DeepSearchStatus.RESOLVED) {
            ledger.complete(snapshot, resultPhotonId, detail)
        } else {
            ledger.unresolved(snapshot, resultPhotonId, detail)
        }
    }

    private fun product(
        photon: Photon,
        checkpoint: DeepSearchPlannerCheckpoint,
        status: DeepSearchStatus,
        missionId: DeepSearchMissionId,
    ): DeepSearchMissionProduct {
        val result = projector.project(checkpoint, status)
        val evidenceIds = result.evidence
            .mapNotNull { it.sourcePhotonId }
            .distinct()
            .sortedBy { it.value }
        return DeepSearchMissionProduct(photon, result, evidenceIds, missionId)
    }

    private fun statusFromTerminal(snapshot: DeepSearchMissionSnapshot): DeepSearchStatus {
        if (snapshot.state == DeepSearchMissionState.COMPLETED) return DeepSearchStatus.RESOLVED
        val detail = requireNotNull(snapshot.lastDetail) { "UNRESOLVED DeepSearch mission lacks terminal detail" }
        val encoded = detail.substringAfter(STATUS_PREFIX, missingDelimiterValue = "")
            .substringBefore(';')
        return runCatching { DeepSearchStatus.valueOf(encoded) }.getOrElse {
            error("Invalid DeepSearch terminal status detail")
        }
    }

    private fun statusFromPhoton(photon: Photon): DeepSearchStatus {
        val encoded = photon.tags
            .firstOrNull { it.startsWith(PHOTON_STATUS_PREFIX) }
            ?.removePrefix(PHOTON_STATUS_PREFIX)
            ?.uppercase()
            ?: error("DeepSearch result Photon lacks status tag")
        return runCatching { DeepSearchStatus.valueOf(encoded) }.getOrElse {
            error("Invalid DeepSearch result Photon status tag")
        }
    }

    private fun terminalDetail(status: DeepSearchStatus): String =
        "$STATUS_PREFIX${status.name};result-photon-persisted-and-verified"

    private companion object {
        const val STATUS_PREFIX = "deepsearch-status="
        const val PHOTON_STATUS_PREFIX = "deepsearch-status:"
    }
}

/** Process-local opt-in V12 coordinator. Absence preserves unit/legacy composition. */
object DeepSearchMissionRuntimeRegistry {
    @Volatile
    private var runtime: DeepSearchMissionCoordinator? = null

    fun install(value: DeepSearchMissionCoordinator) {
        runtime = value
    }

    fun currentOrNull(): DeepSearchMissionCoordinator? = runtime
}
