package app.lifeos.core.runtime.world

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WorldEquationPackLoadReport(
    val packs: List<WorldEquationPack>,
    val unreadableEntries: List<String>,
) {
    init {
        require(packs.map { it.version }.distinct().size == packs.size) {
            "WorldEquationPack report contains duplicate versions"
        }
        require(packs.map { it.fingerprint() }.distinct().size == packs.size) {
            "WorldEquationPack report contains duplicate artifacts"
        }
        require(unreadableEntries.none { it.isBlank() })
    }

    val corrupted: Boolean
        get() = unreadableEntries.isNotEmpty()
}

interface WorldEquationPackRepository {
    suspend fun putIfAbsent(pack: WorldEquationPack)
    suspend fun load(version: String): WorldEquationPack?
    suspend fun loadReport(): WorldEquationPackLoadReport
}

class InMemoryWorldEquationPackRepository(
    initial: List<WorldEquationPack> = emptyList(),
) : WorldEquationPackRepository {
    private val mutex = Mutex()
    private val byVersion = linkedMapOf<String, WorldEquationPack>()

    init {
        initial.forEach { pack ->
            require(pack.version !in byVersion)
            byVersion[pack.version] = pack
        }
    }

    override suspend fun putIfAbsent(pack: WorldEquationPack) = mutex.withLock {
        val existing = byVersion[pack.version]
        require(existing == null || existing.fingerprint() == pack.fingerprint()) {
            "WorldEquationPack version " + pack.version + " already maps to another artifact"
        }
        if (existing == null) byVersion[pack.version] = pack
    }

    override suspend fun load(version: String): WorldEquationPack? = mutex.withLock {
        require(version.isNotBlank())
        byVersion[version]
    }

    override suspend fun loadReport(): WorldEquationPackLoadReport = mutex.withLock {
        WorldEquationPackLoadReport(
            packs = byVersion.values.sortedBy { it.version },
            unreadableEntries = emptyList(),
        )
    }
}
