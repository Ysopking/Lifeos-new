package app.lifeos.core.runtime.world

import app.lifeos.core.field.world.WorldEquationSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WorldEquationSpecLoadReport(
    val specs: List<WorldEquationSpec>,
    val unreadableEntries: List<String>,
) {
    init {
        require(specs.map { it.version }.distinct().size == specs.size) {
            "World equation spec report contains duplicate versions"
        }
        require(unreadableEntries.none { it.isBlank() })
    }

    val corrupted: Boolean
        get() = unreadableEntries.isNotEmpty()
}

interface WorldEquationSpecRepository {
    suspend fun putIfAbsent(spec: WorldEquationSpec)
    suspend fun load(version: String): WorldEquationSpec?
    suspend fun loadReport(): WorldEquationSpecLoadReport
}

class InMemoryWorldEquationSpecRepository(
    initial: List<WorldEquationSpec> = emptyList(),
) : WorldEquationSpecRepository {
    private val mutex = Mutex()
    private val byVersion = linkedMapOf<String, WorldEquationSpec>()

    init {
        initial.forEach { spec ->
            require(spec.version !in byVersion)
            byVersion[spec.version] = spec
        }
    }

    override suspend fun putIfAbsent(spec: WorldEquationSpec) = mutex.withLock {
        val existing = byVersion[spec.version]
        require(existing == null || existing.fingerprint() == spec.fingerprint()) {
            "World equation version ${spec.version} already maps to another durable spec"
        }
        if (existing == null) byVersion[spec.version] = spec
    }

    override suspend fun load(version: String): WorldEquationSpec? = mutex.withLock {
        byVersion[version]
    }

    override suspend fun loadReport(): WorldEquationSpecLoadReport = mutex.withLock {
        WorldEquationSpecLoadReport(
            specs = byVersion.values.sortedBy { it.version },
            unreadableEntries = emptyList(),
        )
    }
}
