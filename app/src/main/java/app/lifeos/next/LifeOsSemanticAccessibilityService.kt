package app.lifeos.next

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import app.lifeos.core.model.StableCognitiveIds
import app.lifeos.core.runtime.android.SemanticAccessibilityBoundary
import app.lifeos.core.runtime.android.SemanticUiActionKind
import app.lifeos.core.runtime.android.SemanticUiNodeSnapshot
import app.lifeos.core.runtime.android.SemanticUiRole
import app.lifeos.core.runtime.android.SemanticUiSnapshot
import app.lifeos.next.kernel.ProductiveSemanticAppContentIngress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * B485 observation-only Accessibility bridge.
 *
 * Raw screen text and coordinates never enter the semantic snapshot. Labels and values are reduced
 * to SHA-256 fingerprints before productive ingestion. Platform Accessibility enablement is source
 * availability only; Owner Observation Policy remains the canonical observation authority.
 */
class LifeOsSemanticAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val boundary = SemanticAccessibilityBoundary()

    override fun onServiceConnected() {
        super.onServiceConnected()
        scope.launch {
            ProductiveSemanticAppContentIngress.connected()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!ProductiveSemanticAppContentIngress.active()) return
        val current = event ?: return
        val packageName =
            current.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        if (packageName == applicationContext.packageName) return

        val root = rootInActiveWindow ?: current.source ?: return
        val capturedAt = current.eventTime
            .takeIf { it > 0L }
            ?.let(Instant::ofEpochMilli)
            ?: Instant.now()
        val snapshot = SemanticUiSnapshot.create(
            packageName = packageName,
            windowRevision = StableCognitiveIds.fingerprint(
                "android-accessibility-window/v1",
                packageName,
                current.windowId.toString(),
                current.eventType.toString(),
                current.eventTime.toString(),
            ),
            capturedAt = capturedAt,
            nodes = snapshotNodes(root),
        )
        if (snapshot.nodes.isEmpty()) return

        val observation = boundary.project(snapshot)
        scope.launch {
            ProductiveSemanticAppContentIngress.ingest(observation)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        scope.launch {
            ProductiveSemanticAppContentIngress.disconnected()
        }
        super.onDestroy()
    }

    private fun snapshotNodes(root: AccessibilityNodeInfo): List<SemanticUiNodeSnapshot> {
        data class PendingNode(
            val node: AccessibilityNodeInfo,
            val key: String,
        )

        val pending = ArrayDeque<PendingNode>()
        pending.add(PendingNode(root, "root"))
        val result = ArrayList<SemanticUiNodeSnapshot>(MAX_NODES)

        while (pending.isNotEmpty() && result.size < MAX_NODES) {
            val current = pending.removeFirst()
            val node = current.node
            result += SemanticUiNodeSnapshot(
                nodeKey = current.key,
                role = role(node),
                labelFingerprint = contentFingerprint(node.contentDescription),
                valueFingerprint = contentFingerprint(node.text),
                enabled = node.isEnabled,
                visible = node.isVisibleToUser,
                supportedActions = supportedActions(node),
            )

            val childCount = minOf(node.childCount, MAX_CHILDREN_PER_NODE)
            for (index in 0 until childCount) {
                val child = node.getChild(index) ?: continue
                pending.addLast(
                    PendingNode(
                        node = child,
                        key = current.key + "/" + index,
                    )
                )
            }
        }

        return result.sortedBy { it.nodeKey }
    }

    private fun role(node: AccessibilityNodeInfo): SemanticUiRole {
        val className = node.className?.toString().orEmpty()
        return when {
            node.isEditable -> SemanticUiRole.TEXT_FIELD
            node.isCheckable && className.contains("Switch", ignoreCase = true) ->
                SemanticUiRole.SWITCH
            node.isCheckable -> SemanticUiRole.CHECKBOX
            className.contains("Button", ignoreCase = true) -> SemanticUiRole.BUTTON
            className.contains("Image", ignoreCase = true) -> SemanticUiRole.IMAGE
            className.contains("Recycler", ignoreCase = true) ||
                className.contains("ListView", ignoreCase = true) ->
                SemanticUiRole.LIST
            node.isClickable -> SemanticUiRole.LINK
            !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank() ->
                SemanticUiRole.TEXT
            else -> SemanticUiRole.CONTAINER
        }
    }

    private fun supportedActions(node: AccessibilityNodeInfo): Set<SemanticUiActionKind> =
        buildSet {
            val actions = node.actions
            if (actions and AccessibilityNodeInfo.ACTION_CLICK != 0) {
                add(SemanticUiActionKind.ACTIVATE)
            }
            if (actions and AccessibilityNodeInfo.ACTION_SET_TEXT != 0) {
                add(SemanticUiActionKind.SET_TEXT)
            }
            if (actions and AccessibilityNodeInfo.ACTION_SCROLL_FORWARD != 0) {
                add(SemanticUiActionKind.SCROLL_FORWARD)
            }
            if (actions and AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD != 0) {
                add(SemanticUiActionKind.SCROLL_BACKWARD)
            }
        }

    private fun contentFingerprint(value: CharSequence?): String? {
        val normalized = value
            ?.toString()
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(MAX_TEXT_CHARS)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
    }

    private companion object {
        const val MAX_NODES = 256
        const val MAX_CHILDREN_PER_NODE = 64
        const val MAX_TEXT_CHARS = 8_192
    }
}
