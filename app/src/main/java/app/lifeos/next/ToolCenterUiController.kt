package app.lifeos.next

import app.lifeos.core.runtime.capability.GeneratedToolGenesisResult
import app.lifeos.core.runtime.capability.GeneratedToolRequestExecutionResult
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatusReader
import app.lifeos.core.runtime.capability.GeneratedToolState
import app.lifeos.core.runtime.evolution.PrivateNovelCapabilityActivationResult
import app.lifeos.next.kernel.LifeOsKernel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ToolCenterUiController(
    private val kernel: LifeOsKernel,
    private val generatedToolStatusReader: GeneratedToolRuntimeStatusReader,
    private val state: MutableStateFlow<LifeOsState>,
    private val scope: CoroutineScope,
) {
    fun refreshStatus() {
        if (state.value.generatedToolStatusLoading) return
        scope.launch { loadStatus() }
    }

    fun requestCapabilityGaps() {
        val current = state.value
        val gap = current.lastCapabilityGaps.firstOrNull()
        if (
            current.loading ||
            current.loadFailed ||
            current.capabilityRequestSaving ||
            current.capabilityActivationSaving ||
            gap == null
        ) return

        state.update {
            it.copy(
                capabilityRequestSaving = true,
                capabilityRequestStatus = null,
            )
        }
        scope.launch {
            try {
                val result = kernel.generateExplicitlyApprovedTool(gap)
                val status = when (val execution = result.execution) {
                    is GeneratedToolRequestExecutionResult.Blocked ->
                        "Tool-Erzeugung wurde vor Genesis blockiert: ${execution.reason}"

                    is GeneratedToolRequestExecutionResult.Completed ->
                        when (val genesis = execution.genesis) {
                            is GeneratedToolGenesisResult.OwnerReviewRequired ->
                                "${genesis.record.manifest.toolId} wurde lokal erzeugt, gebaut, getestet und verifiziert. Die exakte Code-Revision wartet jetzt in Assets auf deine Freigabe; erst danach darf das Tool in TRIAL."

                            is GeneratedToolGenesisResult.TrialReady ->
                                "${genesis.record.manifest.toolId} wurde lokal erzeugt, gebaut, getestet und verifiziert. Das Tool ist jetzt isoliert in TRIAL und noch nicht aktiv."

                            is GeneratedToolGenesisResult.Rejected ->
                                "Der lokale ToolWorkshop hat ${genesis.record.manifest.toolId} sicher abgelehnt: ${genesis.reasons.joinToString("; ")}"
                        }
                }
                state.update { it.copy(capabilityRequestStatus = status) }
                loadStatus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update {
                    it.copy(
                        capabilityRequestStatus =
                            "Tool-Erzeugung konnte nicht sicher abgeschlossen werden: " +
                                (error.message ?: error::class.simpleName ?: "unbekannter Fehler")
                    )
                }
            } finally {
                state.update { it.copy(capabilityRequestSaving = false) }
            }
        }
    }

    fun reviewAndActivateFirstTrialTool() {
        val current = state.value
        val trial = current.generatedToolStatus?.tools
            ?.firstOrNull { it.state == GeneratedToolState.TRIAL }
        if (
            current.loading ||
            current.loadFailed ||
            current.capabilityRequestSaving ||
            current.capabilityActivationSaving ||
            trial == null
        ) return

        state.update {
            it.copy(
                capabilityActivationSaving = true,
                capabilityActivationStatus = null,
            )
        }
        scope.launch {
            try {
                val result = kernel.reviewAndActivateGeneratedTool(trial.toolId)
                val status = when (result) {
                    is PrivateNovelCapabilityActivationResult.Activated ->
                        "${result.promotion.activeRecord.manifest.toolId} hat fünf getrennte lokale Novel-Canaries, Readiness, den dauerhaften Promotion-Seal und die getrennte Review-/Owner-Prüfung bestanden. Das Tool ist jetzt ACTIVE mit LOW Trust und wird nach Neustart nur mit exakt passender Evidence wiederhergestellt."

                    is PrivateNovelCapabilityActivationResult.AlreadyActive ->
                        "${result.record.manifest.toolId} ist bereits ACTIVE. Es wurden keine weiteren Canary-Trials ausgeführt."

                    is PrivateNovelCapabilityActivationResult.Blocked ->
                        "Aktivierung von ${result.toolId} wurde sicher blockiert: ${result.reasons.joinToString("; ")}"
                }
                state.update { it.copy(capabilityActivationStatus = status) }
                loadStatus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update {
                    it.copy(
                        capabilityActivationStatus =
                            "Tool-Aktivierung konnte nicht sicher abgeschlossen werden: " +
                                (error.message ?: error::class.simpleName ?: "unbekannter Fehler")
                    )
                }
            } finally {
                state.update { it.copy(capabilityActivationSaving = false) }
            }
        }
    }

    fun dismissRequestStatus() {
        state.update { it.copy(capabilityRequestStatus = null) }
    }

    fun dismissActivationStatus() {
        state.update { it.copy(capabilityActivationStatus = null) }
    }

    suspend fun loadStatus() {
        state.update {
            it.copy(
                generatedToolStatusLoading = true,
                generatedToolStatusError = null,
            )
        }
        try {
            val status = withContext(Dispatchers.IO) {
                generatedToolStatusReader.snapshot()
            }
            state.update {
                it.copy(
                    generatedToolStatus = status,
                    generatedToolStatusError = null,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            state.update {
                it.copy(
                    generatedToolStatusError =
                        "Generated-Tool-Status konnte nicht gelesen werden."
                )
            }
        } finally {
            state.update { it.copy(generatedToolStatusLoading = false) }
        }
    }
}
