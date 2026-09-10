package app.lifeos.core.runtime.field

import app.lifeos.core.field.FieldConvergenceRequest

/** Pre-convergence boundary for adding immutable runtime context to a request. */
fun interface FieldRequestEnricher {
    suspend fun enrich(request: FieldConvergenceRequest): FieldConvergenceRequest

    companion object {
        val NONE: FieldRequestEnricher = FieldRequestEnricher { request -> request }
    }
}
