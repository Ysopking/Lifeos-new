package app.lifeos.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.language.IntentType
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonIndexOrder
import app.lifeos.core.model.PhotonIndexQuery
import app.lifeos.core.model.Provenance
import app.lifeos.core.runtime.personal.PersonalConversationCorpusImporter
import app.lifeos.core.runtime.personal.PersonalConversationSource
import app.lifeos.core.runtime.personal.PersonalConversationSpeaker
import app.lifeos.core.runtime.personal.PersonalConversationTurn
import app.lifeos.next.kernel.KernelBootstrapState
import app.lifeos.next.kernel.KernelBootstrapStatus
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalCorpusLanguageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app: LifeOsApplication
        get() = instrumentation.targetContext.applicationContext as LifeOsApplication

    @Test
    fun importedOwnerCorpusCanAssistLiveTurnWithoutEnteringRawContext() {
        runBlocking {
            assertTrue(awaitBoot().ready)
            val beforeFingerprint = app.kernel.currentLanguageSnapshot().lexicon.fingerprint
            val importer = PersonalConversationCorpusImporter(app.kernel.photonStore)
            val observedAt = Instant.parse("2026-09-20T08:45:00Z")
            val alias = "corpusshadowxy"
            val archiveText = "weiter $alias"
            importer.import(
                (1..4).map { index ->
                    PersonalConversationTurn(
                        source = PersonalConversationSource.WHATSAPP,
                        conversationId = "corpus-shadow-device-$index",
                        speaker = PersonalConversationSpeaker.OWNER,
                        text = archiveText,
                        observedAt = observedAt.minusSeconds(index.toLong()),
                        externalMessageId = "owner-$index",
                    )
                }
            )

            val user = Photon(
                content = alias,
                provenance = Provenance(
                    source = "personal-corpus-language-device-test",
                    actor = "user",
                    createdAt = observedAt.plusSeconds(30),
                ),
                tags = setOf(
                    "chat",
                    "chat:user",
                    "conversation:personal-corpus-language-device",
                ),
            )

            val submission = app.kernel.persistUserUtterance(user)

            assertTrue(
                submission.languageFailure ?: "Personal corpus language path did not understand turn",
                submission.languageUnderstood,
            )
            val understanding = assertNotNull(submission.understanding)
            assertEquals(IntentType.CONTINUE, understanding.goal.intent)
            assertEquals(
                beforeFingerprint,
                app.kernel.currentLanguageSnapshot().lexicon.fingerprint,
            )
            assertTrue(
                understanding.context?.items?.none { "corpus:archive" in it.tags } == true
            )

            val evidenceRefs = app.kernel.photonStore.query(
                PhotonIndexQuery(
                    allTags = setOf("personal-corpus-language-evidence"),
                    latestOnly = true,
                    order = PhotonIndexOrder.NEWEST_FIRST,
                    limit = 8,
                )
            )
            val evidence = evidenceRefs
                .mapNotNull { app.kernel.photonStore.load(it) }
                .firstOrNull { user.id in it.provenance.parentIds }
            assertNotNull(evidence)
            evidence!!
            assertTrue("privacy:local-only" in evidence.tags)
            assertTrue("privacy:no-external-export" in evidence.tags)
            assertFalse(evidence.content.contains(archiveText))
            assertFalse(evidence.content.contains("corpus-shadow-device-"))
        }
    }

    private suspend fun awaitBoot(): KernelBootstrapState = withTimeout(BOOT_TIMEOUT_MS) {
        val startup = app.startupState.first { state ->
            state.phase == LifeOsProcessStartupPhase.READY ||
                state.phase == LifeOsProcessStartupPhase.FAILED
        }
        if (startup.phase == LifeOsProcessStartupPhase.FAILED) {
            error("Process startup failed: ${startup.failure ?: "unknown"}")
        }
        app.kernel.bootstrapState.first { state ->
            state.status == KernelBootstrapStatus.READY ||
                state.status == KernelBootstrapStatus.DEGRADED ||
                state.status == KernelBootstrapStatus.FAILED
        }.also { state ->
            if (state.status == KernelBootstrapStatus.FAILED) {
                error("Kernel boot failed: ${state.failureMessage ?: "unknown"}")
            }
        }
    }

    private companion object {
        const val BOOT_TIMEOUT_MS = 20_000L
    }
}
