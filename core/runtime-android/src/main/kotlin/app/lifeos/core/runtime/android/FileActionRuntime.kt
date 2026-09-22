package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.capability.CapabilityId
import app.lifeos.core.runtime.policy.OwnerActorId
import app.lifeos.core.runtime.policy.OwnerEffectPreparation
import app.lifeos.core.runtime.policy.OwnerEffectPreparationResult
import app.lifeos.core.runtime.policy.OwnerEffectExposureResult
import app.lifeos.core.runtime.policy.OwnerEffectRequest
import app.lifeos.core.runtime.policy.OwnerEffectType
import app.lifeos.core.runtime.policy.OwnerPolicyAssessment
import app.lifeos.core.runtime.policy.OwnerPolicyEffectGate
import java.nio.ByteBuffer
import java.security.MessageDigest

enum class FileActionKind(
    val capabilityValue: String,
    val mutating: Boolean,
) {
    SEARCH("file.search", false),
    READ("file.read", false),
    WRITE("file.write", true),
    COPY("file.copy", true),
    MOVE("file.move", true),
    ORGANIZE("file.organize", true),
    ;

    val capabilityId: CapabilityId
        get() = CapabilityId(capabilityValue)
}

data class AndroidFileRef private constructor(
    val volumeId: String,
    val relativePath: String,
) {
    init {
        require(volumeId.isNotBlank())
        require(volumeId.length <= MAX_VOLUME_ID_CHARS)
        require(volumeId.none(Char::isISOControl))
        require(relativePath.isNotBlank())
        require(relativePath.length <= MAX_RELATIVE_PATH_CHARS)
        require(relativePath == normalize(relativePath)) {
            "Android file path must already be canonical"
        }
    }

    val policyResource: String
        get() = "android-file://" + volumeId + "/" + relativePath

    fun fingerprint(): String = fileActionFingerprint(
        "android-file-ref/v1",
        volumeId,
        relativePath,
    )

    companion object {
        const val MAX_VOLUME_ID_CHARS = 256
        const val MAX_RELATIVE_PATH_CHARS = 4096

        fun create(
            volumeId: String,
            relativePath: String,
        ): AndroidFileRef {
            require(volumeId.isNotBlank())
            val normalizedVolume = volumeId.trim()
            require(normalizedVolume == volumeId) {
                "Android storage volume id must not contain surrounding whitespace"
            }
            return AndroidFileRef(
                volumeId = normalizedVolume,
                relativePath = normalize(relativePath),
            )
        }

        private fun normalize(value: String): String {
            require(value.isNotBlank()) { "Android file path must not be blank" }
            require(value.length <= MAX_RELATIVE_PATH_CHARS)
            require(value.none(Char::isISOControl)) {
                "Android file path must not contain control characters"
            }
            require(!value.startsWith("/") && !value.startsWith("\\")) {
                "Android file path must be relative"
            }
            require(!WINDOWS_ABSOLUTE.matches(value)) {
                "Android file path must not be Windows-absolute"
            }
            val normalized = value.replace('\\', '/')
            val segments = normalized.split('/')
            require(segments.none { it.isBlank() || it == "." || it == ".." }) {
                "Android file path must not contain empty, dot or traversal segments"
            }
            return segments.joinToString("/")
        }

        private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:.*")
    }
}

data class AndroidFileRevision(
    val ref: AndroidFileRef,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val sha256: String? = null,
) {
    init {
        require(sizeBytes >= 0L)
        require(modifiedAtMillis >= 0L)
        require(sha256 == null || sha256.matches(SHA_256_REGEX))
    }

    fun fingerprint(): String = fileActionFingerprint(
        "android-file-revision/v1",
        ref.fingerprint(),
        sizeBytes.toString(),
        modifiedAtMillis.toString(),
        sha256.orEmpty(),
    )
}

data class FileSearchQuery(
    val query: String,
    val maxResults: Int = DEFAULT_SEARCH_LIMIT,
) {
    init {
        require(query.length <= MAX_QUERY_CHARS)
        require(query.none(Char::isISOControl))
        require(maxResults in 1..MAX_SEARCH_RESULTS)
    }

    fun fingerprint(): String = fileActionFingerprint(
        "file-search-query/v1",
        query,
        maxResults.toString(),
    )

    companion object {
        const val DEFAULT_SEARCH_LIMIT = 64
        const val MAX_SEARCH_RESULTS = 512
        const val MAX_QUERY_CHARS = 1024
    }
}

data class FileReadRequest(
    val ref: AndroidFileRef,
    val expectedRevision: AndroidFileRevision? = null,
    val maxBytes: Int,
) {
    init {
        require(maxBytes in 1..MAX_FILE_ACTION_BYTES)
        require(expectedRevision == null || expectedRevision.ref == ref)
    }

    fun fingerprint(): String = fileActionFingerprint(
        "file-read-request/v1",
        ref.fingerprint(),
        expectedRevision?.fingerprint().orEmpty(),
        maxBytes.toString(),
    )
}

class FilePayload private constructor(
    bytes: ByteArray,
) {
    private val value = bytes.copyOf()

    val sizeBytes: Int
        get() = value.size

    val sha256: String = sha256(value)

    fun copyBytes(): ByteArray = value.copyOf()

    fun fingerprint(): String = fileActionFingerprint(
        "file-payload/v1",
        sizeBytes.toString(),
        sha256,
    )

    companion object {
        fun create(bytes: ByteArray): FilePayload {
            require(bytes.size <= MAX_FILE_ACTION_BYTES)
            return FilePayload(bytes)
        }
    }
}

data class FileWriteRequest(
    val destination: AndroidFileRef,
    val payload: FilePayload,
    val expectedDestinationRevision: AndroidFileRevision? = null,
) {
    init {
        require(expectedDestinationRevision == null || expectedDestinationRevision.ref == destination)
    }

    fun fingerprint(): String = fileActionFingerprint(
        "file-write-request/v1",
        destination.fingerprint(),
        payload.fingerprint(),
        expectedDestinationRevision?.fingerprint().orEmpty(),
    )
}

data class FileCopyRequest(
    val source: AndroidFileRef,
    val expectedSourceRevision: AndroidFileRevision,
    val destination: AndroidFileRef,
    val expectedDestinationRevision: AndroidFileRevision? = null,
) {
    init {
        require(expectedSourceRevision.ref == source)
        require(expectedDestinationRevision == null || expectedDestinationRevision.ref == destination)
        require(source != destination)
    }

    fun fingerprint(): String = fileActionFingerprint(
        "file-copy-request/v1",
        source.fingerprint(),
        expectedSourceRevision.fingerprint(),
        destination.fingerprint(),
        expectedDestinationRevision?.fingerprint().orEmpty(),
    )
}

data class FileMoveRequest(
    val source: AndroidFileRef,
    val expectedSourceRevision: AndroidFileRevision,
    val destination: AndroidFileRef,
    val expectedDestinationRevision: AndroidFileRevision? = null,
) {
    init {
        require(expectedSourceRevision.ref == source)
        require(expectedDestinationRevision == null || expectedDestinationRevision.ref == destination)
        require(source != destination)
    }

    fun fingerprint(): String = fileActionFingerprint(
        "file-move-request/v1",
        source.fingerprint(),
        expectedSourceRevision.fingerprint(),
        destination.fingerprint(),
        expectedDestinationRevision?.fingerprint().orEmpty(),
    )
}

data class FileReadPayload(
    val revision: AndroidFileRevision,
    val payload: FilePayload,
)

interface FileActionHost {
    suspend fun search(query: FileSearchQuery): List<AndroidFileRevision>

    suspend fun read(request: FileReadRequest): FileReadPayload

    suspend fun write(request: FileWriteRequest): AndroidFileRevision

    suspend fun copy(request: FileCopyRequest): AndroidFileRevision

    suspend fun move(request: FileMoveRequest): AndroidFileRevision
}

sealed interface FileActionResult {
    val kind: FileActionKind
    val requestFingerprint: String

    data class Search(
        override val requestFingerprint: String,
        val revisions: List<AndroidFileRevision>,
        val fingerprint: String,
    ) : FileActionResult {
        override val kind: FileActionKind = FileActionKind.SEARCH
    }

    data class Read(
        override val requestFingerprint: String,
        val revision: AndroidFileRevision,
        val payload: FilePayload,
        val fingerprint: String,
    ) : FileActionResult {
        override val kind: FileActionKind = FileActionKind.READ
    }

    data class Mutation(
        override val kind: FileActionKind,
        override val requestFingerprint: String,
        val sourceRevision: AndroidFileRevision?,
        val destinationRevision: AndroidFileRevision,
        val policyAssessment: OwnerPolicyAssessment,
        val fingerprint: String,
    ) : FileActionResult {
        init {
            require(kind.mutating)
        }
    }

    data class Blocked(
        override val kind: FileActionKind,
        override val requestFingerprint: String,
        val policyAssessment: OwnerPolicyAssessment,
    ) : FileActionResult
}

class FileActionRuntime(
    private val host: FileActionHost,
    private val ownerPolicyGate: OwnerPolicyEffectGate,
) {
    suspend fun search(
        plan: AndroidCapabilityDispatchPlan,
        query: FileSearchQuery,
    ): FileActionResult.Search {
        requirePlan(plan, FileActionKind.SEARCH)
        val revisions = host.search(query)
            .distinctBy { it.ref }
            .sortedWith(compareBy({ it.ref.volumeId }, { it.ref.relativePath }))
            .take(query.maxResults)
        val requestFingerprint = fileActionFingerprint(
            "file-search-dispatch/v1",
            plan.fingerprint(),
            query.fingerprint(),
        )
        return FileActionResult.Search(
            requestFingerprint = requestFingerprint,
            revisions = revisions,
            fingerprint = fileActionFingerprint(
                "file-search-result/v1",
                requestFingerprint,
                revisions.joinToString("\u001f") { it.fingerprint() },
            ),
        )
    }

    suspend fun read(
        plan: AndroidCapabilityDispatchPlan,
        request: FileReadRequest,
    ): FileActionResult.Read {
        requirePlan(plan, FileActionKind.READ)
        val hostResult = host.read(request)
        require(hostResult.revision.ref == request.ref) {
            "File host returned a revision for the wrong path"
        }
        require(hostResult.payload.sizeBytes <= request.maxBytes) {
            "File host returned more bytes than the read bound"
        }
        request.expectedRevision?.let { expected ->
            require(hostResult.revision == expected) {
                "File revision changed before bounded read"
            }
        }
        val requestFingerprint = fileActionFingerprint(
            "file-read-dispatch/v1",
            plan.fingerprint(),
            request.fingerprint(),
        )
        return FileActionResult.Read(
            requestFingerprint = requestFingerprint,
            revision = hostResult.revision,
            payload = hostResult.payload,
            fingerprint = fileActionFingerprint(
                "file-read-result/v1",
                requestFingerprint,
                hostResult.revision.fingerprint(),
                hostResult.payload.fingerprint(),
            ),
        )
    }

    suspend fun prepareWrite(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: FileWriteRequest,
    ): OwnerEffectPreparationResult {
        requirePlan(plan, FileActionKind.WRITE)
        return ownerPolicyGate.prepare(
            ownerRequest(
                plan = plan,
                actorId = actorId,
                kind = FileActionKind.WRITE,
                resource = request.destination.policyResource,
            )
        )
    }

    suspend fun write(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: FileWriteRequest,
        prepared: OwnerEffectPreparation? = null,
    ): FileActionResult = mutate(
        kind = FileActionKind.WRITE,
        plan = plan,
        actorId = actorId,
        target = request.destination,
        ownerResource = request.destination.policyResource,
        operationFingerprint = request.fingerprint(),
        prepared = prepared,
        sourceRevision = null,
    ) { host.write(request) }

    suspend fun prepareCopy(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: FileCopyRequest,
    ): OwnerEffectPreparationResult {
        requirePlan(plan, FileActionKind.COPY)
        return ownerPolicyGate.prepare(
            ownerRequest(
                plan = plan,
                actorId = actorId,
                kind = FileActionKind.COPY,
                resource = copyResource(request.source, request.destination),
            )
        )
    }

    suspend fun copy(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: FileCopyRequest,
        prepared: OwnerEffectPreparation? = null,
    ): FileActionResult = mutate(
        kind = FileActionKind.COPY,
        plan = plan,
        actorId = actorId,
        target = request.destination,
        ownerResource = copyResource(request.source, request.destination),
        operationFingerprint = request.fingerprint(),
        prepared = prepared,
        sourceRevision = request.expectedSourceRevision,
    ) { host.copy(request) }

    suspend fun prepareMove(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: FileMoveRequest,
        organize: Boolean = false,
    ): OwnerEffectPreparationResult {
        val kind = if (organize) FileActionKind.ORGANIZE else FileActionKind.MOVE
        requirePlan(plan, kind)
        return ownerPolicyGate.prepare(
            ownerRequest(
                plan = plan,
                actorId = actorId,
                kind = kind,
                resource = moveResource(kind, request.source, request.destination),
            )
        )
    }

    suspend fun move(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        request: FileMoveRequest,
        prepared: OwnerEffectPreparation? = null,
        organize: Boolean = false,
    ): FileActionResult {
        val kind = if (organize) FileActionKind.ORGANIZE else FileActionKind.MOVE
        return mutate(
            kind = kind,
            plan = plan,
            actorId = actorId,
            target = request.destination,
            ownerResource = moveResource(kind, request.source, request.destination),
            operationFingerprint = request.fingerprint(),
            prepared = prepared,
            sourceRevision = request.expectedSourceRevision,
        ) { host.move(request) }
    }

    private suspend fun mutate(
        kind: FileActionKind,
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        target: AndroidFileRef,
        ownerResource: String,
        operationFingerprint: String,
        prepared: OwnerEffectPreparation?,
        sourceRevision: AndroidFileRevision?,
        effect: suspend () -> AndroidFileRevision,
    ): FileActionResult {
        requirePlan(plan, kind)
        val requestFingerprint = fileActionFingerprint(
            "file-mutation-dispatch/v1",
            kind.name,
            plan.fingerprint(),
            operationFingerprint,
        )
        val ownerRequest = ownerRequest(
            plan = plan,
            actorId = actorId,
            kind = kind,
            resource = ownerResource,
        )
        return when (val exposure = ownerPolicyGate.expose(ownerRequest, prepared, effect)) {
            is OwnerEffectExposureResult.Blocked -> FileActionResult.Blocked(
                kind = kind,
                requestFingerprint = requestFingerprint,
                policyAssessment = exposure.assessment,
            )
            is OwnerEffectExposureResult.Exposed -> {
                val destinationRevision = exposure.value
                require(destinationRevision.ref == target) {
                    "File host returned a mutation revision for the wrong destination"
                }
                FileActionResult.Mutation(
                    kind = kind,
                    requestFingerprint = requestFingerprint,
                    sourceRevision = sourceRevision,
                    destinationRevision = destinationRevision,
                    policyAssessment = exposure.assessment,
                    fingerprint = fileActionFingerprint(
                        "file-mutation-result/v1",
                        kind.name,
                        requestFingerprint,
                        sourceRevision?.fingerprint().orEmpty(),
                        destinationRevision.fingerprint(),
                        exposure.assessment.decisionId.value,
                        exposure.assessment.policyRevision.toString(),
                    ),
                )
            }
        }
    }

    private fun requirePlan(
        plan: AndroidCapabilityDispatchPlan,
        kind: FileActionKind,
    ) {
        require(plan.capabilityId == kind.capabilityId) {
            "Android capability dispatch plan does not match file action"
        }
        if (kind.mutating) {
            require(plan.binding.requiredOwnerEffect == OwnerEffectType.FILE_WRITE) {
                "Mutating file action requires FILE_WRITE owner-effect metadata"
            }
        } else {
            require(plan.binding.requiredOwnerEffect == null) {
                "Read-only file action cannot ignore an owner-effect requirement"
            }
        }
    }

    private fun ownerRequest(
        plan: AndroidCapabilityDispatchPlan,
        actorId: OwnerActorId,
        kind: FileActionKind,
        resource: String,
    ): OwnerEffectRequest = OwnerEffectRequest(
        actorId = actorId,
        effect = OwnerEffectType.FILE_WRITE,
        resource = resource,
        scope = plan.binding.ownerScope,
        capabilityId = plan.capabilityId,
        providerVersion = plan.binding.providerVersion,
    )

    private fun copyResource(
        source: AndroidFileRef,
        destination: AndroidFileRef,
    ): String = "file-copy:" + source.policyResource + "->" + destination.policyResource

    private fun moveResource(
        kind: FileActionKind,
        source: AndroidFileRef,
        destination: AndroidFileRef,
    ): String = "file-" + kind.name.lowercase() + ":" +
        source.policyResource + "->" + destination.policyResource
}

const val MAX_FILE_ACTION_BYTES: Int = 8 * 1024 * 1024

private val SHA_256_REGEX = Regex("[0-9a-f]{64}")

private fun fileActionFingerprint(
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

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
