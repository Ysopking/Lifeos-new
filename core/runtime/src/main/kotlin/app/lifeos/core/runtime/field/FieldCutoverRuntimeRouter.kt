package app.lifeos.core.runtime.field

import app.lifeos.core.field.FieldDomainId
import app.lifeos.core.model.Photon

interface AuthoritativeFieldProcessor {
    val domainId: FieldDomainId
    fun accepts(photon: Photon): Boolean
    suspend fun process(photon: Photon): FieldShadowExecution
}

data class FieldAuthoritativeExecution(
    val cutoverState: FieldCutoverState,
    val universal: FieldShadowExecution,
) {
    init {
        require(cutoverState.mode == FieldCutoverMode.AUTHORITATIVE) {
            "Authoritative field execution requires authoritative cutover state"
        }
        require(universal.domainId == cutoverState.domainId) {
            "Authoritative field execution domain does not match cutover state"
        }
    }
}

/**
 * Single worker-side authority bridge. It never evaluates shadow evidence or mutates cutover state;
 * it only consumes the durable decision produced by FieldCutoverAuthority.
 */
class FieldCutoverRuntimeRouter(
    private val states: FieldCutoverStateRepository,
    processors: List<AuthoritativeFieldProcessor>,
) {
    private val processors = processors.toList()

    init {
        val domains = this.processors.map { it.domainId }
        require(domains.size == domains.distinct().size) {
            "Only one authoritative field processor may be registered per domain"
        }
        require(DefaultPhotonFieldRequestFactory.DOMAIN_ID !in domains) {
            "Generic runtime shadow domain can never be registered as authoritative"
        }
    }

    suspend fun processIfAuthoritative(photon: Photon): FieldAuthoritativeExecution? {
        val matches = processors.filter { it.accepts(photon) }
        require(matches.size <= 1) {
            "Multiple authoritative field processors accepted the same Photon"
        }
        val processor = matches.singleOrNull() ?: return null
        val state = states.load(processor.domainId) ?: return null
        if (state.mode != FieldCutoverMode.AUTHORITATIVE) return null

        requireNotNull(state.evidenceFingerprint) {
            "Authoritative field cutover is missing evidence identity"
        }
        val execution = processor.process(photon)
        require(execution.domainId == processor.domainId) {
            "Authoritative processor returned a different field domain"
        }
        require(execution.sourcePhotonId == photon.id) {
            "Authoritative processor returned a different source Photon"
        }
        require(execution.sourceRevision == photon.revision) {
            "Authoritative processor returned a different source revision"
        }
        require(execution.sourceFingerprint == runtimePhotonFingerprint(photon)) {
            "Authoritative processor returned a different source fingerprint"
        }
        return FieldAuthoritativeExecution(
            cutoverState = state,
            universal = execution,
        )
    }
}
