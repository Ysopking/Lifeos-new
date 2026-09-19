package app.lifeos.next.kernel

import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.runtime.context.DurableContextFieldEnricher
import app.lifeos.core.runtime.field.DefaultPhotonFieldRequestFactory
import app.lifeos.core.runtime.field.EphemeralFieldSnapshotRepository
import app.lifeos.core.runtime.field.ReplayableAuthoritativeFieldProcessor
import app.lifeos.core.runtime.field.ReplayableUniversalFieldProcessor
import app.lifeos.core.runtime.field.UniversalFieldRuntimeAdapter
import app.lifeos.core.runtime.informationasset.InformationAssetPhotonContract
import app.lifeos.core.runtime.informationasset.StandardInformationDomains

/**
 * Explicit productive cutover candidates. Registration here does not activate authority: every
 * domain still requires deterministic replay evidence and an exact FieldCutoverAuthority activation.
 */
internal fun productiveAuthoritativeFieldProcessors(
    foundation: KernelFoundationGraph,
    world: KernelWorldGraph,
): List<ReplayableAuthoritativeFieldProcessor> {
    val projectRequestFactory = DefaultPhotonFieldRequestFactory(
        domainId = StandardInformationDomains.PROJECT,
        projectionName = "information-asset-project-field/v1",
    )
    val replaySnapshots = EphemeralFieldSnapshotRepository()
    val enricher = DurableContextFieldEnricher(foundation.store)

    val project = ReplayableUniversalFieldProcessor(
        domainId = StandardInformationDomains.PROJECT,
        replayQuery = PhotonIndexQuery(
            mimeTypes = setOf(InformationAssetPhotonContract.MIME_TYPE),
            allTags = setOf("information-asset-kind:project"),
            latestOnly = true,
            limit = PhotonIndexQuery.HARD_PAGE_LIMIT,
        ),
        acceptsPhoton = { photon ->
            photon.mimeType == InformationAssetPhotonContract.MIME_TYPE &&
                "information-asset-kind:project" in photon.tags
        },
        productive = UniversalFieldRuntimeAdapter(
            snapshotRepository = world.fieldSnapshotRepository,
            requestFactory = projectRequestFactory,
            requestEnricher = enricher,
            engineProvider = { foundation.learnedFieldCalibration.engine() },
            healthGate = foundation.healthGate,
            thoughtGraphProjection = world.fieldThoughtGraphProjection,
        ),
        replayProcessor = UniversalFieldRuntimeAdapter(
            snapshotRepository = replaySnapshots,
            requestFactory = projectRequestFactory,
            requestEnricher = enricher,
            engineProvider = { foundation.learnedFieldCalibration.engine() },
        ),
        replaySnapshots = replaySnapshots,
    )

    return listOf(project)
}
