package app.lifeos.next

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lifeos.core.data.language.EncryptedLanguageRuntimeRepository
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.VersionedLanguageRuntime
import app.lifeos.core.runtime.personal.DurableLanguageRuntimeCoordinator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageRuntimeRecoveryDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val root: File
        get() = instrumentation.targetContext.filesDir.resolve("language-runtime-process-death-gold")
    private val marker: File
        get() = root.resolve("expected-language-runtime.txt")

    @Test
    fun seedPromotedPersonalLanguageSnapshot() = runBlocking {
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val context = isolatedContext()
        val runtime = VersionedLanguageRuntime()
        val coordinator = DurableLanguageRuntimeCoordinator(
            runtime,
            EncryptedLanguageRuntimeRepository(context),
        )
        val baseline = coordinator.rehydrate().lexicon
        val continueConcept = baseline.concepts.first { IntentType.CONTINUE in it.intentBias }
        val promoted = coordinator.promote(
            concepts = baseline.concepts.map { concept ->
                if (concept.id == continueConcept.id) {
                    concept.copy(variants = concept.variants + "weiterso")
                } else {
                    concept
                }
            },
            promotionEvidenceFingerprint = "device-language-evidence",
        ).lexicon

        assertEquals(2L, promoted.revision)
        assertEquals(baseline.fingerprint, promoted.predecessorFingerprint)
        assertEquals(
            IntentType.CONTINUE,
            runtime.current().understanding.understand("weiterso").goal.intent,
        )
        marker.writeText(
            listOf(
                baseline.fingerprint,
                promoted.fingerprint,
                promoted.revision.toString(),
            ).joinToString("\n")
        )
        assertTrue(marker.isFile)
    }

    @Test
    fun recoverPromotedSnapshotThenRollbackDurablyAfterColdStart() = runBlocking {
        assertTrue(marker.isFile)
        val expected = marker.readLines()
        assertEquals(3, expected.size)

        val context = isolatedContext()
        val recoveredRuntime = VersionedLanguageRuntime()
        val recoveredCoordinator = DurableLanguageRuntimeCoordinator(
            recoveredRuntime,
            EncryptedLanguageRuntimeRepository(context),
        )
        val recovered = recoveredCoordinator.rehydrate()

        assertEquals(expected[1], recovered.lexicon.fingerprint)
        assertEquals(expected[2].toLong(), recovered.lexicon.revision)
        assertEquals(
            IntentType.CONTINUE,
            recovered.understanding.understand("weiterso").goal.intent,
        )

        val rolledBack = recoveredCoordinator.rollback(expected[0])
        assertEquals(expected[0], rolledBack.lexicon.fingerprint)
        val rollbackHead = recoveredCoordinator.currentHead()
        assertEquals(3L, rollbackHead.headRevision)
        assertEquals(expected[1], rollbackHead.previousActiveSnapshotFingerprint)

        val reopened = DurableLanguageRuntimeCoordinator(
            VersionedLanguageRuntime(),
            EncryptedLanguageRuntimeRepository(context),
        ).rehydrate()
        assertEquals(expected[0], reopened.lexicon.fingerprint)
    }

    private fun isolatedContext(): Context =
        object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = root
        }
}
