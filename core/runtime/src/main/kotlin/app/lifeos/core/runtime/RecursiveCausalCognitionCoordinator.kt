package app.lifeos.core.runtime

import app.lifeos.core.model.CausalTraceId
import app.lifeos.core.model.Photon
import kotlinx.coroutines.CancellationException

fun interface CausalDerivedPhotonPersistence {
    suspend fun persist(photon: Photon, traceId: CausalTraceId)
}

data class RecursiveCausalCognitionConfig(
    val maxDepth: Int = 6,
    val maxPersistedDerivedPhotonsPerRoot: Int = 256,
) {
    init {
        require(maxDepth in 0..64) { "maxDepth must be in 0..64" }
        require(maxPersistedDerivedPhotonsPerRoot in 1..4096) {
            "maxPersistedDerivedPhotonsPerRoot must be in 1..4096"
        }
    }
}

data class RecursiveCausalCognitionSummary(
    val rootPhoton: Photon,
    val traces: List<CausalTraceId>,
    val persistedDerivedPhotons: Int,
    val stoppedAtDepth: Int,
    val failures: List<RuntimeFailure>,
)

/**
 * Bounded recursive cognition over newly generated Photons.
 *
 * Every emitted Photon is persisted before it can become a new input. Module ancestry tags prevent
 * a field from revisiting its own causal branch, while depth and output budgets cap cross-module
 * expansion even when several modules keep generating novel evidence.
 */
class RecursiveCausalCognitionCoordinator(
    private val modules: CognitiveModuleRegistry,
    private val engine: CausalCognitionEngine,
    private val persistence: CausalDerivedPhotonPersistence,
    private val config: RecursiveCausalCognitionConfig = RecursiveCausalCognitionConfig(),
) {
    suspend fun processRoot(root: Photon): RecursiveCausalCognitionSummary {
        if (shouldIgnore(root) || modules.activeModules().isEmpty()) {
            return RecursiveCausalCognitionSummary(root, emptyList(), 0, 0, emptyList())
        }

        data class Work(val photon: Photon, val depth: Int)

        val queue = ArrayDeque<Work>()
        queue += Work(root, 0)
        val visitedInputs = linkedSetOf<Pair<String, Long>>()
        val persistedIds = linkedSetOf<String>()
        val traces = mutableListOf<CausalTraceId>()
        val failures = mutableListOf<RuntimeFailure>()
        var deepest = 0

        while (queue.isNotEmpty()) {
            val work = queue.removeFirst()
            deepest = maxOf(deepest, work.depth)
            if (work.depth > config.maxDepth) continue
            if (!visitedInputs.add(work.photon.id.value to work.photon.revision)) continue
            if (shouldIgnore(work.photon)) continue

            val result = try {
                engine.process(work.photon, modules.activeModules())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failures += RuntimeFailure(
                    category = RuntimeFailureCategory.UNKNOWN,
                    source = "recursive-causal-cognition",
                    message = error.message ?: error::class.simpleName ?: "Recursive cognition failed",
                    photonId = work.photon.id,
                )
                continue
            }

            traces += result.traceId
            failures += result.failures
            if (result.replayed) continue

            for (emitted in result.emittedPhotons.sortedBy { it.id.value }) {
                if (!persistedIds.add(emitted.id.value)) continue
                if (persistedIds.size > config.maxPersistedDerivedPhotonsPerRoot) {
                    failures += RuntimeFailure(
                        category = RuntimeFailureCategory.INVARIANT,
                        source = "recursive-causal-cognition",
                        message = "Derived Photon budget reached",
                        photonId = emitted.id,
                    )
                    return RecursiveCausalCognitionSummary(
                        rootPhoton = root,
                        traces = traces.distinct(),
                        persistedDerivedPhotons = persistedIds.size - 1,
                        stoppedAtDepth = work.depth,
                        failures = failures,
                    )
                }

                persistence.persist(emitted, result.traceId)
                if (work.depth < config.maxDepth) {
                    queue += Work(emitted, work.depth + 1)
                }
            }
        }

        return RecursiveCausalCognitionSummary(
            rootPhoton = root,
            traces = traces.distinct(),
            persistedDerivedPhotons = persistedIds.size,
            stoppedAtDepth = deepest,
            failures = failures,
        )
    }

    private fun shouldIgnore(photon: Photon): Boolean =
        photon.mimeType == PhotonBackedCausalLedgerStore.MIME_TYPE ||
            "replay-guard" in photon.tags ||
            "causal-ledger" in photon.tags
}
