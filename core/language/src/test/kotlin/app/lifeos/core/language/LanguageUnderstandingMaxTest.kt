package app.lifeos.core.language

import app.lifeos.core.model.PhotonId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LanguageUnderstandingMaxTest {
    private val engine = LanguageUnderstandingEngine()
    private val now = Instant.parse("2026-09-21T12:00:00Z")

    @Test
    fun `colloquial search command is understood compositionally`() {
        val result = engine.understand("Schau bitte nach dem Bescheid.", LanguageContext(now = now))

        assertEquals(IntentType.SEARCH, result.goal.intent)
        val search = result.goal.semanticActionGraph.nodes.single {
            it.frame.predicate == PredicateConcept.SEARCH
        }
        assertEquals(SpeechActType.COMMAND, search.frame.speechAct.type)
        assertTrue(search.frame.evidence.any { it.source == "predicate-syntax-v2" })
    }

    @Test
    fun `separable build verb survives intervening arguments`() {
        val result = engine.understand("Setz das bitte um.", LanguageContext(now = now))

        assertEquals(IntentType.BUILD_OR_IMPLEMENT, result.goal.intent)
        val build = result.goal.semanticActionGraph.nodes.single {
            it.frame.predicate == PredicateConcept.BUILD
        }
        assertTrue(build.frame.evidence.any { it.source == "predicate-paraphrase/v1" })
    }

    @Test
    fun `field semantics understand indirect image creation wording`() {
        val result = engine.understand(
            "Kannst du ein Bild machen?",
            LanguageContext(now = now),
        )

        assertEquals(IntentType.CREATE_IMAGE, result.goal.intent)
        val create = result.goal.semanticActionGraph.nodes.firstOrNull {
            it.frame.predicate == PredicateConcept.CREATE_IMAGE
        }
        assertNotNull(create)
        assertTrue(create.frame.evidence.any {
            it.source == "linguistic-field-predicate/v1" ||
                it.source == "predicate-syntax-v2"
        })
    }

    @Test
    fun `noun free deictic image follow up binds active image revision`() {
        val imageId = PhotonId("active-image")
        val image = LanguageContextItem(
            photonId = imageId,
            kind = "image",
            tags = setOf("image"),
            createdAt = now.minusSeconds(5),
            active = true,
            contentTerms = setOf("bild", "portrait"),
            revisionRef = app.lifeos.core.model.PhotonRevisionRef(imageId, 7L),
            semanticTypes = setOf("image"),
        )
        val result = engine.understand(
            "Mach es heller.",
            LanguageContext(items = listOf(image), now = now),
        )

        assertEquals(IntentType.TRANSFORM_IMAGE, result.goal.intent)
        val transform = result.goal.semanticActionGraph.nodes.single {
            it.frame.predicate == PredicateConcept.TRANSFORM_IMAGE
        }
        assertEquals(image.revisionRef, transform.frame.roles[SemanticRole.OBJECT]?.referencePhoton)
        assertTrue(transform.executable)
    }

    @Test
    fun `descriptive paraphrase recognition cannot silently authorize communication`() {
        val result = engine.understand(
            "Gib Anna Bescheid.",
            LanguageContext(now = now),
        )

        assertEquals(IntentType.COMMUNICATE, result.goal.intent)
        val communicate = result.goal.semanticActionGraph.nodes.single {
            it.frame.predicate == PredicateConcept.COMMUNICATE
        }
        assertTrue(communicate.frame.evidence.any { it.source == "predicate-paraphrase/v1" })
        assertTrue(communicate.frame.confidence < SemanticActionNode.MIN_EXECUTION_READINESS)
        assertFalse(communicate.executable)
        assertFalse(SemanticExecutionGate.externalEffectAllowed(result.goal))
    }

    @Test
    fun `elliptical correction inherits descriptive prior intent without execution authority`() {
        val prior = LanguageContextItem(
            photonId = PhotonId("prior-schedule-goal"),
            kind = "goal",
            tags = setOf("goal", "intent:schedule"),
            createdAt = now.minusSeconds(30),
            active = true,
            contentTerms = setOf("termin", "morgen"),
        )
        val result = engine.understand(
            "Nein, Freitag statt morgen.",
            LanguageContext(items = listOf(prior), now = now),
        )

        assertEquals(IntentType.SCHEDULE, result.goal.intent)
        assertTrue(result.intentEvidence.any {
            it.intent == IntentType.SCHEDULE &&
                it.reasons.any { reason -> reason == "discourse-ellipsis:v1" }
        })
        assertTrue(result.goal.semanticActionGraph.executableNodes.isEmpty())
        assertFalse(SemanticExecutionGate.externalEffectAllowed(result.goal))
    }

    @Test
    fun `short repeat can inherit topic but never fabricate an action frame`() {
        val prior = LanguageContextItem(
            photonId = PhotonId("prior-image-goal"),
            kind = "goal",
            tags = setOf("goal", "intent:create_image"),
            createdAt = now.minusSeconds(10),
            active = true,
            contentTerms = setOf("bild"),
        )
        val result = engine.understand(
            "Nochmal.",
            LanguageContext(items = listOf(prior), now = now),
        )

        assertEquals(IntentType.CREATE_IMAGE, result.goal.intent)
        assertTrue(result.goal.semanticActionGraph.executableNodes.isEmpty())
        assertFalse(SemanticExecutionGate.externalEffectAllowed(result.goal))
    }
}
