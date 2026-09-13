package app.lifeos.next.ui.chat

import app.lifeos.next.kernel.KernelBootstrapStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProcessingStateTest {
    @Test
    fun idleStateCanSendOnlyWhenRuntimeReadyAndDraftHasContent() {
        val idle = ChatTurnProcessingState.idle()

        assertTrue(ChatComposerPolicy.canSend("Hallo", KernelBootstrapStatus.READY, idle))
        assertTrue(ChatComposerPolicy.canSend("Hallo", KernelBootstrapStatus.DEGRADED, idle))
        assertFalse(ChatComposerPolicy.canSend("   ", KernelBootstrapStatus.READY, idle))
        assertFalse(ChatComposerPolicy.canSend("Hallo", KernelBootstrapStatus.LOADING, idle))
        assertFalse(ChatComposerPolicy.canSend("Hallo", KernelBootstrapStatus.FAILED, idle))
        assertNull(ChatComposerPolicy.statusLabel(idle))
    }

    @Test
    fun submittingAndResponsePersistenceSerializeCommittedSends() {
        val submitting = ChatTurnProcessingState.submitting("turn-1")
        val persisting = ChatTurnProcessingState.persistingResponse("turn-1")

        assertTrue(submitting.inFlight)
        assertTrue(persisting.inFlight)
        assertFalse(ChatComposerPolicy.canSend("zweite Nachricht", KernelBootstrapStatus.READY, submitting))
        assertFalse(ChatComposerPolicy.canSend("zweite Nachricht", KernelBootstrapStatus.READY, persisting))
        assertFalse(submitting.userTurnPersisted)
        assertTrue(persisting.userTurnPersisted)
        assertEquals(
            "Nachricht wird lokal gespeichert und verarbeitet …",
            ChatComposerPolicy.statusLabel(submitting),
        )
        assertEquals(
            "Antwort wird lokal gespeichert …",
            ChatComposerPolicy.statusLabel(persisting),
        )
    }

    @Test
    fun failedStateDistinguishesDurableUserTurnFromUncommittedFailure() {
        val beforePersist = ChatTurnProcessingState.failed(
            turnId = "turn-before",
            userTurnPersisted = false,
            message = "vault unavailable",
        )
        val afterPersist = ChatTurnProcessingState.failed(
            turnId = "turn-after",
            userTurnPersisted = true,
            message = "response persistence failed",
        )

        assertFalse(beforePersist.inFlight)
        assertFalse(afterPersist.inFlight)
        assertEquals("Nachricht konnte nicht gespeichert werden.", ChatComposerPolicy.statusLabel(beforePersist))
        assertEquals(
            "Nachricht ist gespeichert; die Antwortverarbeitung ist fehlgeschlagen.",
            ChatComposerPolicy.statusLabel(afterPersist),
        )
    }

    @Test
    fun invalidStateCombinationsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatTurnProcessingState(
                phase = ChatTurnPhase.SUBMITTING,
                turnId = "turn-1",
                userTurnPersisted = true,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatTurnProcessingState(
                phase = ChatTurnPhase.PERSISTING_RESPONSE,
                turnId = "turn-1",
                userTurnPersisted = false,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChatTurnProcessingState(
                phase = ChatTurnPhase.FAILED,
                turnId = "turn-1",
                failureMessage = "",
            )
        }
    }
}
