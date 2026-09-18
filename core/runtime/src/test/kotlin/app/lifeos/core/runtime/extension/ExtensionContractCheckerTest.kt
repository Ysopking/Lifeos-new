package app.lifeos.core.runtime.extension

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ExtensionContractCheckerTest {
    @Test
    fun acceptsSameMajorAndHostNewerMinor() {
        val compatibility = ExtensionContractChecker.check(
            manifest = manifest(),
            extensionContract = contract(signalMinor = 1, nodeMinor = 1, equationMinor = 1),
            hostAbiVersion = ExtensionAbiVersion(major = 1, minor = 4),
            hostContract = contract(signalMinor = 3, nodeMinor = 2, equationMinor = 5),
        )

        assertIs<ExtensionContractCompatibility.Compatible>(compatibility)
    }

    @Test
    fun blocksEveryMajorMismatchFailClosed() {
        val compatibility = ExtensionContractChecker.check(
            manifest = manifest(abi = ExtensionAbiVersion(major = 2, minor = 0)),
            extensionContract = contract(
                signalMajor = 2,
                nodeMajor = 3,
                equationMajor = 4,
            ),
            hostAbiVersion = ExtensionAbiVersion(major = 1, minor = 9),
            hostContract = contract(),
        )

        val blocked = assertIs<ExtensionContractCompatibility.Blocked>(compatibility)
        assertEquals(
            setOf(
                ExtensionContractMismatch.ABI_MAJOR,
                ExtensionContractMismatch.WORLD_SIGNAL_SCHEMA_MAJOR,
                ExtensionContractMismatch.WORLD_NODE_SCHEMA_MAJOR,
                ExtensionContractMismatch.WORLD_EQUATION_MAJOR,
            ),
            blocked.mismatches,
        )
    }

    @Test
    fun blocksWhenExtensionRequiresNewerHostMinor() {
        val compatibility = ExtensionContractChecker.check(
            manifest = manifest(),
            extensionContract = contract(
                signalMinor = 4,
                nodeMinor = 5,
                equationMinor = 6,
            ),
            hostAbiVersion = ExtensionAbiVersion.CURRENT,
            hostContract = contract(signalMinor = 3, nodeMinor = 4, equationMinor = 5),
        )

        val blocked = assertIs<ExtensionContractCompatibility.Blocked>(compatibility)
        assertEquals(
            setOf(
                ExtensionContractMismatch.WORLD_SIGNAL_SCHEMA_MINOR,
                ExtensionContractMismatch.WORLD_NODE_SCHEMA_MINOR,
                ExtensionContractMismatch.WORLD_EQUATION_MINOR,
            ),
            blocked.mismatches,
        )
    }

    @Test
    fun bindsEquationAndSchemaFingerprintsExactly() {
        val extension = contract(
            equationId = "lifeos-world-cognitive-v1",
            coefficient = "coeff-extension",
            projection = "projection-extension",
        )
        val host = contract(
            equationId = "lifeos-world-informational-v1",
            coefficient = "coeff-host",
            projection = "projection-host",
        )

        val blocked = assertIs<ExtensionContractCompatibility.Blocked>(
            ExtensionContractChecker.check(
                manifest = manifest(),
                extensionContract = extension,
                hostAbiVersion = ExtensionAbiVersion.CURRENT,
                hostContract = host,
            )
        )

        assertEquals(
            setOf(
                ExtensionContractMismatch.WORLD_EQUATION_ID,
                ExtensionContractMismatch.COEFFICIENT_SCHEMA_FINGERPRINT,
                ExtensionContractMismatch.PROJECTION_CONTRACT_FINGERPRINT,
            ),
            blocked.mismatches,
        )
    }

    private fun manifest(
        abi: ExtensionAbiVersion = ExtensionAbiVersion.CURRENT,
    ) = ExtensionManifest(
        extensionId = ExtensionId("extension.world.signal"),
        version = ExtensionVersion("1.0.0"),
        abiVersion = abi,
        kind = ExtensionKind.WORLD_SIGNAL_PACK,
        providerId = "lifeos.test",
        entrypoints = setOf(
            ExtensionEntrypoint("world.signal.projector", "projector.test"),
        ),
    )

    private fun contract(
        signalMajor: Int = 1,
        signalMinor: Int = 0,
        nodeMajor: Int = 1,
        nodeMinor: Int = 0,
        equationId: String = "lifeos-world-informational-v1",
        equationMajor: Int = 1,
        equationMinor: Int = 0,
        coefficient: String = "coeff-v1",
        projection: String = "projection-v1",
    ) = ExtensionWorldContract(
        worldSignalSchemaVersion = WorldSignalSchemaVersion(signalMajor, signalMinor),
        worldNodeSchemaVersion = WorldNodeSchemaVersion(nodeMajor, nodeMinor),
        worldEquationVersion = WorldEquationVersion(equationId, equationMajor, equationMinor),
        coefficientSchemaFingerprint = CoefficientSchemaFingerprint(coefficient),
        projectionContractFingerprint = ProjectionContractFingerprint(projection),
    )
}
