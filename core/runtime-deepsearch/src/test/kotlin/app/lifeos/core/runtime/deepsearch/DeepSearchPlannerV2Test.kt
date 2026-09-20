package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.model.PhotonId
import java.time.Duration
import java.time.Instant
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeepSearchPlannerV2Test {
    private val at = Instant.parse("2026-09-11T16:00:00Z")

    @Test
    fun `crash after durable work reservation resumes without charging work twice`() = runTest {
        val request = request(maxWorkUnits = 1)
        val source = CountingSource()
        var durable: DeepSearchPlannerCheckpoint? = null
        val crashSink = DeepSearchCheckpointSink { checkpoint ->
            durable = checkpoint
            if (checkpoint.trace.any { it.type == DeepSearchTraceType.EXPANSION_RESERVED }) {
                throw SimulatedCrash()
            }
        }

        assertFailsWith<SimulatedCrash> {
            DeepSearchPlannerV2(now = { at }).search(
                request = request,
                sources = listOf(source),
                checkpointSink = crashSink,
            )
        }

        val reserved = assertNotNull(durable)
        assertEquals(1, reserved.workUnitsUsed)
        assertEquals(0, source.calls)
        assertTrue(reserved.trace.any { it.type == DeepSearchTraceType.EXPANSION_RESERVED })

        val result = DeepSearchPlannerV2(now = { at }).search(
            request = request,
            sources = listOf(source),
            resume = reserved,
            checkpointSink = DeepSearchCheckpointSink { durable = it },
        )

        assertEquals(1, result.workUnitsUsed)
        assertEquals(1, source.calls)
        assertEquals(DeepSearchStatus.RESOLVED, result.status)
    }

    @Test
    fun `source blocked at mission start cannot gain authority during resume`() = runTest {
        val request = request(maxWorkUnits = 2)
        val source = CountingSource()
        var durable: DeepSearchPlannerCheckpoint? = null
        val denying = DeepSearchPlannerV2(
            capabilityGate = FixedGate(false),
            now = { at },
        )

        val first = denying.search(
            request = request,
            sources = listOf(source),
            checkpointSink = DeepSearchCheckpointSink { durable = it },
        )
        assertEquals(DeepSearchStatus.PERMISSION_BLOCKED, first.status)
        assertEquals(0, source.calls)

        val resumed = DeepSearchPlannerV2(
            capabilityGate = FixedGate(true),
            now = { at },
        ).search(
            request = request,
            sources = listOf(source),
            resume = assertNotNull(durable),
        )

        assertEquals(DeepSearchStatus.PERMISSION_BLOCKED, resumed.status)
        assertEquals(0, source.calls)
    }

    @Test
    fun `previously authorized external-style source is rechecked and can be revoked`() = runTest {
        val request = request(maxWorkUnits = 2)
        val source = CountingSource()
        var durable: DeepSearchPlannerCheckpoint? = null

        assertFailsWith<SimulatedCrash> {
            DeepSearchPlannerV2(
                capabilityGate = FixedGate(true),
                now = { at },
            ).search(
                request = request,
                sources = listOf(source),
                checkpointSink = DeepSearchCheckpointSink { checkpoint ->
                    durable = checkpoint
                    if (checkpoint.trace.any { it.type == DeepSearchTraceType.SOURCE_AUTHORIZED } &&
                        checkpoint.trace.none { it.type == DeepSearchTraceType.EXPANSION_RESERVED }
                    ) {
                        throw SimulatedCrash()
                    }
                },
            )
        }

        val resumed = DeepSearchPlannerV2(
            capabilityGate = FixedGate(false),
            now = { at },
        ).search(
            request = request,
            sources = listOf(source),
            resume = assertNotNull(durable),
        )

        assertEquals(DeepSearchStatus.PERMISSION_BLOCKED, resumed.status)
        assertEquals(0, source.calls)
        assertTrue(resumed.blockedSourceIds.contains(source.descriptor.sourceId))
    }

    @Test
    fun `retryable source failure is checkpointed and succeeds without double work charge`() = runTest {
        val request = request(maxWorkUnits = 1)
        val source = FlakySource(failuresBeforeSuccess = 2)
        var durable: DeepSearchPlannerCheckpoint? = null

        val result = DeepSearchPlannerV2(
            retryPolicy = DeepSearchRetryPolicy(
                maxAttemptsPerExpansion = 3,
                baseBackoffMillis = 1,
                maxBackoffMillis = 1,
            ),
            now = { at },
        ).search(
            request = request,
            sources = listOf(source),
            checkpointSink = DeepSearchCheckpointSink { durable = it },
        )

        assertEquals(DeepSearchStatus.RESOLVED, result.status)
        assertEquals(3, source.calls)
        assertEquals(1, result.workUnitsUsed)
        assertEquals(
            2,
            result.trace.count { it.type == DeepSearchTraceType.SOURCE_RETRY_SCHEDULED },
        )
        assertNotNull(durable)
    }

    @Test
    fun `permanent source failure is not retried`() = runTest {
        val request = request(maxWorkUnits = 1)
        val source = PermanentFailureSource()

        val result = DeepSearchPlannerV2(
            retryPolicy = DeepSearchRetryPolicy(
                maxAttemptsPerExpansion = 3,
                baseBackoffMillis = 1,
                maxBackoffMillis = 1,
            ),
            now = { at },
        ).search(
            request = request,
            sources = listOf(source),
        )

        assertEquals(1, source.calls)
        assertTrue(result.failedSourceIds.contains(source.descriptor.sourceId))
        assertTrue(result.trace.none { it.type == DeepSearchTraceType.SOURCE_RETRY_SCHEDULED })
        assertTrue(result.trace.any { it.type == DeepSearchTraceType.SOURCE_FAILED })
    }

    @Test
    fun `retry decision survives crash and resume without another reservation`() = runTest {
        val request = request(maxWorkUnits = 1)
        val source = FlakySource(failuresBeforeSuccess = 1)
        var durable: DeepSearchPlannerCheckpoint? = null

        assertFailsWith<SimulatedCrash> {
            DeepSearchPlannerV2(
                retryPolicy = DeepSearchRetryPolicy(
                    maxAttemptsPerExpansion = 3,
                    baseBackoffMillis = 1,
                    maxBackoffMillis = 1,
                ),
                now = { at },
            ).search(
                request = request,
                sources = listOf(source),
                checkpointSink = DeepSearchCheckpointSink { checkpoint ->
                    durable = checkpoint
                    if (checkpoint.trace.any {
                            it.type == DeepSearchTraceType.SOURCE_RETRY_SCHEDULED
                        }
                    ) {
                        throw SimulatedCrash()
                    }
                },
            )
        }

        val resumed = DeepSearchPlannerV2(
            retryPolicy = DeepSearchRetryPolicy(
                maxAttemptsPerExpansion = 3,
                baseBackoffMillis = 1,
                maxBackoffMillis = 1,
            ),
            now = { at },
        ).search(
            request = request,
            sources = listOf(source),
            resume = assertNotNull(durable),
        )

        assertEquals(DeepSearchStatus.RESOLVED, resumed.status)
        assertEquals(2, source.calls)
        assertEquals(1, resumed.workUnitsUsed)
        assertEquals(
            1,
            resumed.trace.count { it.type == DeepSearchTraceType.EXPANSION_RESERVED },
        )
    }

    @Test
    fun `cancellation is checkpointed before it is propagated`() = runTest {
        val request = request(maxWorkUnits = 1)
        var durable: DeepSearchPlannerCheckpoint? = null

        val error = assertFailsWith<CancellationException> {
            DeepSearchPlannerV2(now = { at }).search(
                request = request,
                sources = listOf(CancellingSource()),
                checkpointSink = DeepSearchCheckpointSink { durable = it },
            )
        }

        assertEquals("test-cancel", error.message)
        val checkpoint = assertNotNull(durable)
        assertEquals(1, checkpoint.workUnitsUsed)
        assertTrue(checkpoint.trace.any { it.type == DeepSearchTraceType.SEARCH_CANCELLED })
        assertTrue(checkpoint.trace.any { it.type == DeepSearchTraceType.EXPANSION_RESERVED })
    }

    private fun request(maxWorkUnits: Int) = DeepSearchRequest(
        query = "lifeos photons",
        contextTerms = setOf("memory"),
        budget = DeepSearchBudget(
            maxDepth = 2,
            maxBreadth = 4,
            maxWorkUnits = maxWorkUnits,
            maxElapsed = Duration.ofSeconds(4),
        ),
        minimumResolutionScore = 0.0,
        minimumWinnerMargin = 0.0,
    )

    private class CountingSource : DeepSearchSource {
        var calls: Int = 0

        override val descriptor = DeepSearchSourceDescriptor(
            sourceId = "test-local",
            kind = DeepSearchSourceKind.LOCAL,
            reliability = 1.0,
            workUnitsPerExpansion = 1,
        )

        override suspend fun expand(
            request: DeepSearchRequest,
            branch: DeepSearchBranch,
        ): List<DeepSearchFindingDraft> {
            calls += 1
            if (branch.depth > 0) return emptyList()
            return listOf(
                DeepSearchFindingDraft(
                    statement = "LIFEOS stores information as photons in memory fields.",
                    semanticTerms = setOf("lifeos", "photons", "memory"),
                    confidence = 0.95,
                    evidence = listOf(
                        DeepSearchEvidenceDraft(
                            statement = "Photon memory evidence",
                            confidence = 0.95,
                            sourcePhotonId = PhotonId("evidence-photon"),
                        )
                    ),
                )
            )
        }
    }

    private class FlakySource(
        private val failuresBeforeSuccess: Int,
    ) : DeepSearchSource {
        var calls: Int = 0

        override val descriptor = DeepSearchSourceDescriptor(
            sourceId = "flaky-local",
            kind = DeepSearchSourceKind.LOCAL,
            reliability = 1.0,
            workUnitsPerExpansion = 1,
        )

        override suspend fun expand(
            request: DeepSearchRequest,
            branch: DeepSearchBranch,
        ): List<DeepSearchFindingDraft> {
            calls += 1
            if (calls <= failuresBeforeSuccess) {
                throw IOException("transient-$calls")
            }
            if (branch.depth > 0) return emptyList()
            return listOf(
                DeepSearchFindingDraft(
                    statement = "Recovered source evidence",
                    semanticTerms = setOf("lifeos", "photons", "memory"),
                    confidence = 0.95,
                    evidence = listOf(
                        DeepSearchEvidenceDraft(
                            statement = "Recovered evidence",
                            confidence = 0.95,
                            sourcePhotonId = PhotonId("recovered-evidence-photon"),
                        )
                    ),
                )
            )
        }
    }

    private class PermanentFailureSource : DeepSearchSource {
        var calls: Int = 0

        override val descriptor = DeepSearchSourceDescriptor(
            sourceId = "permanent-local",
            kind = DeepSearchSourceKind.LOCAL,
            reliability = 1.0,
            workUnitsPerExpansion = 1,
        )

        override suspend fun expand(
            request: DeepSearchRequest,
            branch: DeepSearchBranch,
        ): List<DeepSearchFindingDraft> {
            calls += 1
            throw IllegalArgumentException("permanent-test-failure")
        }
    }

    private class CancellingSource : DeepSearchSource {
        override val descriptor = DeepSearchSourceDescriptor(
            sourceId = "cancelling-local",
            kind = DeepSearchSourceKind.LOCAL,
            reliability = 1.0,
            workUnitsPerExpansion = 1,
        )

        override suspend fun expand(
            request: DeepSearchRequest,
            branch: DeepSearchBranch,
        ): List<DeepSearchFindingDraft> {
            throw CancellationException("test-cancel")
        }
    }

    private class FixedGate(private val allowed: Boolean) : DeepSearchCapabilityGate {
        override suspend fun authorize(source: DeepSearchSourceDescriptor) =
            DeepSearchSourceAuthorization(
                allowed = allowed,
                reason = if (allowed) "test-allowed" else "test-denied",
            )
    }

    private class SimulatedCrash : RuntimeException()
}
