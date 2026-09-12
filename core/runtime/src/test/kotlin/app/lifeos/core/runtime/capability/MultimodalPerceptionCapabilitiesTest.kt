package app.lifeos.core.runtime.capability

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultimodalPerceptionCapabilitiesTest {
    @Test
    fun localSpeechAndWritingFieldsRegisterAsSystemCapabilities() = runTest {
        val registry = CapabilityRegistry()
        MultimodalPerceptionCapabilityInstaller(registry).installLocalFields()

        val speech = registry.providersFor(CapabilityId(MultimodalPerceptionCapabilities.SPEECH_FIELD))
        val writing = registry.providersFor(CapabilityId(MultimodalPerceptionCapabilities.WRITING_FIELD))
        assertEquals(listOf("lifeos-speech-field"), speech.map { it.providerId })
        assertEquals(listOf("lifeos-writing-field"), writing.map { it.providerId })
        assertTrue((speech + writing).all { it.state == ProviderState.ACTIVE && it.trustLevel == TrustLevel.SYSTEM })
    }

    @Test
    fun absentVisualAdapterNeverCreatesAnActiveVisualProvider() = runTest {
        val registry = CapabilityRegistry()
        MultimodalPerceptionCapabilityInstaller(registry).installLocalFields()

        assertTrue(
            registry.providersFor(
                CapabilityId(MultimodalPerceptionCapabilities.VISUAL_OBSERVATION),
                includeUnavailable = true,
            ).isEmpty()
        )
    }

    @Test
    fun visualProviderStateComesFromConcreteAdapterHealth() = runTest {
        val registry = CapabilityRegistry()
        val installer = MultimodalPerceptionCapabilityInstaller(registry)
        val degraded = object : VisualPerceptionAdapterHealthSource {
            override val providerId: String = "camera-writing-adapter"
            override suspend fun health() = VisualPerceptionAdapterHealth(
                state = VisualPerceptionAdapterState.DEGRADED,
                reliability = 0.72,
                detail = "limited visual confidence",
            )
        }

        val descriptor = installer.installVisualAdapter(degraded)

        assertEquals(ProviderState.DEGRADED, descriptor.state)
        assertEquals(0.72, descriptor.reliability)
        assertTrue(
            registry.providersFor(CapabilityId(MultimodalPerceptionCapabilities.VISUAL_OBSERVATION))
                .none { it.state == ProviderState.ACTIVE }
        )
    }

    @Test
    fun quarantinedAdapterIsNotReturnedAsUsableVisualCapability() = runTest {
        val registry = CapabilityRegistry()
        val installer = MultimodalPerceptionCapabilityInstaller(registry)
        val quarantined = object : VisualPerceptionAdapterHealthSource {
            override val providerId: String = "quarantined-camera"
            override suspend fun health() = VisualPerceptionAdapterHealth(
                state = VisualPerceptionAdapterState.QUARANTINED,
                reliability = 0.2,
            )
        }

        installer.installVisualAdapter(quarantined)

        assertTrue(registry.providersFor(CapabilityId(MultimodalPerceptionCapabilities.VISUAL_OBSERVATION)).isEmpty())
        assertEquals(
            ProviderState.QUARANTINED,
            registry.providersFor(
                CapabilityId(MultimodalPerceptionCapabilities.VISUAL_OBSERVATION),
                includeUnavailable = true,
            ).single().state,
        )
    }
}
