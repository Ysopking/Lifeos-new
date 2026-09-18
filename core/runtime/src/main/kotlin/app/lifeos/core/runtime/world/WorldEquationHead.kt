package app.lifeos.core.runtime.world

import app.lifeos.core.field.StableFieldIds

data class WorldEquationHeadLoadReport(
    val head: WorldEquationHead?,
    val corrupted: Boolean,
    val message: String?,
)

data class WorldEquationHead private constructor(
    val revision: Long,
    val activeEquationVersion: String,
    val predecessorEquationVersion: String?,
    val sourcePromotionId: String?,
    val fingerprint: String,
) {
    init {
        require(revision > 0L)
        require(activeEquationVersion.isNotBlank())
        require(predecessorEquationVersion == null || predecessorEquationVersion.isNotBlank())
        require(sourcePromotionId == null || sourcePromotionId.isNotBlank())
        require(fingerprint == expectedFingerprint()) {
            "World equation head fingerprint does not match content"
        }
    }

    private fun expectedFingerprint(): String = StableFieldIds.fingerprint(
        "world-equation-head/v1",
        revision.toString(),
        activeEquationVersion,
        predecessorEquationVersion.orEmpty(),
        sourcePromotionId.orEmpty(),
    )

    companion object {
        fun create(
            revision: Long,
            activeEquationVersion: String,
            predecessorEquationVersion: String?,
            sourcePromotionId: String? = null,
        ): WorldEquationHead {
            val fingerprint = StableFieldIds.fingerprint(
                "world-equation-head/v1",
                revision.toString(),
                activeEquationVersion,
                predecessorEquationVersion.orEmpty(),
                sourcePromotionId.orEmpty(),
            )
            return WorldEquationHead(
                revision = revision,
                activeEquationVersion = activeEquationVersion,
                predecessorEquationVersion = predecessorEquationVersion,
                sourcePromotionId = sourcePromotionId,
                fingerprint = fingerprint,
            )
        }

        fun restore(
            revision: Long,
            activeEquationVersion: String,
            predecessorEquationVersion: String?,
            sourcePromotionId: String?,
            fingerprint: String,
        ): WorldEquationHead = WorldEquationHead(
            revision = revision,
            activeEquationVersion = activeEquationVersion,
            predecessorEquationVersion = predecessorEquationVersion,
            sourcePromotionId = sourcePromotionId,
            fingerprint = fingerprint,
        )
    }
}

interface WorldEquationHeadRepository {
    suspend fun load(): WorldEquationHead?

    suspend fun compareAndSet(
        expectedRevision: Long?,
        next: WorldEquationHead,
    ): Boolean

    suspend fun loadReport(): WorldEquationHeadLoadReport
}
