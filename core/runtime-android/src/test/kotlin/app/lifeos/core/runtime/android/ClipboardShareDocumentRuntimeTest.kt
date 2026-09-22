package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.capability.CapabilityContract
import app.lifeos.core.runtime.capability.CapabilityDescriptor
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClipboardShareDocumentRuntimeTest {
    @Test
    fun public_content_uri_rejects_file_and_http_schemes() {
        assertFailsWith<IllegalArgumentException> {
            PublicContentUri.create("file:///sdcard/a.txt")
        }
        assertFailsWith<IllegalArgumentException> {
            PublicContentUri.create("https://example.com/a.txt")
        }

        val uri = PublicContentUri.create("content://app.lifeos.next.share/a/../b.txt")
        assertEquals("app.lifeos.next.share", uri.authority)
        assertTrue(uri.value.startsWith("content://"))
    }

    @Test
    fun prepared_share_and_document_grant_no_execution_authority() {
        val host = FakeHandoffHost()
        val runtime = runtime(host)
        val share = runtime.prepareShare(
            plan(ClipboardShareDocumentOperation.SHARE_PREPARE),
            SharePayload.Text(ShareText("hello")),
        )
        val document = runtime.prepareDocument(
            plan(ClipboardShareDocumentOperation.DOCUMENT_OPEN),
            DocumentHandle(
                PublicContentUri.create("content://app.example/doc/1"),
                "application/pdf",
            ),
        )

        assertFalse(share.executionAuthority)
        assertFalse(document.executionAuthority)
    }

    @Test
    fun clipboard_write_without_owner_grant_never_reaches_host() = runTest {
        val host = FakeHandoffHost()
        val runtime = runtime(host)

        val result = runtime.writeClipboard(
            plan = plan(ClipboardShareDocumentOperation.CLIPBOARD_WRITE),
            actorId = OWNER,
            text = ClipboardText("public text"),
        )

        assertIs<HandoffExecutionResult.Blocked>(result)
        assertEquals(0, host.clipboardWrites)
    }

    @Test
    fun owner_revocation_after_share_preparation_wins_before_host_launch() = runTest {
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { NOW }
        val gate = OwnerPolicyEffectGate(ledger)
        val fileRuntime = FileActionRuntime(FakeFileHost(), gate)
        val host = FakeHandoffHost()
        val runtime = ClipboardShareDocumentRuntime(host, gate, fileRuntime)
        val plan = plan(ClipboardShareDocumentOperation.SHARE_LAUNCH)
        ledger.grant(grant(plan, OwnerEffectType.EXTERNAL_APP_HANDOFF))
        val share = PreparedShare.create(SharePayload.Text(ShareText("hello")))
        val prepared = assertIs<OwnerEffectPreparationResult.Ready>(
            runtime.prepareShareLaunch(plan, OWNER, share)
        ).preparation
        val active = ledger.snapshot().activeGrants.single()
        ledger.revoke(active.id)

        val result = runtime.launchShare(plan, OWNER, share, prepared)

        assertIs<HandoffExecutionResult.Blocked>(result)
        assertEquals(0, host.shareLaunches)
    }

    @Test
    fun successful_content_share_preserves_exact_uri_mime_and_package() = runTest {
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { NOW }
        val gate = OwnerPolicyEffectGate(ledger)
        val runtime = ClipboardShareDocumentRuntime(
            FakeHandoffHost(),
            gate,
            FileActionRuntime(FakeFileHost(), gate),
        )
        val plan = plan(ClipboardShareDocumentOperation.SHARE_LAUNCH)
        ledger.grant(grant(plan, OwnerEffectType.EXTERNAL_APP_HANDOFF))
        val share = PreparedShare.create(
            payload = SharePayload.Content(
                uri = PublicContentUri.create("content://app.example/share/1"),
                mimeType = "image/png",
                displayName = "image.png",
            ),
            exactPackage = "receiver.example",
        )

        val result = assertIs<HandoffExecutionResult.Exposed>(
            runtime.launchShare(plan, OWNER, share)
        )

        assertEquals("receiver.example", result.receipt.exactPackage)
        assertEquals("image/png", result.receipt.mimeType)
        assertEquals("content://app.example/share/1", result.receipt.uri?.value)
        assertTrue(result.policyAssessment.allowed)
    }

    @Test
    fun persistent_export_delegates_authority_to_b406_file_write() = runTest {
        val repository = TestRepository()
        val ledger = OwnerPolicyLedger(repository) { NOW }
        val gate = OwnerPolicyEffectGate(ledger)
        val fileHost = FakeFileHost()
        val fileRuntime = FileActionRuntime(fileHost, gate)
        val runtime = ClipboardShareDocumentRuntime(FakeHandoffHost(), gate, fileRuntime)
        val b410Plan = plan(ClipboardShareDocumentOperation.DOCUMENT_EXPORT)
        val filePlan = fileWritePlan()
        ledger.grant(fileGrant(filePlan))
        val destination = AndroidFileRef.create("shared-primary", "Documents/export.txt")
        val request = DocumentExportRequest(
            FileWriteRequest(
                destination = destination,
                payload = FilePayload.create("export".encodeToByteArray()),
            )
        )

        val result = runtime.exportDocument(
            plan = b410Plan,
            fileWritePlan = filePlan,
            actorId = OWNER,
            request = request,
        )

        val mutation = assertIs<FileActionResult.Mutation>(result)
        assertEquals(destination, mutation.destinationRevision.ref)
        assertEquals(1, fileHost.writeCalls)
    }

    private fun runtime(host: FakeHandoffHost): ClipboardShareDocumentRuntime {
        val ledger = OwnerPolicyLedger(TestRepository()) { NOW }
        val gate = OwnerPolicyEffectGate(ledger)
        return ClipboardShareDocumentRuntime(
            host = host,
            ownerPolicyGate = gate,
            fileActionRuntime = FileActionRuntime(FakeFileHost(), gate),
        )
    }

    private fun plan(
        operation: ClipboardShareDocumentOperation,
    ): AndroidCapabilityDispatchPlan {
        val descriptor = CapabilityDescriptor(
            capabilityId = operation.capabilityId,
            providerId = "android.handoff",
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
            requestFingerprint = "f".repeat(64),
            binding = AndroidCapabilityBinding(
                descriptor = descriptor,
                providerVersion = "b410-v1",
                requiredOwnerEffect =
                    if (operation.productiveHandoff) {
                        OwnerEffectType.EXTERNAL_APP_HANDOFF
                    } else {
                        null
                    },
                ownerScope = operation.capabilityValue,
                permissions = emptyList(),
                riskClass =
                    if (operation.productiveHandoff) {
                        AndroidCapabilityRiskClass.MEDIUM
                    } else {
                        AndroidCapabilityRiskClass.LOW
                    },
                reversibility = AndroidCapabilityReversibility.COMPENSATABLE,
                recoverySemantics = AndroidRecoverySemantics.MANUAL_REVIEW,
                expectedOutcomeContract = "result",
            ),
        )
    }

    private fun fileWritePlan(): AndroidCapabilityDispatchPlan {
        val descriptor = CapabilityDescriptor(
            capabilityId = FileActionKind.WRITE.capabilityId,
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
            requestFingerprint = "a".repeat(64),
            binding = AndroidCapabilityBinding(
                descriptor = descriptor,
                providerVersion = "b406-v1",
                requiredOwnerEffect = OwnerEffectType.FILE_WRITE,
                ownerScope = FileActionKind.WRITE.capabilityValue,
                permissions = emptyList(),
                riskClass = AndroidCapabilityRiskClass.MEDIUM,
                reversibility = AndroidCapabilityReversibility.REVERSIBLE,
                recoverySemantics = AndroidRecoverySemantics.RETRY_SAFE,
                expectedOutcomeContract = "result",
            ),
        )
    }

    private fun grant(
        plan: AndroidCapabilityDispatchPlan,
        effect: OwnerEffectType,
    ): OwnerPolicyGrant = OwnerPolicyGrant.create(
        actorId = OWNER,
        effect = effect,
        resource = OwnerResourceSelector(OwnerResourceSelectorType.ANY),
        scope = plan.binding.ownerScope,
        capability = OwnerCapabilityConstraint(
            capabilityId = plan.capabilityId,
            providerVersion = plan.binding.providerVersion,
        ),
        validFrom = NOW.minusSeconds(1),
    )

    private fun fileGrant(
        plan: AndroidCapabilityDispatchPlan,
    ): OwnerPolicyGrant = grant(plan, OwnerEffectType.FILE_WRITE)

    private class FakeHandoffHost : ClipboardShareDocumentHost {
        var clipboardWrites = 0
        var shareLaunches = 0
        var documentOpens = 0
        var clipboard: ClipboardText? = null

        override suspend fun readClipboard(): ClipboardText? = clipboard

        override suspend fun writeClipboard(text: ClipboardText): String {
            clipboardWrites += 1
            clipboard = text
            return text.fingerprint()
        }

        override suspend fun launchShare(prepared: PreparedShare): HandoffReceipt {
            shareLaunches += 1
            return HandoffReceipt.share(prepared)
        }

        override suspend fun openDocument(
            prepared: PreparedDocumentHandoff,
        ): HandoffReceipt {
            documentOpens += 1
            return HandoffReceipt.document(prepared)
        }
    }

    private class FakeFileHost : FileActionHost {
        var writeCalls = 0

        override suspend fun search(query: FileSearchQuery): List<AndroidFileRevision> = emptyList()

        override suspend fun read(request: FileReadRequest): FileReadPayload =
            error("unused")

        override suspend fun write(request: FileWriteRequest): AndroidFileRevision {
            writeCalls += 1
            return AndroidFileRevision(
                ref = request.destination,
                sizeBytes = request.payload.sizeBytes.toLong(),
                modifiedAtMillis = 1L,
                sha256 = request.payload.sha256,
            )
        }

        override suspend fun copy(request: FileCopyRequest): AndroidFileRevision =
            error("unused")

        override suspend fun move(request: FileMoveRequest): AndroidFileRevision =
            error("unused")
    }

    private class TestRepository : OwnerPolicyRepository {
        val events = mutableListOf<OwnerPolicyEvent>()

        override suspend fun loadReport() =
            OwnerPolicyRepositoryLoadReport(events.toList())

        override suspend fun append(
            expectedRevision: Long,
            event: OwnerPolicyEvent,
        ): Boolean {
            val current = events.lastOrNull()?.revision ?: 0L
            if (current != expectedRevision) return false
            require(event.revision == expectedRevision + 1L)
            events += event
            return true
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-22T00:00:00Z")
        val OWNER = OwnerActorId("clipboard-share-document-runtime")
    }
}
