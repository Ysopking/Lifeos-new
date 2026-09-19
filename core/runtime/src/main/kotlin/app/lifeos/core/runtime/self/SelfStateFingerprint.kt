package app.lifeos.core.runtime.self

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

object SelfStateFingerprint {
    /**
     * Restart-stable durable authority identity.
     *
     * Only exact persisted control-plane authority heads belong here. PhotonStore is itself a
     * mutable append/revision authority: startup can legitimately append or advance canonical
     * Photons while restoring the same store. Its population/head evidence therefore belongs to
     * [stateFingerprint] and is recovery-checked by monotonic continuity rather than hash equality.
     * Rebuildable cognitive snapshots, memory projections, process topology and generated-tool
     * runtime status likewise remain state evidence.
     */
    fun authorityFingerprint(
        world: SelfWorldState,
    ): String = StableFieldIds.fingerprint(
        "lifeos-self-authority/v3",
        "world.revision=" + encode(world.worldHeadRevision),
        "world.fingerprint=" + encode(world.worldHeadFingerprint),
        "equation.revision=" + encode(world.worldEquationRevision),
        "equation.version=" + encode(world.worldEquationVersion),
        "equation.fingerprint=" + encode(world.worldEquationFingerprint),
        "boot.id=" + encode(world.bootCycleId),
        "boot.fingerprint=" + encode(world.bootCycleFingerprint),
    )

    fun stateFingerprint(snapshot: LifeOsSelfStateSnapshot): String = StableFieldIds.fingerprint(
        *buildList {
            add("lifeos-self-state/v2")
            add("captured=" + encode(snapshot.capturedAt))
            add("authority=" + snapshot.authorityFingerprint)

            add("photon.latest=" + encode(snapshot.photon.latestRevisionCount))
            add("photon.live=" + encode(snapshot.photon.livePhotonCount))
            add("photon.tombstoned=" + encode(snapshot.photon.tombstonedPhotonCount))
            add("photon.index-identity=" + encode(snapshot.photon.indexFingerprint))
            add("photon.index-head=" + encode(snapshot.photon.headFingerprint))

            add("world.cognition.snapshot=" + encode(snapshot.world.cognitiveSnapshotFingerprint))

            add("memory.authoritative=" + encode(snapshot.memory.authoritativePhotonCount))
            add("memory.nodes=" + encode(snapshot.memory.graphNodeCount))
            add("memory.edges=" + encode(snapshot.memory.graphEdgeCount))
            add("memory.fingerprint=" + encode(snapshot.memory.memoryFingerprint))

            add("runtime.topology=" + encode(snapshot.runtime.topologyFingerprint))
            addAll(canonicalSet("runtime.registered", snapshot.runtime.registeredSubsystems))
            addAll(canonicalSet("runtime.operational", snapshot.runtime.operationalSubsystems))
            addAll(canonicalSet("runtime.degraded", snapshot.runtime.degradedSubsystems))
            addAll(canonicalSet("runtime.unavailable", snapshot.runtime.unavailableSubsystems))
            addAll(canonicalSet("runtime.unbound", snapshot.runtime.unboundSubsystems))
            add("runtime.telemetry=" + encode(snapshot.runtime.telemetry?.fingerprint()))

            add("resource.hardware=" + encode(snapshot.resource.hardwareFingerprint))
            add("resource.memory=" + encode(snapshot.resource.memoryHeadroom))
            add("resource.storage=" + encode(snapshot.resource.storageHeadroom))
            add("resource.thermal=" + encode(snapshot.resource.thermalHeadroom))
            add("resource.energy=" + encode(snapshot.resource.energyAvailability))
            add("resource.capability=" + encode(snapshot.resource.capabilityReadiness))

            add("health.healthy=" + encode(snapshot.health.healthy))
            add("health.degraded=" + encode(snapshot.health.degraded))
            add("health.unhealthy=" + encode(snapshot.health.unhealthy))
            add("health.recovering=" + encode(snapshot.health.recovering))
            add("health.quarantined=" + encode(snapshot.health.quarantined))
            add("health.disabled=" + encode(snapshot.health.disabled))
            add("health.unknown=" + encode(snapshot.health.unknown))

            addAll(canonicalSet("recovery.active", snapshot.recovery.activeRepairIds))
            add("recovery.fingerprint=" + encode(snapshot.recovery.recoveryStateFingerprint))

            add("tools.total=" + encode(snapshot.tools.totalTools))
            add("tools.active=" + encode(snapshot.tools.activeTools))
            add("tools.trial=" + encode(snapshot.tools.trialTools))
            add("tools.quarantined=" + encode(snapshot.tools.quarantinedTools))
            add("tools.rejected=" + encode(snapshot.tools.rejectedTools))

            add("sources.total=" + encode(snapshot.liveSources.sourceCount))
            add("sources.healthy=" + encode(snapshot.liveSources.healthyCount))
            add("sources.blocked=" + encode(snapshot.liveSources.blockedCount))
            add("sources.failed=" + encode(snapshot.liveSources.failedCount))
            add("sources.fingerprint=" + encode(snapshot.liveSources.sourceStateFingerprint))
        }.toTypedArray()
    )

    private fun canonicalSet(prefix: String, values: Set<String>?): List<String> =
        if (values == null) {
            listOf(prefix + "=null:")
        } else {
            listOf(prefix + "=present") +
                values.asSequence()
                    .map(::canonicalText)
                    .sorted()
                    .mapIndexed { index, value -> prefix + "[" + index + "]=" + value }
                    .toList()
        }

    private fun encode(value: Any?): String = when (value) {
        null -> "null:"
        is Double -> "double:" + java.lang.Double.toHexString(value)
        is Instant -> "instant:" + value.toString()
        is String -> "string:" + canonicalText(value)
        is Int -> "int:" + value.toString()
        is Long -> "long:" + value.toString()
        else -> "value:" + value.toString()
    }

    private fun canonicalText(value: String): String = value.trim().lowercase()
}
