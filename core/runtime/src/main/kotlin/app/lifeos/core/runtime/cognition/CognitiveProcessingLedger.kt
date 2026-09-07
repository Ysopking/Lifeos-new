package app.lifeos.core.runtime.cognition

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CognitiveProcessingKey(
    val deltaId: String,
    val moduleId: String,
    val operation: String,
    val inputRevision: Long,
) {
    init {
        require(deltaId.isNotBlank()) { "Delta id must not be blank" }
        require(moduleId.isNotBlank()) { "Module id must not be blank" }
        require(operation.isNotBlank()) { "Operation must not be blank" }
        require(inputRevision > 0) { "Input revision must be positive" }
    }
}

enum class ProcessingState {
    PROCESSING,
    COMMITTED,
}

class CognitiveProcessingLedger {
    private val mutex = Mutex()
    private val states = mutableMapOf<CognitiveProcessingKey, ProcessingState>()

    suspend fun tryStart(key: CognitiveProcessingKey): Boolean = mutex.withLock {
        if (key in states) return@withLock false
        states[key] = ProcessingState.PROCESSING
        true
    }

    suspend fun commit(key: CognitiveProcessingKey): Boolean = mutex.withLock {
        if (states[key] != ProcessingState.PROCESSING) return@withLock false
        states[key] = ProcessingState.COMMITTED
        true
    }

    suspend fun abort(key: CognitiveProcessingKey): Boolean = mutex.withLock {
        if (states[key] != ProcessingState.PROCESSING) return@withLock false
        states.remove(key)
        true
    }

    suspend fun state(key: CognitiveProcessingKey): ProcessingState? = mutex.withLock {
        states[key]
    }
}
