package app.lifeos.core.runtime.web

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class BrowserActionOperationKey(val value: String) {
    init {
        require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) {
            "Invalid browser action operation key"
        }
    }
}

@JvmInline
value class BrowserActionPlanId(val value: String) {
    init {
        require(value.startsWith(PREFIX))
        require(value.removePrefix(PREFIX).matches(Regex("[0-9a-f]{64}")))
    }

    companion object {
        const val PREFIX = "browser-action-plan:"
    }
}

@JvmInline
value class BrowserElementSelector(val value: String) {
    init {
        require(value.isNotBlank())
        require(value.length <= MAX_BROWSER_SELECTOR_CHARS)
        require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "Browser selector contains forbidden control characters"
        }
    }

    override fun toString(): String = value
}

class BrowserSensitiveText private constructor(
    private val content: ByteArray,
    val sha256: String,
) {
    val byteCount: Int
        get() = content.size

    fun utf8(): String = String(content.copyOf(), StandardCharsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        other is BrowserSensitiveText &&
            sha256 == other.sha256 &&
            content.contentEquals(other.content)

    override fun hashCode(): Int =
        31 * sha256.hashCode() + content.contentHashCode()

    override fun toString(): String = "BrowserSensitiveText(REDACTED," + content.size + "B)"

    companion object {
        fun create(value: String): BrowserSensitiveText {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            require(bytes.isNotEmpty()) { "Browser sensitive text must not be empty" }
            require(bytes.size <= MAX_BROWSER_SENSITIVE_TEXT_BYTES) {
                "Browser sensitive text exceeds bounded size"
            }
            return BrowserSensitiveText(
                content = bytes.copyOf(),
                sha256 = browserActionSha256(bytes),
            )
        }
    }
}

enum class BrowserActionKind {
    NAVIGATE,
    CLICK,
    INPUT_TEXT,
    SUBMIT,
    DOWNLOAD,
    UPLOAD,
}

data class BrowserActionStep(
    val ordinal: Int,
    val kind: BrowserActionKind,
    val targetResource: WebResourceIdentity? = null,
    val selector: BrowserElementSelector? = null,
    val sensitiveText: BrowserSensitiveText? = null,
    val downloadRequest: WebDownloadRequest? = null,
    val uploadRequest: WebUploadRequest? = null,
) {
    init {
        require(ordinal >= 0)
        when (kind) {
            BrowserActionKind.NAVIGATE -> {
                require(targetResource != null)
                require(selector == null)
                require(sensitiveText == null)
                require(downloadRequest == null)
                require(uploadRequest == null)
            }
            BrowserActionKind.CLICK,
            BrowserActionKind.SUBMIT -> {
                require(targetResource == null)
                require(selector != null)
                require(sensitiveText == null)
                require(downloadRequest == null)
                require(uploadRequest == null)
            }
            BrowserActionKind.INPUT_TEXT -> {
                require(targetResource == null)
                require(selector != null)
                require(sensitiveText != null)
                require(downloadRequest == null)
                require(uploadRequest == null)
            }
            BrowserActionKind.DOWNLOAD -> {
                require(targetResource == null)
                require(selector == null)
                require(sensitiveText == null)
                require(downloadRequest != null)
                require(uploadRequest == null)
            }
            BrowserActionKind.UPLOAD -> {
                require(targetResource == null)
                require(selector == null)
                require(sensitiveText == null)
                require(downloadRequest == null)
                require(uploadRequest != null)
            }
        }
    }

    /**
     * Identity deliberately excludes sensitive text bytes/digest. It is safe to use in external
     * plan identity without turning BrowserActionPlanId into a stable hash of secrets.
     */
    fun identityFingerprint(): String = browserActionFingerprint(
        "browser-action-step-identity/v1",
        ordinal.toString(),
        kind.name,
        targetResource?.id?.value.orEmpty(),
        selector?.value.orEmpty(),
        downloadRequest?.id?.value.orEmpty(),
        uploadRequest?.id?.value.orEmpty(),
    )

    /** Exact internal fingerprint binds sensitive text and exact transfer fingerprints. */
    fun fingerprint(): String = browserActionFingerprint(
        "browser-action-step/v1",
        identityFingerprint(),
        sensitiveText?.sha256.orEmpty(),
        downloadRequest?.fingerprint().orEmpty(),
        uploadRequest?.fingerprint().orEmpty(),
    )

    val executionAuthority: Boolean
        get() = false

    val networkAuthority: Boolean
        get() = false

    val permissionAuthority: Boolean
        get() = false
}

data class BrowserActionPlan(
    val id: BrowserActionPlanId,
    val operationKey: BrowserActionOperationKey,
    val origin: WebOriginIdentity,
    val sessionRevisionId: WebSessionRevisionId?,
    val steps: List<BrowserActionStep>,
) {
    init {
        require(steps.isNotEmpty())
        require(steps.size <= MAX_BROWSER_ACTION_STEPS)
        require(steps.map { it.ordinal } == steps.indices.toList()) {
            "Browser action steps must use contiguous canonical ordinals"
        }
        steps.forEach { step ->
            step.targetResource?.let {
                require(it.origin.id == origin.id) {
                    "Browser navigation target crosses the plan origin boundary"
                }
            }
            step.downloadRequest?.let {
                require(it.acquisitionRequest.resource.origin.id == origin.id) {
                    "Browser download crosses the plan origin boundary"
                }
            }
            step.uploadRequest?.let {
                require(it.target.origin.id == origin.id) {
                    "Browser upload crosses the plan origin boundary"
                }
            }
        }
        require(id == expectedId())
    }

    val executionAuthority: Boolean get() = false
    val networkAuthority: Boolean get() = false
    val formSubmissionAuthority: Boolean get() = false
    val fileWriteAuthority: Boolean get() = false
    val permissionAuthority: Boolean get() = false

    fun fingerprint(): String = browserActionFingerprint(
        "browser-action-plan-fingerprint/v1",
        id.value,
        sessionRevisionId?.value.orEmpty(),
        *steps.map { it.fingerprint() }.toTypedArray(),
    )

    private fun expectedId(): BrowserActionPlanId =
        BrowserActionPlanId(
            BrowserActionPlanId.PREFIX +
                browserActionFingerprint(
                    "browser-action-plan/v1",
                    operationKey.value,
                    origin.id.value,
                    sessionRevisionId?.value.orEmpty(),
                    *steps.map { it.identityFingerprint() }.toTypedArray(),
                )
        )

    companion object {
        fun create(
            operationKey: BrowserActionOperationKey,
            origin: WebOriginIdentity,
            steps: Collection<BrowserActionStep>,
            sessionRevisionId: WebSessionRevisionId? = null,
        ): BrowserActionPlan {
            val canonical = steps.sortedBy { it.ordinal }
            val id = BrowserActionPlanId(
                BrowserActionPlanId.PREFIX +
                    browserActionFingerprint(
                        "browser-action-plan/v1",
                        operationKey.value,
                        origin.id.value,
                        sessionRevisionId?.value.orEmpty(),
                        *canonical.map { it.identityFingerprint() }.toTypedArray(),
                    )
            )
            return BrowserActionPlan(
                id = id,
                operationKey = operationKey,
                origin = origin,
                sessionRevisionId = sessionRevisionId,
                steps = canonical,
            )
        }
    }
}

/**
 * B404 is declarative only.
 *
 * It defines same-origin browser action plans and exact secret-safe identities. It does not execute
 * navigation, clicking, typing, form submission, download, upload, cookie attachment, session
 * activation, permission checks or network effects. Host execution remains a later block and must
 * compose OwnerPolicyEffectGate at the point of productive exposure.
 */
object BrowserActionContracts {
    fun navigate(
        ordinal: Int,
        target: WebResourceIdentity,
    ): BrowserActionStep =
        BrowserActionStep(
            ordinal = ordinal,
            kind = BrowserActionKind.NAVIGATE,
            targetResource = target,
        )

    fun click(
        ordinal: Int,
        selector: BrowserElementSelector,
    ): BrowserActionStep =
        BrowserActionStep(
            ordinal = ordinal,
            kind = BrowserActionKind.CLICK,
            selector = selector,
        )

    fun inputText(
        ordinal: Int,
        selector: BrowserElementSelector,
        text: BrowserSensitiveText,
    ): BrowserActionStep =
        BrowserActionStep(
            ordinal = ordinal,
            kind = BrowserActionKind.INPUT_TEXT,
            selector = selector,
            sensitiveText = text,
        )

    fun submit(
        ordinal: Int,
        selector: BrowserElementSelector,
    ): BrowserActionStep =
        BrowserActionStep(
            ordinal = ordinal,
            kind = BrowserActionKind.SUBMIT,
            selector = selector,
        )

    fun download(
        ordinal: Int,
        request: WebDownloadRequest,
    ): BrowserActionStep =
        BrowserActionStep(
            ordinal = ordinal,
            kind = BrowserActionKind.DOWNLOAD,
            downloadRequest = request,
        )

    fun upload(
        ordinal: Int,
        request: WebUploadRequest,
    ): BrowserActionStep =
        BrowserActionStep(
            ordinal = ordinal,
            kind = BrowserActionKind.UPLOAD,
            uploadRequest = request,
        )
}

private fun browserActionFingerprint(
    domain: String,
    vararg parts: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(
            byteArrayOf(
                (bytes.size ushr 24).toByte(),
                (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(),
                bytes.size.toByte(),
            )
        )
        digest.update(bytes)
    }
    update(domain)
    parts.forEach(::update)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun browserActionSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private const val MAX_BROWSER_SELECTOR_CHARS = 2048
private const val MAX_BROWSER_SENSITIVE_TEXT_BYTES = 64 * 1024
private const val MAX_BROWSER_ACTION_STEPS = 64
