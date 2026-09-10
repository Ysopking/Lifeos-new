package app.lifeos.core.runtime.deepsearch

import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.CapabilityRegistry
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeepSearchPlannerTest {
    private val at = Instant.parse("2026-09-10T13:00:00Z")

    @Test
    fun `no sources terminates explicitly without work`() = runTest {
        val result = DeepSearchPlanner(now = { at }).search(
            request = request(),
            sources = emptyList(),
        )

        assertEquals(DeepSearchStatus.NO_USABLE_SOURCE, result.status)
        assertEquals(0, result.workUnitsUsed)
        assertTrue(result.evidence.isEmpty())
        assertTrue(result.trace.any { it.type == DeepSearchTraceType.UNRESOLVED })
    }

    @Test
    fun `external source is denied by default even when descriptor says permission granted`() = runTest {
        val external = FakeSource(
            DeepSearchSourceDescriptor(
                sourceId = "external",
                kind = DeepSearchSourceKind.EXTERNAL,
                capabilityId = CapabilityId("search.external"),
                permissionState = DeepSearchPermissionState.GRANTED,
            ),
            findings = listOf(finding("alpha beta")),
        )

        val result = DeepSearchPlanner(now = { at }).search(request(), listOf(external))

        assertEquals(DeepSearchStatus.PERMISSION_BLOCKED, result.status)
        assertEquals(setOf("external"), result.blockedSourceIds)
        assertEquals(0, external.expansions)
        assertTrue(result.trace.any {
            it.type == DeepSearchTraceType.SOURCE_BLOCKED &&
                it.detail == "external-source-requires-explicit-capability-and-permission"
        })
    }

    @Test
    fun `external source requires both usable capability and granted permission`() = runTest {
        val capabilityId = CapabilityId("search.external")
        val registry = CapabilityRegistry(
            listOf(
                CapabilityDescriptor(
                    capabilityId = capabilityId,
                    providerId = "connector-search",
                    providerType = ProviderType.CONNECTOR,
                    state = ProviderState.ACTIVE,
                    trustLevel = TrustLevel.SYSTEM,
                )
            )
        )
        val granted = FakeSource(
            DeepSearchSourceDescriptor(
                sourceId = "external-granted",
                kind = DeepSearchSourceKind.EXTERNAL,
                capabilityId = capabilityId,
                permissionState = DeepSearchPermissionState.GRANTED,
            ),
            findings = listOf(finding("alpha beta")),
        )
        val denied = FakeSource(
            DeepSearchSourceDescriptor(
                sourceId = "external-denied",
                kind = DeepSearchSourceKind.EXTERNAL,
                capabilityId = capabilityId,
                permissionState = DeepSearchPermissionState.DENIED,
            ),
            findings = listOf(finding("alpha beta")),
        )
        val gate = CapabilityRegistryDeepSearchGate(registry)

        val grantedResult = DeepSearchPlanner(capabilityGate = gate, now = { at })
            .search(request(minimumResolutionScore = 0.7), listOf(granted))
        val deniedResult = DeepSearchPlanner(capabilityGate = gate, now = { at })
            .search(request(), listOf(denied))

        assertEquals(DeepSearchStatus.RESOLVED, grantedResult.status)
        assertEquals(1, granted.expansions)
        assertEquals(DeepSearchStatus.PERMISSION_BLOCKED, deniedResult.status)
        assertEquals(0, denied.expansions)
        assertEquals(setOf("external-denied"), deniedResult.blockedSourceIds)
    }

    @Test
    fun `work budget stops expansion before source can exceed configured units`() = runTest {
        val source = FakeSource(
            descriptor = DeepSearchSourceDescriptor(
                sourceId = "costly-local",
                kind = DeepSearchSourceKind.LOCAL,
                workUnitsPerExpansion = 2,
            ),
            findings = listOf(finding("alpha beta")),
        )
        val result = DeepSearchPlanner(now = { at }).search(
            request = request(
                budget = DeepSearchBudget(
                    maxDepth = 2,
                    maxBreadth = 2,
                    maxWorkUnits = 3,
                    maxElapsed = Duration.ofSeconds(5),
                ),
                minimumResolutionScore = 1.0,
                minimumWinnerMargin = 1.0,
            ),
            sources = listOf(source),
        )

        assertEquals(DeepSearchStatus.WORK_BUDGET_EXHAUSTED, result.status)
        assertEquals(2, result.workUnitsUsed)
        assertEquals(1, source.expansions)
        assertTrue(result.trace.any { it.type == DeepSearchTraceType.WORK_LIMIT_REACHED })
    }

    @Test
    fun `depth budget stops recursive expansion explicitly`() = runTest {
        val source = FakeSource(
            DeepSearchSourceDescriptor("local", DeepSearchSourceKind.LOCAL),
            findings = listOf(finding("alpha beta")),
        )
        val result = DeepSearchPlanner(now = { at }).search(
            request = request(
                budget = DeepSearchBudget(
                    maxDepth = 1,
                    maxBreadth = 2,
                    maxWorkUnits = 10,
                    maxElapsed = Duration.ofSeconds(5),
                ),
                minimumResolutionScore = 1.0,
                minimumWinnerMargin = 1.0,
            ),
            sources = listOf(source),
        )

        assertEquals(DeepSearchStatus.UNRESOLVED, result.status)
        assertEquals(1, source.expansions)
        assertTrue(result.trace.any { it.type == DeepSearchTraceType.DEPTH_LIMIT_REACHED })
    }

    @Test
    fun `elapsed budget can terminate before first source expansion`() = runTest {
        val clock = SequenceClock(
            mutableListOf(
                at,
                at,
                at.plusSeconds(2),
            )
        )
        val source = FakeSource(
            DeepSearchSourceDescriptor("local", DeepSearchSourceKind.LOCAL),
            findings = listOf(finding("alpha beta")),
        )
        val result = DeepSearchPlanner(now = clock::now).search(
            request = request(
                budget = DeepSearchBudget(
                    maxDepth = 2,
                    maxBreadth = 2,
                    maxWorkUnits = 10,
                    maxElapsed = Duration.ofSeconds(1),
                )
            ),
            sources = listOf(source),
        )

        assertEquals(DeepSearchStatus.TIME_BUDGET_EXHAUSTED, result.status)
        assertEquals(0, source.expansions)
        assertTrue(result.trace.any { it.type == DeepSearchTraceType.TIME_LIMIT_REACHED })
    }

    @Test
    fun `source failure is contained and remains traceable`() = runTest {
        val source = FakeSource(
            DeepSearchSourceDescriptor("broken-local", DeepSearchSourceKind.LOCAL),
            failure = IllegalStateException("index unavailable"),
        )

        val result = DeepSearchPlanner(now = { at }).search(
            request = request(
                budget = DeepSearchBudget(maxDepth = 1, maxBreadth = 2, maxWorkUnits = 4),
                minimumResolutionScore = 1.0,
            ),
            sources = listOf(source),
        )

        assertEquals(DeepSearchStatus.UNRESOLVED, result.status)
        assertEquals(setOf("broken-local"), result.failedSourceIds)
        assertEquals(1, result.workUnitsUsed)
        assertTrue(result.trace.any { it.type == DeepSearchTraceType.SOURCE_FAILED })
    }

    @Test
    fun `cancellation from a source propagates instead of becoming failed evidence`() = runTest {
        val source = object : DeepSearchSource {
            override val descriptor = DeepSearchSourceDescriptor("cancel-local", DeepSearchSourceKind.LOCAL)
            override suspend fun expand(
                request: DeepSearchRequest,
                branch: DeepSearchBranch,
            ): List<DeepSearchFindingDraft> = throw CancellationException("cancelled")
        }

        assertFailsWith<CancellationException> {
            DeepSearchPlanner(now = { at }).search(request(), listOf(source))
        }
    }

    @Test
    fun `source and finding order cannot change deterministic offline result`() = runTest {
        val aFindings = listOf(
            finding("alpha beta gamma", confidence = 0.7),
            finding("alpha beta delta", confidence = 0.8),
        )
        val zFindings = listOf(
            finding("alpha beta epsilon", confidence = 0.65),
            finding("alpha beta zeta", confidence = 0.75),
        )
        val request = request(
            budget = DeepSearchBudget(maxDepth = 1, maxBreadth = 4, maxWorkUnits = 8),
            minimumResolutionScore = 1.0,
            minimumWinnerMargin = 1.0,
        )

        val left = DeepSearchPlanner(now = { at }).search(
            request,
            listOf(
                FakeSource(DeepSearchSourceDescriptor("z", DeepSearchSourceKind.LOCAL), zFindings),
                FakeSource(DeepSearchSourceDescriptor("a", DeepSearchSourceKind.LOCAL), aFindings),
            ),
        )
        val right = DeepSearchPlanner(now = { at }).search(
            request,
            listOf(
                FakeSource(DeepSearchSourceDescriptor("a", DeepSearchSourceKind.LOCAL), aFindings.reversed()),
                FakeSource(DeepSearchSourceDescriptor("z", DeepSearchSourceKind.LOCAL), zFindings.reversed()),
            ),
        )

        assertEquals(left, right)
        assertFalse(left.evidence.isEmpty())
        assertEquals(listOf("a", "z"), left.trace
            .filter { it.type == DeepSearchTraceType.SOURCE_AUTHORIZED }
            .mapNotNull { it.sourceId })
    }

    private fun request(
        budget: DeepSearchBudget = DeepSearchBudget(maxDepth = 2, maxBreadth = 4, maxWorkUnits = 16),
        minimumResolutionScore: Double = 0.68,
        minimumWinnerMargin: Double = 0.08,
    ) = DeepSearchRequest(
        query = "alpha beta",
        contextTerms = setOf("context"),
        budget = budget,
        minimumResolutionScore = minimumResolutionScore,
        minimumWinnerMargin = minimumWinnerMargin,
    )

    private fun finding(
        statement: String,
        confidence: Double = 1.0,
    ) = DeepSearchFindingDraft(
        statement = statement,
        semanticTerms = tokenizeSearchText(statement),
        confidence = confidence,
        evidence = listOf(
            DeepSearchEvidenceDraft(
                statement = "evidence for $statement",
                confidence = confidence,
            )
        ),
    )

    private class FakeSource(
        override val descriptor: DeepSearchSourceDescriptor,
        private val findings: List<DeepSearchFindingDraft> = emptyList(),
        private val failure: Exception? = null,
    ) : DeepSearchSource {
        var expansions: Int = 0
            private set

        override suspend fun expand(
            request: DeepSearchRequest,
            branch: DeepSearchBranch,
        ): List<DeepSearchFindingDraft> {
            expansions += 1
            if (failure != null) throw failure
            return findings
        }
    }

    private class SequenceClock(
        private val values: MutableList<Instant>,
    ) {
        private var last: Instant = values.first()

        fun now(): Instant {
            if (values.isNotEmpty()) last = values.removeAt(0)
            return last
        }
    }
}
