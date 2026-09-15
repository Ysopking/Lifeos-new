package app.lifeos.core.life

@JvmInline
value class LifeDomainId(val value: String) {
    init { require(value.isNotBlank()) }
}

object BuiltInLifeDomains {
    val AUTHORITIES = LifeDomainId("authorities")
    val FINANCE = LifeDomainId("finance")
    val DAILY_LIFE = LifeDomainId("daily_life")
    val PROJECTS = LifeDomainId("projects")
    val CREATIVE = LifeDomainId("creative")
}

data class LifeDomainProjection(
    val domainId: LifeDomainId,
    val revision: Long,
    val photonRevisionKeys: Set<String>,
    val matterIds: Set<String>,
    val attentionIds: Set<String>,
    val fingerprint: String,
) {
    init {
        require(revision > 0)
        require(photonRevisionKeys.none { it.isBlank() })
        require(matterIds.none { it.isBlank() })
        require(attentionIds.none { it.isBlank() })
        require(fingerprint.isNotBlank())
    }
}
