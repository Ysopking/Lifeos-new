package app.lifeos.next

import android.content.Context
import android.content.ContextWrapper
import app.lifeos.core.field.StableFieldIds
import app.lifeos.core.runtime.boot.BootEngineCycle
import app.lifeos.core.runtime.boot.BootEngineFrozenInputs
import app.lifeos.core.runtime.world.CognitiveCycleId
import app.lifeos.core.runtime.world.ProductiveWorldHead
import app.lifeos.core.runtime.world.WorldFormulaCycleContext
import app.lifeos.core.runtime.world.WorldFormulaSnapshotNamespace
import app.lifeos.core.runtime.world.WorldFormulaSnapshotRef
import java.io.File

internal object Level7DeviceFixtures {
    fun context(base: Context, root: File): Context =
        object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
        }

    fun preparedCycle(
        cycle: String,
        equationVersion: String,
        previousSnapshotId: String?,
    ): BootEngineCycle {
        val id = CognitiveCycleId(cycle)
        val frozen = BootEngineFrozenInputs(
            representationSnapshotId = "representation-v1",
            strategySnapshotId = "strategy-v1",
            equationVersion = equationVersion,
            resourceSnapshotId = "resources-v1",
        )
        return BootEngineCycle.prepared(
            cycleId = id,
            context = WorldFormulaCycleContext(
                cycleId = id,
                previousWorldSnapshotId = previousSnapshotId,
                representationSnapshotId = frozen.representationSnapshotId,
                strategySnapshotId = frozen.strategySnapshotId,
                equationVersion = frozen.equationVersion,
                resourceSnapshotId = frozen.resourceSnapshotId,
            ),
            frozenInputs = frozen,
        )
    }

    fun worldHead(
        revision: Long,
        snapshotId: String,
        predecessor: String?,
        cycle: String,
        equationVersion: String,
    ): ProductiveWorldHead {
        val cycleId = CognitiveCycleId(cycle)
        val ref = WorldFormulaSnapshotRef(
            namespace = WorldFormulaSnapshotNamespace.PRODUCTIVE,
            snapshotId = snapshotId,
            equationVersion = equationVersion,
            cycleId = cycleId,
        )
        val contextFingerprint = StableFieldIds.fingerprint(
            "level7-device-cycle-context/v1",
            cycle,
            snapshotId,
            predecessor.orEmpty(),
            equationVersion,
        )
        val fingerprint = StableFieldIds.fingerprint(
            "productive-world-head/v1",
            revision.toString(),
            ref.fingerprint(),
            predecessor.orEmpty(),
            equationVersion,
            cycleId.value,
            contextFingerprint,
        )
        return ProductiveWorldHead.restore(
            revision = revision,
            activeSnapshot = ref,
            predecessorSnapshotId = predecessor,
            equationVersion = equationVersion,
            cycleId = cycleId,
            cycleContextFingerprint = contextFingerprint,
            fingerprint = fingerprint,
        )
    }
}
