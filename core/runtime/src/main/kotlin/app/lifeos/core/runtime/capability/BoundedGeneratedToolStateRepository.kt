package app.lifeos.core.runtime.capability

/**
 * Explicit durable extension for bounded novel-capability activation. Legacy J03 repositories keep
 * their original contract; a bounded promotion fails closed unless the repository implements this
 * boundary and can persist the full bounded receipt atomically before RAM becomes ACTIVE.
 */
interface BoundedGeneratedToolStateRepository : GeneratedToolStateRepository {
    suspend fun persistBoundedLifecycle(
        record: GeneratedToolRecord,
        auditEntries: List<GeneratedToolAuditEntry>,
        promotionEvidence: BoundedGeneratedToolPromotionEvidence,
    )
}
