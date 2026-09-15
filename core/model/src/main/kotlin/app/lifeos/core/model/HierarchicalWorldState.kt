package app.lifeos.core.model

import java.security.MessageDigest

data class WorldMerkleNode(
    val name: String,
    val fingerprint: String,
    val children: List<WorldMerkleNode> = emptyList(),
) {
    init { require(name.isNotBlank()); require(fingerprint.isNotBlank()) }

    val merkleRoot: String by lazy {
        val canonical = buildString {
            append(name).append(':').append(fingerprint)
            children.sortedBy { it.name }.forEach { append('|').append(it.name).append(':').append(it.merkleRoot) }
        }
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

object LifeWorldPartitions {
    const val AUTHORITIES = "life:authorities"
    const val FINANCE = "life:finance"
    const val DAILY = "life:daily"
    const val PROJECTS = "life:projects"
    const val CREATIVE = "life:creative"
}

data class HierarchicalWorldState(
    val cognitionRoot: WorldMerkleNode,
    val memoryRoot: WorldMerkleNode,
    val lifeRoot: WorldMerkleNode,
    val creativeRoot: WorldMerkleNode,
    val resourceRoot: WorldMerkleNode,
) {
    val worldRoot: String get() = WorldMerkleNode(
        name = "world",
        fingerprint = "v1",
        children = listOf(cognitionRoot, memoryRoot, lifeRoot, creativeRoot, resourceRoot),
    ).merkleRoot
}
