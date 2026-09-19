package app.lifeos.core.runtime.world

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WorldEquationPackStructuralValidationLoadReport(
    val bundles: List<WorldEquationPackStructuralValidationBundle>,
    val unreadableEntries: List<String>,
) {
    init {
        require(
            bundles.map { it.candidatePackFingerprint }.distinct().size == bundles.size
        ) {
            "Structural validation report contains duplicate candidates"
        }
        require(unreadableEntries.none { it.isBlank() })
    }

    val corrupted: Boolean
        get() = unreadableEntries.isNotEmpty()
}

interface WorldEquationPackStructuralValidationRepository {
    suspend fun putIfAbsent(bundle: WorldEquationPackStructuralValidationBundle)
    suspend fun load(candidatePackFingerprint: String): WorldEquationPackStructuralValidationBundle?
    suspend fun loadReport(): WorldEquationPackStructuralValidationLoadReport
}

class InMemoryWorldEquationPackStructuralValidationRepository(
    initial: List<WorldEquationPackStructuralValidationBundle> = emptyList(),
) : WorldEquationPackStructuralValidationRepository {
    private val mutex = Mutex()
    private val byCandidate = linkedMapOf<String, WorldEquationPackStructuralValidationBundle>()

    init {
        initial.forEach { bundle ->
            require(bundle.candidatePackFingerprint !in byCandidate)
            byCandidate[bundle.candidatePackFingerprint] = bundle
        }
    }

    override suspend fun putIfAbsent(
        bundle: WorldEquationPackStructuralValidationBundle,
    ) = mutex.withLock {
        val existing = byCandidate[bundle.candidatePackFingerprint]
        require(existing == null || existing.fingerprint == bundle.fingerprint) {
            "Structural validation candidate already maps to another immutable bundle"
        }
        if (existing == null) {
            byCandidate[bundle.candidatePackFingerprint] = bundle
        }
    }

    override suspend fun load(
        candidatePackFingerprint: String,
    ): WorldEquationPackStructuralValidationBundle? = mutex.withLock {
        require(candidatePackFingerprint.isNotBlank())
        byCandidate[candidatePackFingerprint]
    }

    override suspend fun loadReport(): WorldEquationPackStructuralValidationLoadReport =
        mutex.withLock {
            WorldEquationPackStructuralValidationLoadReport(
                bundles = byCandidate.values.sortedBy { it.candidatePackFingerprint },
                unreadableEntries = emptyList(),
            )
        }
}
