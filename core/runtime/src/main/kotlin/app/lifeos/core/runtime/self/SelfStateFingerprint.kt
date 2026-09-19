package app.lifeos.core.runtime.self

import app.lifeos.core.field.StableFieldIds
import java.time.Instant

object SelfStateFingerprint {
    /**
     * Durable/self-authoritative identity only. Observation time and volatile runtime/resource/
     * health/recovery/live-source measurements are deliberately excluded so process death does not
     * manufacture an authority change.
     */
    fun authorityFingerprint(
        photon: SelfPhotonState,
        memory: SelfMemoryState,
        world: SelfWorldState,
        runtime: SelfRuntimeState,
        tools: SelfToolState,
    ): String = StableFieldIds.fingerprint(
        *buildList {
            add("lifeos-self-authority/v1")
            add("photon.latest=" + encode(photon.latestRevisionCount))
            add("photon.live=" + encode(photon.livePhotonCount))
            add("photon.tombstoned=" + encode(photon.tombstonedPhotonCount))
            add("photon.index=" + encode(photon.indexFingerprint))

            add("memory.authoritative=" + encode(memory.authoritativePhotonCount))
            add("memory.nodes=" + encode(memory.graphNodeCount))
            add("memory.edges=" + encode(memory.graphEdgeCount))
            add("memory.fingerprint=" + encode(memory.memoryFingerprint))

            add("world.revision=" + encode(world.worldHeadRevision))
            add("world.fingerprint=" + encode(world.worldHeadFingerprint))
            add("equation.revision=" + encode(world.worldEquationRevision))
            add("equation.version=" + encode(world.worldEquationVersion))
            add("equation.fingerprint=" + encode(world.worldEquationFingerprint))
            add("boot.id=" + encode(world.bootCycleId))
            add("boot.fingerprint=" + encode(world.bootCycleFingerprint))
            add("cognition.snapshot=" + encode(world.cognitiveSnapshotFingerprint))

            add("runtime.topology=" + encode(runtime.topologyFingerprint))
            addAll(canonicalSet("runtime.registered", runtime.registeredSubsystems))
            addAll(canonicalSet("runtime.unbound", runtime.unboundSubsystems))

            add("tools.total=" + encode(tools.totalTools))
            add("tools.active=" + encode(tools.activeTools))
            add("tools.trial=" + encode(tools.trialTools))
            add("tools.quarantined=" + encode(tools.quarantinedTools))
            add("tools.rejected=" + encode(tools.rejectedTools))
        }.toTypedArray()
    )

    fun stateFingerprint(snapshot: LifeOsSelfStateSnapshot): String = StableFieldIds.fingerprint(
        *buildList {
            add("lifeos-self-state/v1")
            add("captured=" + encode(snapshot.capturedAt))
            add("authority=" + snapshot.authorityFingerprint)

            addAll(canonicalSet("runtime.operational", snapshot.runtime.operationalSubsystems))
            addAll(canonicalSet("runtime.degraded", snapshot.runtime.degradedSubsystems))
            addAll(canonicalSet("runtime.unavailable", snapshot.runtime.unavailableSubsystems))

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

            addAll(canonicalSet("recovery.active", snapshot.recovery.activeRepairIds))
            add("recovery.fingerprint=" + encode(snapshot.recovery.recoveryStateFingerprint))

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
