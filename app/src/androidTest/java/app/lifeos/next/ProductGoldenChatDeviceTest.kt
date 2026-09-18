package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.language.IntentType
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import app.lifeos.core.runtime.chat.ConversationProjector
import app.lifeos.core.runtime.topology.LifeOsProcessTopology
import app.lifeos.core.runtime.topology.LifeOsSubsystemState
import app.lifeos.next.kernel.KernelBootstrapState
import app.lifeos.next.kernel.KernelBootstrapStatus
import app.lifeos.next.kernel.LifeOsResponseComposer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end product contract for Photon-backed chat, cognition, topology and cold restart. */
@RunWith(AndroidJUnit4::class)
class ProductGoldenChatDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: LifeOsApplication
        get() = instrumentation.targetContext.applicationContext as LifeOsApplication

    @Test
    fun seedProductGoldChatRoundTrip() = runBlocking {
        assertTrue(awaitBoot().ready)
        assertProductTopology()

        val user = Photon(
            content = "Merke dir den LIFEOS Product-Gold Chat-Sentinel.",
            provenance = Provenance(
                source = "product-gold-chat-device-test",
                actor = "user",
            ),
            tags = setOf(
                "chat",
                "chat:user",
                "conversation:default",
                "turn:product-gold-device",
                USER_SENTINEL_TAG,
            ),
        )
        val submission = app.kernel.persistUserUtterance(user)
        val response = LifeOsResponseComposer.compose(submission)
        assertTrue("LIFEOS response must not be blank", response.isNotBlank())

        val assistant = Photon(
            content = response,
            provenance = Provenance(
                source = "lifeos-chat",
                actor = "lifeos",
                parentIds = setOf(user.id),
            ),
            relations = setOf(
                PhotonRelation(
                    target = user.id,
                    type = RelationType.DERIVED_FROM,
                )
            ),
            tags = setOf(
                "chat",
                "chat:assistant",
                "conversation:default",
                "turn:product-gold-device",
                ASSISTANT_SENTINEL_TAG,
            ),
        )
        val persisted = app.kernel.persistAndIngest(assistant)
        assertTrue("Assistant Photon must enter durable cognition", persisted.processingQueued)

        withTimeout(BOOT_TIMEOUT_MS) {
            app.kernel.matrix.state.first { state ->
                state.nodes[assistant.id]?.revision == assistant.revision
            }
        }

        val events = ConversationProjector.project(app.kernel.photonStore.loadAll())
        assertTrue(events.any { it.photonId == user.id })
        assertTrue(events.any { it.photonId == assistant.id })
        assertEquals(user.id, assistant.relations.single().target)
    }

    @Test
    fun seedSemanticGoalV4RoundTrip() = runBlocking {
        assertTrue(awaitBoot().ready)
        val user = Photon(
            content = "Wie erstelle ich ein Bild?",
            provenance = Provenance(
                source = "product-gold-semantic-device-test",
                actor = "user",
            ),
            tags = setOf(
                "chat",
                "chat:user",
                "conversation:default",
                SEMANTIC_USER_SENTINEL_TAG,
            ),
        )

        val submission = app.kernel.persistUserUtterance(user)

        assertTrue(submission.languageUnderstood)
        assertEquals(IntentType.QUERY, submission.effectiveGoal?.intent)
        assertTrue(submission.effectiveGoal?.semanticActionGraph?.executableNodes?.isEmpty() == true)
        assertNotNull(submission.goal)
        val persistedGoal = requireNotNull(submission.goal).photon
        assertTrue(persistedGoal.content.startsWith("goal/v4\n"))
        assertTrue(persistedGoal.content.contains("action.fingerprint="))
        assertTrue(persistedGoal.provenance.parentIds.contains(user.id))
        assertEquals(null, submission.externalEffect)
    }

    @Test
    fun recoverProductGoldChatRoundTrip() = runBlocking {
        assertTrue(awaitBoot().ready)
        assertProductTopology()

        val photons = app.kernel.photonStore.loadAll()
        val user = photons.singleOrNull { USER_SENTINEL_TAG in it.tags }
        val assistant = photons.singleOrNull { ASSISTANT_SENTINEL_TAG in it.tags }
        assertNotNull("Cold restart must preserve Product-Gold user Photon", user)
        assertNotNull("Cold restart must preserve Product-Gold assistant Photon", assistant)
        user!!
        assistant!!

        withTimeout(BOOT_TIMEOUT_MS) {
            app.kernel.matrix.state.first { state ->
                state.nodes[assistant.id]?.revision == assistant.revision
            }
        }

        val events = ConversationProjector.project(photons)
        val turnEvents = events.filter { it.turnId == "product-gold-device" }
        assertEquals(2, turnEvents.size)
        assertTrue(turnEvents.any { it.photonId == user.id })
        assertTrue(turnEvents.any { it.photonId == assistant.id })
    }

    private suspend fun assertProductTopology() {
        val topology = LifeOsProcessTopology.snapshot()
        assertNotNull("Product topology must be installed", topology)
        topology!!
        assertTrue(
            "Canonical topology must preserve the established LIFEOS baseline",
            topology.registeredSubsystemCount >= LifeOsProcessTopology.MINIMUM_CANONICAL_SUBSYSTEMS,
        )
        val mandatory = topology.subsystems.filterNot { it.descriptor.id in ADAPTIVE_ONLY_SUBSYSTEMS }
        val notOperational = mandatory.filter {
            it.state != LifeOsSubsystemState.ACTIVE && it.state != LifeOsSubsystemState.DEGRADED
        }
        assertTrue(
            "Product core contains unbound/unavailable subsystems: ${notOperational.map { it.descriptor.id }}",
            notOperational.isEmpty(),
        )
        assertFalse("Product core must not be empty", mandatory.isEmpty())
    }

    private suspend fun awaitBoot(): KernelBootstrapState = withTimeout(BOOT_TIMEOUT_MS) {
        val processStartup = app.startupState.first { state ->
            state.phase == LifeOsProcessStartupPhase.READY ||
                state.phase == LifeOsProcessStartupPhase.FAILED
        }
        if (processStartup.phase == LifeOsProcessStartupPhase.FAILED) {
            error("Process startup failed during Product-Gold chat recovery: ${processStartup.failure ?: "unknown"}")
        }
        app.kernel.bootstrapState.first { state ->
            state.status == KernelBootstrapStatus.READY ||
                state.status == KernelBootstrapStatus.DEGRADED ||
                state.status == KernelBootstrapStatus.FAILED
        }.also { state ->
            if (state.status == KernelBootstrapStatus.FAILED) {
                error("Kernel boot failed during Product-Gold chat recovery: ${state.failureMessage ?: "unknown"}")
            }
        }
    }

    companion object {
        private const val BOOT_TIMEOUT_MS = 20_000L
        private const val USER_SENTINEL_TAG = "product-gold-chat:user"
        private const val ASSISTANT_SENTINEL_TAG = "product-gold-chat:assistant"
        private const val SEMANTIC_USER_SENTINEL_TAG = "product-gold-semantic:user"
        private val ADAPTIVE_ONLY_SUBSYSTEMS = setOf("build-studio")
    }
}
