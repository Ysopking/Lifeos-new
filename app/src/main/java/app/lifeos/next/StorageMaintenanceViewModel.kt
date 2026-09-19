package app.lifeos.next

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lifeos.next.kernel.PrivateOwnerPolicyBaseline
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StorageTrashUiItem(
    val id: String,
    val originalPath: String,
    val sizeBytes: Long,
    val movedAt: Instant,
    val state: String,
)

data class StorageMaintenanceUiState(
    val broadFileAccess: Boolean = false,
    val authorized: Boolean = false,
    val scanPhase: String = "not-started",
    val indexedFiles: Long = 0L,
    val indexedBytes: Long = 0L,
    val fingerprintedFiles: Long = 0L,
    val fingerprintedBytes: Long = 0L,
    val contentReadComplete: Boolean = false,
    val candidateCount: Int = 0,
    val cleanupCandidateCount: Int = 0,
    val reorganizationCandidateCount: Int = 0,
    val reclaimableBytes: Long = 0L,
    val trashBytes: Long = 0L,
    val trashItems: List<StorageTrashUiItem> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class StorageMaintenanceViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val owner = application as LifeOsApplication
    private val mutableState = MutableStateFlow(StorageMaintenanceUiState())
    val state = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            refreshInternal()
        }
    }

    fun authorize() {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                owner.ownerPolicy.grant(PrivateOwnerPolicyBaseline.storageMaintenanceGrant())
                mutableState.update {
                    it.copy(
                        busy = false,
                        authorized = true,
                        message = "Speicherpflege ist durch die Owner-Policy freigegeben.",
                    )
                }
                refreshInternal()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    fun continueScan() {
        owner.refreshStorageIntelligence()
        mutableState.update {
            it.copy(
                message = "Hardware-adaptiver Dateiscan läuft weiter.",
                error = null,
            )
        }
    }

    fun applySafeCleanup() {
        executeMaintenance(includeReorganization = false)
    }

    fun applyOrganization() {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val result = owner.storageMaintenance.organizeNextBatch()
                mutableState.update {
                    it.copy(
                        busy = false,
                        message = buildString {
                            append("Neuordnung: ")
                            append(result.maintenance.reorganized)
                            append(" verschoben, ")
                            append(result.maintenance.blocked)
                            append(" blockiert, ")
                            append(result.maintenance.skipped)
                            append(" übersprungen · ")
                            append(result.scannedInventoryEntries)
                            append(" Indexeinträge geprüft")
                            if (result.complete) append(" · kompletter Bestand erreicht.")
                            else append(" · weiterer Batch verfügbar.")
                        },
                    )
                }
                owner.refreshStorageIntelligence()
                refreshInternal()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    fun restore(recordId: String) {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val result = owner.storageMaintenance.restore(recordId)
                mutableState.update {
                    it.copy(
                        busy = false,
                        message = when (result.status) {
                            StorageMaintenanceStatus.RESTORED -> "Datei wurde aus dem LIFEOS-Papierkorb wiederhergestellt."
                            StorageMaintenanceStatus.BLOCKED -> "Wiederherstellung wurde von der Owner-Policy blockiert."
                            else -> "Wiederherstellung wurde nicht ausgeführt: " + result.detail
                        },
                    )
                }
                owner.refreshStorageIntelligence()
                refreshInternal()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    fun purgeExpiredTrash() {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val result = owner.storageMaintenance.purgeExpired()
                mutableState.update {
                    it.copy(
                        busy = false,
                        message = "Papierkorb bereinigt: " + result.purged +
                            " endgültig gelöscht, " + result.blocked + " blockiert.",
                    )
                }
                refreshInternal()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    fun dismissMessage() {
        mutableState.update { it.copy(message = null, error = null) }
    }

    private fun executeMaintenance(
        includeReorganization: Boolean,
    ) {
        if (mutableState.value.busy) return
        val snapshot = owner.latestStorageIntelligence
        if (snapshot == null) {
            mutableState.update {
                it.copy(error = "Noch keine Speicheranalyse verfügbar. Starte zuerst den Scan.")
            }
            return
        }
        val selected = snapshot.cleanupCandidates.filter {
            it.kind != StorageCleanupKind.REORGANIZE
        }
        if (selected.isEmpty()) {
            mutableState.update {
                it.copy(message = "Für diesen Durchlauf gibt es keine passenden Aktionen.")
            }
            return
        }

        mutableState.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try {
                val result = owner.storageMaintenance.applyCandidates(
                    candidates = selected,
                    includeReorganization = includeReorganization,
                )
                mutableState.update {
                    it.copy(
                        busy = false,
                        message = buildString {
                            append("Speicherpflege abgeschlossen: ")
                            append(result.trashed)
                            append(" in Papierkorb, ")
                            append(result.reorganized)
                            append(" neu eingeordnet, ")
                            append(result.blocked)
                            append(" blockiert, ")
                            append(result.skipped)
                            append(" übersprungen.")
                        },
                    )
                }
                owner.refreshStorageIntelligence()
                refreshInternal()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    private suspend fun refreshInternal() {
        try {
            val expectedGrant = PrivateOwnerPolicyBaseline.storageMaintenanceGrant()
            val policy = owner.ownerPolicy.snapshot()
            val snapshot = owner.latestStorageIntelligence
            val trash = owner.storageMaintenance.trashSnapshot()
                .filter { it.state == StorageTrashState.TRASHED }
                .map { record ->
                    StorageTrashUiItem(
                        id = record.id,
                        originalPath = record.originalRelativePath,
                        sizeBytes = record.sizeBytes,
                        movedAt = record.preparedAt,
                        state = record.state.name,
                    )
                }
            mutableState.update { current ->
                current.copy(
                    broadFileAccess = owner.hasBroadFileAccess(),
                    authorized = policy.activeGrants.any { it.id == expectedGrant.id },
                    scanPhase = snapshot?.phase?.name ?: "not-started",
                    indexedFiles = snapshot?.indexedFiles ?: 0L,
                    indexedBytes = snapshot?.indexedBytes ?: 0L,
                    fingerprintedFiles = snapshot?.fullyFingerprintFiles ?: 0L,
                    fingerprintedBytes = snapshot?.fullyFingerprintBytes ?: 0L,
                    contentReadComplete = snapshot?.contentReadComplete ?: false,
                    candidateCount = snapshot?.cleanupCandidates?.size ?: 0,
                    cleanupCandidateCount = snapshot?.cleanupCandidates
                        ?.count { it.kind != StorageCleanupKind.REORGANIZE } ?: 0,
                    reorganizationCandidateCount = snapshot?.cleanupCandidates
                        ?.count { it.kind == StorageCleanupKind.REORGANIZE } ?: 0,
                    reclaimableBytes = snapshot?.reclaimableBytes ?: 0L,
                    trashBytes = trash.sumOf { it.sizeBytes },
                    trashItems = trash,
                    busy = false,
                    error = owner.storageIntelligenceFailure ?: current.error,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun fail(error: Exception) {
        mutableState.update {
            it.copy(
                busy = false,
                error = error.message ?: error::class.simpleName ?: "Speicherpflege ist fehlgeschlagen.",
            )
        }
    }
}
