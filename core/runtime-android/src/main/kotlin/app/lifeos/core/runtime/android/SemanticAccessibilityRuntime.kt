package app.lifeos.core.runtime.android

import app.lifeos.core.runtime.life.ControlStatus
import app.lifeos.core.runtime.life.EpistemicStatus
import app.lifeos.core.runtime.life.InformationObservation
import app.lifeos.core.runtime.life.ObservationAuthorityClass
import app.lifeos.core.runtime.life.ObservationPrivacyClass
import app.lifeos.core.runtime.life.ObservationSurfaceKind
import app.lifeos.core.runtime.life.RealizationDescriptor
import app.lifeos.core.runtime.life.RepresentationLevel
import app.lifeos.core.runtime.life.TemporalStatus
import java.time.Instant

enum class SemanticUiRole {
    BUTTON,
    TEXT,
    TEXT_FIELD,
    CHECKBOX,
    SWITCH,
    LIST,
    LIST_ITEM,
    LINK,
    IMAGE,
    CONTAINER,
    UNKNOWN,
}

enum class SemanticUiActionKind {
    ACTIVATE,
    SET_TEXT,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
}

data class SemanticUiNodeSnapshot(
    val nodeKey: String,
    val role: SemanticUiRole,
    val labelFingerprint: String?,
    val valueFingerprint: String?,
    val enabled: Boolean,
    val visible: Boolean,
    val supportedActions: Set<SemanticUiActionKind>,
) {
    init {
        require(nodeKey.isNotBlank())
        listOfNotNull(labelFingerprint, valueFingerprint).forEach {
            require(it.matches(Regex("[0-9a-f]{64}"))) {
                "Semantic UI content fingerprints must be SHA-256"
            }
        }
    }

    val fingerprint: String = androidCapabilityFingerprint(
        "semantic-ui-node/v1",
        nodeKey,
        role.name,
        labelFingerprint.orEmpty(),
        valueFingerprint.orEmpty(),
        enabled.toString(),
        visible.toString(),
        supportedActions.map { it.name }.sorted().joinToString("\u001f"),
    )
}

data class SemanticUiSnapshot(
    val packageName: String,
    val windowRevision: String,
    val capturedAt: Instant,
    val nodes: List<SemanticUiNodeSnapshot>,
) {
    init {
        require(packageName.isNotBlank())
        require(windowRevision.isNotBlank())
        require(nodes.map { it.nodeKey }.distinct().size == nodes.size)
        require(nodes == nodes.sortedBy { it.nodeKey }) {
            "Semantic UI nodes must be canonical by node key"
        }
    }

    val fingerprint: String = androidCapabilityFingerprint(
        "semantic-ui-snapshot/v1",
        packageName,
        windowRevision,
        capturedAt.toString(),
        nodes.joinToString("\u001f") { it.fingerprint },
    )

    companion object {
        fun create(
            packageName: String,
            windowRevision: String,
            capturedAt: Instant,
            nodes: Collection<SemanticUiNodeSnapshot>,
        ): SemanticUiSnapshot = SemanticUiSnapshot(
            packageName = packageName,
            windowRevision = windowRevision,
            capturedAt = capturedAt,
            nodes = nodes.sortedBy { it.nodeKey },
        )
    }
}

data class SemanticUiActionCandidate(
    val packageName: String,
    val targetNodeKey: String,
    val action: SemanticUiActionKind,
    val preconditionSnapshotFingerprint: String,
    val payloadFingerprint: String? = null,
) {
    init {
        require(packageName.isNotBlank())
        require(targetNodeKey.isNotBlank())
        require(preconditionSnapshotFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(
            payloadFingerprint == null ||
                payloadFingerprint.matches(Regex("[0-9a-f]{64}"))
        )
        if (action == SemanticUiActionKind.SET_TEXT) {
            require(payloadFingerprint != null) {
                "SET_TEXT semantic candidate requires payload fingerprint"
            }
        }
    }

    val fingerprint: String = androidCapabilityFingerprint(
        "semantic-ui-action-candidate/v1",
        packageName,
        targetNodeKey,
        action.name,
        preconditionSnapshotFingerprint,
        payloadFingerprint.orEmpty(),
    )

    val executionAuthority: Boolean
        get() = false

    val ownerPolicyAuthority: Boolean
        get() = false

    val coordinateFallbackAllowed: Boolean
        get() = false
}

/**
 * B462 semantic accessibility boundary.
 *
 * The boundary exposes a structured projection and non-authoritative action candidates only. It does
 * not drive Android Accessibility APIs, does not synthesize taps and never converts a UI snapshot
 * into truth. Productive execution must be supplied by a separate host behind platform permission,
 * Owner Policy, precondition and postcondition checks.
 */
class SemanticAccessibilityBoundary {
    fun observe(
        snapshot: SemanticUiSnapshot,
        observationGrantId: String,
    ): InformationObservation {
        require(observationGrantId.isNotBlank())
        return InformationObservation(
            sourceId = "android-accessibility-semantic",
            sourceResource =
                "android-ui:${snapshot.packageName}:${snapshot.windowRevision}",
            surface = ObservationSurfaceKind.APP_UI,
            observedAt = snapshot.capturedAt,
            sourceTimestamp = snapshot.capturedAt,
            sourceRevision = snapshot.fingerprint,
            mimeType = "application/vnd.lifeos.semantic-ui+text",
            payload = buildString {
                appendLine("package=${snapshot.packageName}")
                appendLine("window_revision=${snapshot.windowRevision}")
                append("node_fingerprints=")
                append(snapshot.nodes.joinToString(",") { it.fingerprint })
            },
            realization = RealizationDescriptor(
                representation = RepresentationLevel.PROJECTED,
                epistemicStatus = EpistemicStatus.OBSERVED,
                temporalStatus = TemporalStatus.CURRENT,
                controlStatus = ControlStatus.PASSIVE,
            ),
            authority = ObservationAuthorityClass.UI_OBSERVATION,
            privacy = ObservationPrivacyClass.SENSITIVE,
            observationGrantId = observationGrantId,
            tags = setOf(
                "accessibility",
                "semantic-ui",
                "untrusted-ui-projection",
            ),
            metadata = mapOf(
                "package" to snapshot.packageName,
                "windowRevision" to snapshot.windowRevision,
                "snapshotFingerprint" to snapshot.fingerprint,
            ),
        )
    }

    fun actionCandidate(
        snapshot: SemanticUiSnapshot,
        targetNodeKey: String,
        action: SemanticUiActionKind,
        payloadFingerprint: String? = null,
    ): SemanticUiActionCandidate {
        val node = requireNotNull(
            snapshot.nodes.firstOrNull { it.nodeKey == targetNodeKey }
        ) {
            "Semantic UI action target is absent from the precondition snapshot"
        }
        require(node.visible && node.enabled) {
            "Semantic UI action target must be visible and enabled"
        }
        require(action in node.supportedActions) {
            "Semantic UI action is unsupported by the target node"
        }
        return SemanticUiActionCandidate(
            packageName = snapshot.packageName,
            targetNodeKey = targetNodeKey,
            action = action,
            preconditionSnapshotFingerprint = snapshot.fingerprint,
            payloadFingerprint = payloadFingerprint,
        )
    }
}
