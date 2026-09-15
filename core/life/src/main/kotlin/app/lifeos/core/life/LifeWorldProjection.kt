package app.lifeos.core.life

import app.lifeos.core.model.LifeWorldPartitions

data class LifeWorldPartitionRef(val partition: String, val revision: Long, val fingerprint: String) {
    init { require(partition.startsWith("life:")); require(revision > 0); require(fingerprint.isNotBlank()) }
}

object LifeWorldProjector {
    fun project(domains: Collection<LifeDomainProjection>): List<LifeWorldPartitionRef> = domains.map { domain ->
        val partition = when (domain.domainId) {
            BuiltInLifeDomains.AUTHORITIES -> LifeWorldPartitions.AUTHORITIES
            BuiltInLifeDomains.FINANCE -> LifeWorldPartitions.FINANCE
            BuiltInLifeDomains.DAILY_LIFE -> LifeWorldPartitions.DAILY
            BuiltInLifeDomains.PROJECTS -> LifeWorldPartitions.PROJECTS
            BuiltInLifeDomains.CREATIVE -> LifeWorldPartitions.CREATIVE
            else -> "life:${domain.domainId.value}"
        }
        LifeWorldPartitionRef(partition, domain.revision, domain.fingerprint)
    }.sortedBy { it.partition }
}
