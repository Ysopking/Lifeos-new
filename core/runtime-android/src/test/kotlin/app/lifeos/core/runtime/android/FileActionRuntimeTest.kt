package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.capability.ProviderState
import app.lifeos.core.runtime.capability.ProviderType
import app.lifeos.core.runtime.capability.TrustLevel
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerCapabilityConstraint
import app.lifeos.core.runtime.policy.OwnerEffectPreparationResult
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import app.lifeos.core.runtime.policy.OwnerPolicyEvent
import app.lifeos.core.runtime.policy.OwnerPolicyGrant
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.policy.OwnerPolicyRepository
import app.lifeos.core.runtime.policy.OwnerPolicyRepositoryLoadReport
import app.lifeos.core.runtime.policy.OwnerResourceSelector
import app.lifeos.core.runtime.policy.OwnerResourceSelectorType
import java.time.Instant
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FileActionRuntimeTest {
    @Test
    fun file_ref_normalizes_separator_and_rejects_escape_paths() {
        val ref = AndroidFileRef.create("shared-primary", "Documents\\Report.txt")
        assertEquals("Documents/Report.txt", ref.relativePath)

        assertFailsWith<IllegalArgumentException> {
            AndroidFileRef.create("shared-primary", "../secret.txt")
        }
        assertFailsWith<IllegalArgumentException> {
            AndroidFileRef.create("shared-primary", "/absolute.txt")
        }
        assertFailsWith<IllegalArgumentException> {
            AndroidFileRef.create("shared-primary", "Documents//Report.txt")
        }
        assertFailsWith<IllegalArgumentException> {
            AndroidFileRef.create("shared-primary", "C:\\secret.txt")
        }
    }

    @Test
    fun read_is_bounded_and_exact_revision_is_preserved() = runTest {
        val now = Instant.parse("2026-09-22T00:00:00Z")
        val host = FakeHost()
        val runtime = FileActionRuntime(
            host = host,
            ownerPolicyGate = OwnerPolicyEffectGate(OwnerPolicyLedger(TestRepository()) { now }),
        )
        val ref = AndroidFileRef.create("shared-primary", "Documents/report.txt")
        val revision = AndroidFileRevision(ref, 5L, 10L, "a".repeat(64))
        host.current[ref] = revision
        host.bytes[ref] = "hello".encodeToByteArray()

        val result = runtime.read(
            plan = plan(FileActionKind.READ, ownerEffect = null),
            request = FileReadRequest(
                ref = ref,
                expectedRevision = revision,
                maxBytes = 5,
            ),
        )

        assertEquals(revision, result.revision)
        assertContentEquals("hello".encodeToByteArray(), result.payload.copyBytes())
        assertTrue(result.fingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun host_cannot_return_more_bytes_than_declared_read_bound() = runTest {
        val host = FakeHost()
        val ref = AndroidFileRef.create("shared-primary", "a.txt")
        val revision = AndroidFileRevision(ref, 6L, 1L)
        host.current[ref] = revision
        host.bytes[ref] = "123456".encodeToByteArray()
        val runtime = runtime(host)

        assertFailsWith<IllegalArgumentException> {
            runtime.read(
                plan(FileActionKind.READ, null),
                FileReadRequest(ref = ref, expectedRevision = revision, maxBytes = 5),
            )
        }
    }

    @Test
    fun mutation_requires_file_write_metadata_before_host_exposure() = runTest {
        val host = FakeHost()
        val runtime = runtime(host)
        val request = FileWriteRequest(
            destination = AndroidFileRef.create("shared-primary", "Documents/new.txt"),
            payload = FilePayload.create("new".encodeToByteArray()),
        )

        assertFailsWith<IllegalArgumentException> {
            runtime.write(
                plan = plan(FileActionKind.WRITE, OwnerEffectType.EXTERNAL_APP_HANDOFF),
                actorId = OWNER,
                request = request,
            )
        }
        assertEquals(0, host.writeCalls)
    }

    @Test
    fun mutation_without_live_owner_grant_never_calls_host() = runTest {
        val host = FakeHost()
        val runtime = runtime(host)
        val request = FileWriteRequest(
            destination = AndroidFileRef.create("shared-primary", "Documents/new.txt"),
            payload = FilePayload.create("new".encodeToByteArray()),
        )

        val result = runtime.write(
            plan = plan(FileActionKind.WRITE, OwnerEffectType.FILE_WRITE),
            actorId = OWNER,
            request = request,
        )

        assertIs<FileActionResult.Blocked>(result)
        assertEquals(0, host.writeCalls)
    }

    @Test
    fun prepared_policy_revision_change_fails_closed_before_host_call() = runTest {
        val now = Instant.parse("2026-09-22T00:00:00Z")
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { now }
        val gate = OwnerPolicyEffectGate(ledger)
        val host = FakeHost()
        val runtime = FileActionRuntime(host, gate)
        val plan = plan(FileActionKind.WRITE, OwnerEffectType.FILE_WRITE)
        val request = FileWriteRequest(
            destination = AndroidFileRef.create("shared-primary", "Documents/new.txt"),
            payload = FilePayload.create("new".encodeToByteArray()),
        )
        ledger.grant(grant(now, plan))
        val prepared = assertIs<OwnerEffectPreparationResult.Ready>(
            runtime.prepareWrite(plan, OWNER, request)
        ).preparation

        ledger.grant(
            OwnerPolicyGrant.create(
                actorId = OwnerActorId("other"),
                effect = OwnerEffectType.FILE_WRITE,
                resource = OwnerResourceSelector(OwnerResourceSelectorType.ANY),
                scope = "other",
                validFrom = now.minusSeconds(1),
            )
        )

        val result = runtime.write(plan, OWNER, request, prepared)

        assertIs<FileActionResult.Blocked>(result)
        assertEquals(0, host.writeCalls)
    }

    @Test
    fun unchanged_live_grant_exposes_exact_write_once() = runTest {
        val now = Instant.parse("2026-09-22T00:00:00Z")
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { now }
        val plan = plan(FileActionKind.WRITE, OwnerEffectType.FILE_WRITE)
        ledger.grant(grant(now, plan))
        val host = FakeHost()
        val runtime = FileActionRuntime(host, OwnerPolicyEffectGate(ledger))
        val destination = AndroidFileRef.create("shared-primary", "Documents/new.txt")
        val request = FileWriteRequest(
            destination = destination,
            payload = FilePayload.create("new".encodeToByteArray()),
        )

        val result = runtime.write(plan, OWNER, request)

        val mutation = assertIs<FileActionResult.Mutation>(result)
        assertEquals(1, host.writeCalls)
        assertEquals(destination, mutation.destinationRevision.ref)
        assertEquals(3L, mutation.destinationRevision.sizeBytes)
        assertTrue(mutation.policyAssessment.allowed)
    }

    @Test
    fun dispatch_identity_and_payload_identity_are_deterministic() {
        val ref = AndroidFileRef.create("shared-primary", "Documents/new.txt")
        val firstPayload = FilePayload.create("same".encodeToByteArray())
        val secondPayload = FilePayload.create("same".encodeToByteArray())
        val first = FileWriteRequest(ref, firstPayload)
        val second = FileWriteRequest(ref, secondPayload)
        val changed = FileWriteRequest(ref, FilePayload.create("different".encodeToByteArray()))

        assertEquals(first.fingerprint(), second.fingerprint())
        assertNotEquals(first.fingerprint(), changed.fingerprint())
    }

    private fun runtime(host: FakeHost): FileActionRuntime {
        val now = Instant.parse("2026-09-22T00:00:00Z")
        return FileActionRuntime(
            host = host,
            ownerPolicyGate = OwnerPolicyEffectGate(OwnerPolicyLedger(TestRepository()) { now }),
        )
    }

    private fun plan(
        kind: FileActionKind,
        ownerEffect: OwnerEffectType?,
    ): AndroidCapabilityDispatchPlan {
        val capability = kind.capabilityId
        val descriptor = CapabilityDescriptor(
            capabilityId = capability,
            providerId = "android.files",
            providerType = ProviderType.MODULE,
            contract = CapabilityContract(
                requiredInputs = setOf("request"),
                outputs = setOf("result"),
            ),
            state = ProviderState.ACTIVE,
            trustLevel = TrustLevel.SYSTEM,
            reliability = 1.0,
        )
        return AndroidCapabilityDispatchPlan(
            requestFingerprint = "b".repeat(64),
            binding = AndroidCapabilityBinding(
                descriptor = descriptor,
                providerVersion = "b406-v1",
                requiredOwnerEffect = ownerEffect,
                ownerScope = kind.capabilityValue,
                permissions = emptyList(),
                riskClass = if (kind.mutating) AndroidCapabilityRiskClass.MEDIUM else AndroidCapabilityRiskClass.LOW,
                reversibility = if (kind == FileActionKind.MOVE || kind == FileActionKind.ORGANIZE) {
                    AndroidCapabilityReversibility.COMPENSATABLE
                } else {
                    AndroidCapabilityReversibility.REVERSIBLE
                },
                recoverySemantics = AndroidRecoverySemantics.RETRY_SAFE,
                expectedOutcomeContract = "result",
            ),
        )
    }

    private fun grant(
        now: Instant,
        plan: AndroidCapabilityDispatchPlan,
    ): OwnerPolicyGrant = OwnerPolicyGrant.create(
        actorId = OWNER,
        effect = OwnerEffectType.FILE_WRITE,
        resource = OwnerResourceSelector(OwnerResourceSelectorType.ANY),
        scope = plan.binding.ownerScope,
        capability = OwnerCapabilityConstraint(
            capabilityId = plan.capabilityId,
            providerVersion = plan.binding.providerVersion,
        ),
        validFrom = now.minusSeconds(1),
    )

    private class FakeHost : FileActionHost {
        val current = linkedMapOf<AndroidFileRef, AndroidFileRevision>()
        val bytes = linkedMapOf<AndroidFileRef, ByteArray>()
        var writeCalls = 0

        override suspend fun search(query: FileSearchQuery): List<AndroidFileRevision> =
            current.values.filter {
                query.query.isBlank() ||
                    it.ref.relativePath.contains(query.query, ignoreCase = true)
            }

        override suspend fun read(request: FileReadRequest): FileReadPayload {
            val revision = requireNotNull(current[request.ref])
            request.expectedRevision?.let {
                require(it == revision) { "revision-mismatch" }
            }
            return FileReadPayload(
                revision = revision,
                payload = FilePayload.create(requireNotNull(bytes[request.ref])),
            )
        }

        override suspend fun write(request: FileWriteRequest): AndroidFileRevision {
            writeCalls += 1
            val existing = current[request.destination]
            require(request.expectedDestinationRevision == existing) {
                "destination-revision-mismatch"
            }
            val payload = request.payload.copyBytes()
            val revision = AndroidFileRevision(
                ref = request.destination,
                sizeBytes = payload.size.toLong(),
                modifiedAtMillis = 100L + writeCalls,
                sha256 = request.payload.sha256,
            )
            bytes[request.destination] = payload
            current[request.destination] = revision
            return revision
        }

        override suspend fun copy(request: FileCopyRequest): AndroidFileRevision {
            val source = requireNotNull(current[request.source])
            require(source == request.expectedSourceRevision) { "source-revision-mismatch" }
            require(current[request.destination] == request.expectedDestinationRevision) {
                "destination-revision-mismatch"
            }
            val payload = requireNotNull(bytes[request.source]).copyOf()
            val revision = AndroidFileRevision(
                request.destination,
                payload.size.toLong(),
                source.modifiedAtMillis + 1L,
                source.sha256,
            )
            bytes[request.destination] = payload
            current[request.destination] = revision
            return revision
        }

        override suspend fun move(request: FileMoveRequest): AndroidFileRevision {
            val source = requireNotNull(current[request.source])
            require(source == request.expectedSourceRevision) { "source-revision-mismatch" }
            require(current[request.destination] == request.expectedDestinationRevision) {
                "destination-revision-mismatch"
            }
            val payload = requireNotNull(bytes[request.source])
            val revision = AndroidFileRevision(
                request.destination,
                payload.size.toLong(),
                source.modifiedAtMillis + 1L,
                source.sha256,
            )
            current.remove(request.source)
            bytes.remove(request.source)
            current[request.destination] = revision
            bytes[request.destination] = payload
            return revision
        }
    }

    private class TestRepository : OwnerPolicyRepository {
        val events = mutableListOf<OwnerPolicyEvent>()

        override suspend fun loadReport() = OwnerPolicyRepositoryLoadReport(events.toList())

        override suspend fun append(expectedRevision: Long, event: OwnerPolicyEvent): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            require(event.revision == expectedRevision + 1L)
            events += event
            return true
        }
    }

    private companion object {
        val OWNER = OwnerActorId("file-action-runtime")
    }
}
