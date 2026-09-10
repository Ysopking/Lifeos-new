package app.lifeos.core.runtime.context

import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.language.LanguageContextItem
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

@JvmInline
value class ConversationContextId(val value: String) {
    init { require(value.isNotBlank()) { "Conversation context id must not be blank" } }
}

@JvmInline
value class ProjectContextId(val value: String) {
    init { require(value.isNotBlank()) { "Project context id must not be blank" } }
}

@JvmInline
value class GoalContextId(val value: String) {
    init { require(value.isNotBlank()) { "Goal context id must not be blank" } }
}

enum class ContextScope {
    CONVERSATION,
    PROJECT,
    GOAL,
}

data class ContextEntry(
    val recordPhotonId: PhotonId,
    val recordRevision: Long,
    val scope: ContextScope,
    val scopeId: String,
    val targetPhotonId: PhotonId,
    val targetRevision: Long,
    val kind: String,
    val tags: Set<String>,
    val contentTerms: Set<String>,
    val targetCreatedAt: Instant,
    val active: Boolean,
    val confidence: Double,
    val recordedAt: Instant,
) {
    init {
        require(recordPhotonId.value.startsWith(ContextRecordCodec.ID_PREFIX)) {
            "Context record photon id must use the context namespace"
        }
        require(recordRevision > 0) { "Context record revision must be positive" }
        require(scopeId.isNotBlank()) { "Context scope id must not be blank" }
        require(targetRevision > 0) { "Context target revision must be positive" }
        require(kind.isNotBlank()) { "Context kind must not be blank" }
        require(tags.none { it.isBlank() }) { "Context tags must not be blank" }
        require(contentTerms.none { it.isBlank() }) { "Context content terms must not be blank" }
        require(confidence in 0.0..1.0) { "Context confidence must be in 0..1" }
    }

    fun toLanguageContextItem(): LanguageContextItem = LanguageContextItem(
        photonId = targetPhotonId,
        kind = kind,
        tags = tags,
        createdAt = targetCreatedAt,
        active = active,
        contentTerms = contentTerms,
        confidence = confidence,
    )
}

data class ContextLoadReport(
    val entries: List<ContextEntry>,
    val unreadableFiles: List<String>,
) {
    init {
        require(entries == entries.sortedWith(ContextEntryOrdering)) {
            "Context entries must be deterministically ordered"
        }
        require(unreadableFiles == unreadableFiles.distinct().sorted()) {
            "Unreadable context files must be unique and ordered"
        }
    }

    val complete: Boolean
        get() = unreadableFiles.isEmpty()
}

data class ContextResolutionCandidate(
    val photonId: PhotonId,
    val score: Double,
    val kind: String,
    val scope: ContextScope,
    val scopeId: String,
    val active: Boolean,
    val reasons: List<String>,
) {
    init {
        require(score in 0.0..1.0) { "Context resolution score must be in 0..1" }
        require(kind.isNotBlank()) { "Context resolution kind must not be blank" }
        require(scopeId.isNotBlank()) { "Context resolution scope id must not be blank" }
        require(reasons.isNotEmpty()) { "Context resolution candidate needs reasons" }
    }
}

data class DurableReferenceResolution(
    val targetPhotonId: PhotonId?,
    val confidence: Double,
    val alternatives: List<ContextResolutionCandidate>,
    val incompleteContext: Boolean,
    val reasons: List<String>,
) {
    init {
        require(confidence in 0.0..1.0) { "Reference resolution confidence must be in 0..1" }
        require(alternatives == alternatives.sortedWith(ContextResolutionOrdering)) {
            "Reference alternatives must be deterministically ordered"
        }
        require(targetPhotonId == alternatives.firstOrNull()?.photonId) {
            "Resolved target must equal the leading alternative"
        }
        require(reasons.isNotEmpty()) { "Reference resolution needs reasons" }
    }
}

internal val ContextEntryOrdering: Comparator<ContextEntry> =
    compareBy<ContextEntry> { it.scope.name }
        .thenBy { it.scopeId }
        .thenBy { it.targetPhotonId.value }
        .thenBy { it.kind }
        .thenByDescending { it.recordRevision }

internal val ContextResolutionOrdering: Comparator<ContextResolutionCandidate> =
    compareByDescending<ContextResolutionCandidate> { it.score }
        .thenByDescending { it.active }
        .thenByDescending { it.scope == ContextScope.CONVERSATION }
        .thenByDescending { it.scope == ContextScope.GOAL }
        .thenBy { it.photonId.value }
        .thenBy { it.scopeId }

object ContextKindClassifier {
    fun classify(photon: Photon): String {
        val tags = photon.tags.map { it.lowercase() }.toSet()
        val mime = photon.mimeType.lowercase()
        return when {
            mime.startsWith("image/") || "image" in tags -> "image"
            mime == "application/vnd.android.package-archive" || "apk" in tags -> "apk"
            mime.startsWith("application/vnd.lifeos.goal") || "goal" in tags -> "goal"
            "module" in tags || tags.any { it.startsWith("module:") } -> "module"
            "file" in tags || mime !in setOf("text/plain", "application/json") -> "file"
            "result" in tags -> "result"
            else -> "photon"
        }
    }

    fun contentTerms(photon: Photon): Set<String> = tokenize(photon.content) +
        photon.tags.flatMapTo(mutableSetOf()) { tokenize(it) }

    private fun tokenize(value: String): Set<String> = value
        .lowercase()
        .split(Regex("[^\\p{L}\\p{N}._-]+"))
        .asSequence()
        .map(String::trim)
        .filter { it.isNotBlank() }
        .take(MAX_TERMS)
        .toSet()

    private const val MAX_TERMS = 128
}

internal object ContextRecordCodec {
    const val MIME_TYPE = "application/vnd.lifeos.context+text"
    const val ID_PREFIX = "ctx_"
    private const val FORMAT = "context/v1"

    fun recordId(scope: ContextScope, scopeId: String, target: PhotonId, kind: String): PhotonId =
        PhotonId(
            ID_PREFIX + ContextFingerprint.exact(
                FORMAT,
                scope.name,
                scopeId,
                target.value,
                kind,
            )
        )

    fun encode(
        previous: Photon?,
        scope: ContextScope,
        scopeId: String,
        target: Photon,
        kind: String,
        tags: Set<String>,
        contentTerms: Set<String>,
        active: Boolean,
        confidence: Double,
        recordedAt: Instant,
        actor: String,
    ): Photon {
        require(scopeId.isNotBlank()) { "Context scope id must not be blank" }
        require(kind.isNotBlank()) { "Context kind must not be blank" }
        require(confidence in 0.0..1.0) { "Context confidence must be in 0..1" }
        require(actor.isNotBlank()) { "Context actor must not be blank" }
        val id = recordId(scope, scopeId, target.id, kind)
        require(previous == null || previous.id == id) { "Context record identity mismatch" }
        val revision = (previous?.revision ?: 0L) + 1L
        val canonicalTags = tags.filter { it.isNotBlank() }.toSortedSet()
        val canonicalTerms = contentTerms.filter { it.isNotBlank() }.toSortedSet()
        val content = buildString {
            append(FORMAT).append('\n')
            append("scope=").append(scope.name).append('\n')
            append("scopeId=").append(enc(scopeId)).append('\n')
            append("targetId=").append(enc(target.id.value)).append('\n')
            append("targetRevision=").append(target.revision).append('\n')
            append("kind=").append(enc(kind)).append('\n')
            append("targetCreatedAt=").append(target.provenance.createdAt).append('\n')
            append("active=").append(active).append('\n')
            append("confidence=").append(confidence).append('\n')
            canonicalTags.forEach { append("tag=").append(enc(it)).append('\n') }
            canonicalTerms.forEach { append("term=").append(enc(it)).append('\n') }
        }.trimEnd()
        return Photon(
            id = id,
            revision = revision,
            content = content,
            mimeType = MIME_TYPE,
            phase = if (active) PhotonPhase.ACTIVE else PhotonPhase.ARCHIVED,
            semanticMass = 0.25,
            energy = 0.0,
            confidence = confidence,
            provenance = Provenance(
                source = "durable-context",
                actor = actor,
                createdAt = recordedAt,
                parentIds = setOf(target.id),
            ),
            relations = setOf(PhotonRelation(target.id, RelationType.REFERENCES, confidence)),
            tags = setOf(
                "context-record",
                "context-scope:${scope.name.lowercase()}",
                "context-kind:${kind.lowercase()}",
            ),
        )
    }

    fun decode(photon: Photon): ContextEntry? {
        if (photon.mimeType != MIME_TYPE || "context-record" !in photon.tags) return null
        val lines = photon.content.lineSequence().toList()
        require(lines.firstOrNull() == FORMAT) { "Unsupported context record format" }
        val scalar = mutableMapOf<String, String>()
        val tags = mutableListOf<String>()
        val terms = mutableListOf<String>()
        lines.drop(1).forEach { line ->
            val split = line.indexOf('=')
            require(split > 0) { "Malformed context record line" }
            val key = line.substring(0, split)
            val value = line.substring(split + 1)
            when (key) {
                "tag" -> tags += dec(value)
                "term" -> terms += dec(value)
                else -> require(scalar.put(key, value) == null) { "Duplicate context scalar: $key" }
            }
        }
        val scope = ContextScope.valueOf(required(scalar, "scope"))
        val scopeId = dec(required(scalar, "scopeId"))
        val targetId = PhotonId(dec(required(scalar, "targetId")))
        val targetRevision = required(scalar, "targetRevision").toLong()
        val kind = dec(required(scalar, "kind"))
        val targetCreatedAt = Instant.parse(required(scalar, "targetCreatedAt"))
        val active = required(scalar, "active").toBooleanStrict()
        val confidence = required(scalar, "confidence").toDouble()
        require(photon.id == recordId(scope, scopeId, targetId, kind)) {
            "Context record content does not match photon identity"
        }
        require(photon.phase == if (active) PhotonPhase.ACTIVE else PhotonPhase.ARCHIVED) {
            "Context record phase does not match active flag"
        }
        require(photon.relations.any {
            it.target == targetId && it.type == RelationType.REFERENCES
        }) { "Context record must reference target photon" }
        return ContextEntry(
            recordPhotonId = photon.id,
            recordRevision = photon.revision,
            scope = scope,
            scopeId = scopeId,
            targetPhotonId = targetId,
            targetRevision = targetRevision,
            kind = kind,
            tags = tags.toSortedSet(),
            contentTerms = terms.toSortedSet(),
            targetCreatedAt = targetCreatedAt,
            active = active,
            confidence = confidence,
            recordedAt = photon.provenance.createdAt,
        )
    }

    private fun required(values: Map<String, String>, key: String): String =
        requireNotNull(values[key]) { "Missing context field: $key" }

    private fun enc(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8,
    )
}

private object ContextFingerprint {
    fun exact(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(StandardCharsets.UTF_8)
            val size = bytes.size
            digest.update(
                byteArrayOf(
                    ((size ushr 24) and 0xff).toByte(),
                    ((size ushr 16) and 0xff).toByte(),
                    ((size ushr 8) and 0xff).toByte(),
                    (size and 0xff).toByte(),
                )
            )
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
