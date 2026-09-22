package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectExposureResult
import app.lifeos.core.runtime.policy.OwnerEffectPreparation
import app.lifeos.core.runtime.policy.OwnerEffectPreparationResult
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyAssessment
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import java.net.URI
import java.nio.ByteBuffer
import java.security.MessageDigest

enum class ClipboardShareDocumentOperation(
    val capabilityValue: String,
    val productiveHandoff: Boolean,
) {
    CLIPBOARD_READ("clipboard.read", false),
    CLIPBOARD_WRITE("clipboard.write", true),
    SHARE_PREPARE("share.prepare", false),
    SHARE_LAUNCH("share.launch", true),
    DOCUMENT_OPEN("document.open", true),
    DOCUMENT_EXPORT("document.export", false),
    ;

    val capabilityId: CapabilityId
        get() = CapabilityId(capabilityValue)
}

@JvmInline
value class ClipboardText(val value: String) {
    init {
        require(value.length <= MAX_CLIPBOARD_TEXT_CHARS)
        require(value.none { it == '\u0000' })
    }

    fun fingerprint(): String = handoffFingerprint(
        "clipboard-text/v1",
        value,
    )
}

@JvmInline
value class ShareText(val value: String) {
    init {
        require(value.isNotBlank())
        require(value.length <= MAX_SHARE_TEXT_CHARS)
        require(value.none { it == '\u0000' })
    }

    fun fingerprint(): String = handoffFingerprint(
        "share-text/v1",
        value,
    )
}

data class PublicContentUri private constructor(
    val value: String,
    val authority: String,
) {
    init {
        require(value.length <= MAX_PUBLIC_URI_CHARS)
        require(authority.isNotBlank())
    }

    fun fingerprint(): String = handoffFingerprint(
        "public-content-uri/v1",
        value,
        authority,
    )

    companion object {
        fun create(raw: String): PublicContentUri {
            require(raw.isNotBlank() && raw.length <= MAX_PUBLIC_URI_CHARS)
            require(raw.none(Char::isISOControl))
            val parsed = URI(raw).normalize()
            require(parsed.scheme?.lowercase() == "content") {
                "B410 only accepts content:// public URIs"
            }
            require(parsed.userInfo == null) {
                "Content URI user-info is not allowed"
            }
            val authority = parsed.authority?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Content URI authority is required")
            return PublicContentUri(
                value = parsed.toASCIIString(),
                authority = authority,
            )
        }
    }
}

sealed interface SharePayload {
    val mimeType: String
    fun fingerprint(): String

    data class Text(
        val text: ShareText,
    ) : SharePayload {
        override val mimeType: String = "text/plain"

        override fun fingerprint(): String = handoffFingerprint(
            "share-payload/text/v1",
            mimeType,
            text.fingerprint(),
        )
    }

    data class Content(
        val uri: PublicContentUri,
        override val mimeType: String,
        val displayName: String? = null,
    ) : SharePayload {
        init {
            requireMimeType(mimeType)
            require(displayName == null || displayName.length <= MAX_DISPLAY_NAME_CHARS)
            require(displayName == null || displayName.none { it == '\u0000' || it == '\n' || it == '\r' })
        }

        override fun fingerprint(): String = handoffFingerprint(
            "share-payload/content/v1",
            uri.fingerprint(),
            mimeType,
            displayName.orEmpty(),
        )
    }
}

data class PreparedShare(
    val payload: SharePayload,
    val exactPackage: String? = null,
    val fingerprint: String,
) {
    init {
        require(exactPackage == null || PACKAGE_NAME_REGEX.matches(exactPackage))
        require(
            fingerprint == handoffFingerprint(
                "prepared-share/v1",
                payload.fingerprint(),
                exactPackage.orEmpty(),
            )
        )
    }

    val executionAuthority: Boolean
        get() = false

    companion object {
        fun create(
            payload: SharePayload,
            exactPackage: String? = null,
        ): PreparedShare {
            require(exactPackage == null || PACKAGE_NAME_REGEX.matches(exactPackage))
            return PreparedShare(
                payload = payload,
                exactPackage = exactPackage,
                fingerprint = handoffFingerprint(
                    "prepared-share/v1",
                    payload.fingerprint(),
                    exactPackage.orEmpty(),
                ),
            )
        }
    }
}

data class DocumentHandle(
    val uri: PublicContentUri,
    val mimeType: String,
    val displayName: String? = null,
) {
    init {
        requireMimeType(mimeType)
        require(displayName == null || displayName.length <= MAX_DISPLAY_NAME_CHARS)
        require(displayName == null || displayName.none { it == '\u0000' || it == '\n' || it == '\r' })
    }

    fun fingerprint(): String = handoffFingerprint(
        "document-handle/v1",
        uri.fingerprint(),
        mimeType,
        displayName.orEmpty(),
    )
}

data class PreparedDocumentHandoff(
    val document: DocumentHandle,
    val exactPackage: String? = null,
    val fingerprint: String,
) {
    init {
        require(exactPackage == null || PACKAGE_NAME_REGEX.matches(exactPackage))
        require(
            fingerprint == handoffFingerprint(
                "prepared-document-handoff/v1",
                document.fingerprint(),
                exactPackage.orEmpty(),
            )
        )
    }

    val executionAuthority: Boolean
        get() = false

    companion object {
        fun create(
            document: DocumentHandle,
            exactPackage: String? = null,
        ): PreparedDocumentHandoff {
            require(exactPackage == null || PACKAGE_NAME_REGEX.matches(exactPackage))
            return PreparedDocumentHandoff(
                document = document,
                exactPackage = exactPackage,
                fingerprint = handoffFingerprint(
                    "prepared-document-handoff/v1",
                    document.fingerprint(),
                    exactPackage.orEmpty(),
                ),
            )
        }
    }
}

data class DocumentExportRequest(
    val fileWriteRequest: FileWriteRequest,
) {
    fun fingerprint(): String = handoffFingerprint(
        "document-export-request/v1",
        fileWriteRequest.fingerprint(),
    )
}

interface ClipboardShareDocumentHost {
    suspend fun readClipboard(): ClipboardText?

    suspend fun writeClipboard(text: ClipboardText): String

    suspend fun launchShare(prepared: PreparedShare): HandoffReceipt

    suspend fun openDocument(prepared: PreparedDocumentHandoff): HandoffReceipt
}

data class HandoffReceipt(
    val operation: ClipboardShareDocumentOperation,
    val preparedFingerprint: String,
    val exactPackage: String?,
    val mimeType: String,
    val uri: PublicContentUri?,
    val fingerprint: String,
) {
    init {
        require(operation == ClipboardShareDocumentOperation.SHARE_LAUNCH ||
            operation == ClipboardShareDocumentOperation.DOCUMENT_OPEN ||
            operation == ClipboardShareDocumentOperation.CLIPBOARD_WRITE)
        require(preparedFingerprint.matches(SHA_256_REGEX))
        require(exactPackage == null || PACKAGE_NAME_REGEX.matches(exactPackage))
        requireMimeType(mimeType)
        require(
            fingerprint == handoffFingerprint(
                "handoff-receipt/v1",
                operation.name,
                preparedFingerprint,
                exactPackage.orEmpty(),
                mimeType,
                uri?.fingerprint().orEmpty(),
            )
        )
    }

    companion object {
        fun share(prepared: PreparedShare): HandoffReceipt =
            HandoffReceipt(
                operation = ClipboardShareDocumentOperation.SHARE_LAUNCH,
                preparedFingerprint = prepared.fingerprint,
                exactPackage = prepared.exactPackage,
                mimeType = prepared.payload.mimeType,
                uri = (prepared.payload as? SharePayload.Content)?.uri,
                fingerprint = handoffFingerprint(
                    "handoff-receipt/v1",
                    ClipboardShareDocumentOperation.SHARE_LAUNCH.name,
                    prepared.fingerprint,
                    prepared.exactPackage.orEmpty(),
                    prepared.payload.mimeType,
                    (prepared.payload as? SharePayload.Content)?.uri?.fingerprint().orEmpty(),
                ),
            )

        fun document(prepared: PreparedDocumentHandoff): HandoffReceipt =
            HandoffReceipt(
                operation = ClipboardShareDocumentOperation.DOCUMENT_OPEN,
                preparedFingerprint = prepared.fingerprint,
                exactPackage = prepared.exactPackage,
                mimeType = prepared.document.mimeType,
                uri = prepared.document.uri,
                fingerprint = handoffFingerprint(
                    "handoff-receipt/v1",
                    ClipboardShareDocumentOperation.DOCUMENT_OPEN.name,
                    prepared.fingerprint,
                    prepared.exactPackage.orEmpty(),
                    prepared.document.mimeType,
                    prepared.document.uri.fingerprint(),
                ),
            )

        fun clipboard(text: ClipboardText): HandoffReceipt =
            HandoffReceipt(
                operation = ClipboardShareDocumentOperation.CLIPBOARD_WRITE,
                preparedFingerprint = text.fingerprint(),
                exactPackage = null,
                mimeType = "text/plain",
                uri = null,
                fingerprint = handoffFingerprint(
                    "handoff-receipt/v1",
                    ClipboardShareDocumentOperation.CLIPBOARD_WRITE.name,
                    text.fingerprint(),
                    "",
                    "text/plain",
                    "",
                ),
            )
    }
}

sealed interface HandoffExecutionResult {
    data class Exposed(
        val receipt: HandoffReceipt,
        val policyAssessment: OwnerPolicyAssessment,
        val fingerprint: String,
    ) : HandoffExecutionResult

    data class Blocked(
        val policyAssessment: OwnerPolicyAssessment,
    ) : HandoffExecutionResult
}

/**
 * B410 public clipboard/share/document boundary. Secret-bearing runtime types cannot enter this API:
 * only bounded public text, content:// descriptors and B406 FileWriteRequest are accepted.
 */
class ClipboardShareDocumentRuntime(
    private val host: ClipboardShareDocumentHost,
    private val ownerPolicyGate: OwnerPolicyEffectGate,
    private val fileActionRuntime: FileActionRuntime,
) {
    suspend fun readClipboard(
        plan: AndroidCapabilityDispatchPlan,
    ): ClipboardText? {
        requirePlan(plan, ClipboardShareDocumentOperation.CLIPBOARD_READ)
        return host.readClipboard()
    }

    fun prepareShare(
        plan: AndroidCapabilityDispatchPlan,
        payload: SharePayload,
        exactPackage: String? = null,
    ): PreparedShare {
        requirePlan(plan, ClipboardShareDocumentOperation.SHARE_PREPARE)
        return PreparedShare.create(payload, exactPackage)
    }

    fun prepareDocument(
        plan: AndroidCapabilityDispatchPlan,
        document: DocumentHandle,
        exactPackage: String? = null,
    ): PreparedDocumentHandoff {
        requirePlan(plan, ClipboardShareDocumentOperation.DOCUMENT_OPEN)
        return PreparedDocumentHandoff.create(document, exactPackage)
    }

    suspend fun prepareClipboardWrite(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        text: ClipboardText,
    ): OwnerEffectPreparationResult {
        requirePlan(plan, ClipboardShareDocumentOperation.CLIPBOARD_WRITE)
        return ownerPolicyGate.prepare(
            ownerRequest(
                plan,
                actorId,
                "android-clipboard:text:" + text.fingerprint(),
            )
        )
    }

    suspend fun writeClipboard(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        text: ClipboardText,
        prepared: OwnerEffectPreparation? = null,
    ): HandoffExecutionResult {
        requirePlan(plan, ClipboardShareDocumentOperation.CLIPBOARD_WRITE)
        val resource = "android-clipboard:text:" + text.fingerprint()
        return expose(plan, actorId, resource, prepared) {
            host.writeClipboard(text)
            HandoffReceipt.clipboard(text)
        }
    }

    suspend fun prepareShareLaunch(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        preparedShare: PreparedShare,
    ): OwnerEffectPreparationResult {
        requirePlan(plan, ClipboardShareDocumentOperation.SHARE_LAUNCH)
        return ownerPolicyGate.prepare(
            ownerRequest(plan, actorId, shareResource(preparedShare))
        )
    }

    suspend fun launchShare(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        preparedShare: PreparedShare,
        prepared: OwnerEffectPreparation? = null,
    ): HandoffExecutionResult {
        requirePlan(plan, ClipboardShareDocumentOperation.SHARE_LAUNCH)
        return expose(
            plan,
            actorId,
            shareResource(preparedShare),
            prepared,
        ) {
            host.launchShare(preparedShare).also { receipt ->
                require(receipt == HandoffReceipt.share(preparedShare)) {
                    "Share host receipt does not match prepared share"
                }
            }
        }
    }

    suspend fun prepareDocumentOpen(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        preparedDocument: PreparedDocumentHandoff,
    ): OwnerEffectPreparationResult {
        requirePlan(plan, ClipboardShareDocumentOperation.DOCUMENT_OPEN)
        return ownerPolicyGate.prepare(
            ownerRequest(plan, actorId, documentResource(preparedDocument))
        )
    }

    suspend fun openDocument(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        preparedDocument: PreparedDocumentHandoff,
        prepared: OwnerEffectPreparation? = null,
    ): HandoffExecutionResult {
        requirePlan(plan, ClipboardShareDocumentOperation.DOCUMENT_OPEN)
        return expose(
            plan,
            actorId,
            documentResource(preparedDocument),
            prepared,
        ) {
            host.openDocument(preparedDocument).also { receipt ->
                require(receipt == HandoffReceipt.document(preparedDocument)) {
                    "Document host receipt does not match prepared handoff"
                }
            }
        }
    }

    suspend fun exportDocument(
        plan: AndroidCapabilityDispatchPlan,
        fileWritePlan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: DocumentExportRequest,
        preparedFileWrite: OwnerEffectPreparation? = null,
    ): FileActionResult {
        requirePlan(plan, ClipboardShareDocumentOperation.DOCUMENT_EXPORT)
        require(fileWritePlan.capabilityId == FileActionKind.WRITE.capabilityId) {
            "Document export requires exact B406 file.write plan"
        }
        return fileActionRuntime.write(
            plan = fileWritePlan,
            actorId = actorId,
            request = request.fileWriteRequest,
            prepared = preparedFileWrite,
        )
    }

    private suspend fun expose(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        resource: String,
        prepared: OwnerEffectPreparation?,
        effect: suspend () -> HandoffReceipt,
    ): HandoffExecutionResult =
        when (
            val exposure = ownerPolicyGate.expose(
                request = ownerRequest(plan, actorId, resource),
                prepared = prepared,
                effect = effect,
            )
        ) {
            is OwnerEffectExposureResult.Blocked ->
                HandoffExecutionResult.Blocked(exposure.assessment)
            is OwnerEffectExposureResult.Exposed ->
                HandoffExecutionResult.Exposed(
                    receipt = exposure.value,
                    policyAssessment = exposure.assessment,
                    fingerprint = handoffFingerprint(
                        "handoff-execution-result/v1",
                        plan.fingerprint(),
                        exposure.value.fingerprint,
                        exposure.assessment.decisionId.value,
                        exposure.assessment.policyRevision.toString(),
                    ),
                )
        }

    private fun requirePlan(
        plan: AndroidCapabilityDispatchPlan,
        operation: ClipboardShareDocumentOperation,
    ) {
        require(plan.capabilityId == operation.capabilityId) {
            "Android capability dispatch plan does not match B410 operation"
        }
        if (operation.productiveHandoff) {
            require(plan.binding.requiredOwnerEffect == OwnerEffectType.EXTERNAL_APP_HANDOFF) {
                "Productive B410 handoff requires EXTERNAL_APP_HANDOFF metadata"
            }
        } else {
            require(plan.binding.requiredOwnerEffect == null) {
                "Non-productive B410 operation cannot ignore an owner effect"
            }
        }
    }

    private fun ownerRequest(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        resource: String,
    ): OwnerEffectRequest = OwnerEffectRequest(
        actorId = actorId,
        effect = OwnerEffectType.EXTERNAL_APP_HANDOFF,
        resource = resource,
        scope = plan.binding.ownerScope,
        capabilityId = plan.capabilityId,
        providerVersion = plan.binding.providerVersion,
    )

    private fun shareResource(prepared: PreparedShare): String =
        "android-share:" + prepared.fingerprint

    private fun documentResource(prepared: PreparedDocumentHandoff): String =
        "android-document-open:" + prepared.fingerprint
}

private val SHA_256_REGEX = Regex("[0-9a-f]{64}")
private val PACKAGE_NAME_REGEX = Regex("[A-Za-z0-9_.]+")
private val MIME_REGEX = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")

private const val MAX_CLIPBOARD_TEXT_CHARS = 64 * 1024
private const val MAX_SHARE_TEXT_CHARS = 256 * 1024
private const val MAX_PUBLIC_URI_CHARS = 4096
private const val MAX_DISPLAY_NAME_CHARS = 512

private fun requireMimeType(value: String) {
    require(value.length in 3..255 && MIME_REGEX.matches(value)) {
        "Invalid MIME type"
    }
}

private fun handoffFingerprint(
    domain: String,
    vararg parts: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    listOf(domain, *parts).forEach { value ->
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
