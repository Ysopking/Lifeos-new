package app.lifeos.core.runtime.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Content-free provenance for protection changes; sequence is the concurrency token. */
data class HealthDecision(val revision: Long, val action: String, val target: String, val actor: String)
data class HealthControlSnapshot(
    val revision: Long = 0,
    val quarantined: Set<String> = emptySet(),
    val safeReasons: Set<String> = emptySet(),
    val decisions: List<HealthDecision> = emptyList(),
)
interface HealthControlStore {
    fun load(): HealthControlSnapshot
    fun save(snapshot: HealthControlSnapshot)
}
class MemoryHealthControlStore : HealthControlStore {
    private var value = HealthControlSnapshot()
    override fun load() = value
    override fun save(snapshot: HealthControlSnapshot) { value = snapshot }
}

/** Process-level owner. Failed reads preserve the original and prohibit overwriting it. */
class HealthControlRepository(private val store: HealthControlStore = MemoryHealthControlStore()) {
    private var loaded = false
    private var persistenceFailed = false
    private val mutableSnapshot = MutableStateFlow(HealthControlSnapshot())
    val changes = mutableSnapshot.asStateFlow()

    @Synchronized fun snapshot(): HealthControlSnapshot {
        if (!loaded) {
            try { mutableSnapshot.value = store.load() }
            catch (error: Exception) {
                persistenceFailed = true
                mutableSnapshot.value = HealthControlSnapshot(safeReasons = setOf(PERSISTENCE_FAILURE))
            }
            loaded = true
        }
        return mutableSnapshot.value
    }

    @Synchronized fun protect(target: String, quarantine: Boolean) {
        val old = snapshot()
        val next = old.copy(
            revision = Math.addExact(old.revision, 1),
            quarantined = if (quarantine) old.quarantined + target else old.quarantined,
            safeReasons = if (quarantine) old.safeReasons else old.safeReasons + target,
            decisions = (old.decisions + HealthDecision(old.revision + 1,
                if (quarantine) "QUARANTINE" else "SAFE_MODE", target, "runtime")).takeLast(100),
        )
        // Restriction applies even if persistence fails; never report a false successful release.
        try {
            check(!persistenceFailed)
            store.save(next)
            mutableSnapshot.value = next
        } catch (error: Exception) {
            persistenceFailed = true
            mutableSnapshot.value = next.copy(safeReasons = next.safeReasons + PERSISTENCE_FAILURE)
        }
    }

    @Synchronized internal fun releaseVerified(expectedRevision: Long, targets: Set<String>): Boolean {
        val old = snapshot()
        if (persistenceFailed || old.revision != expectedRevision || PERSISTENCE_FAILURE in targets) return false
        val next = old.copy(
            revision = Math.addExact(old.revision, 1),
            quarantined = old.quarantined - targets,
            safeReasons = old.safeReasons - targets,
            decisions = (old.decisions + targets.sorted().map {
                HealthDecision(old.revision + 1, "VERIFIED_RELEASE", it, "user-requested-probe")
            }).takeLast(100),
        )
        return try {
            store.save(next)
            mutableSnapshot.value = next
            true
        } catch (error: Exception) {
            persistenceFailed = true
            mutableSnapshot.value = old.copy(safeReasons = old.safeReasons + PERSISTENCE_FAILURE)
            false
        }
    }
    companion object { const val PERSISTENCE_FAILURE = "HealthPersistence" }
}
