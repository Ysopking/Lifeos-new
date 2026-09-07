package app.lifeos.core.runtime.health

import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** Uses exception types, never exception messages or private payloads. */
class FailureClassifier {
    fun classify(error: Exception): FailureKind = when (error) {
        is TimeoutCancellationException, is java.util.concurrent.TimeoutException -> FailureKind.TIMEOUT
        is CancellationException -> throw error
        is InvariantViolation -> FailureKind.INVARIANT
        is GeneralSecurityException, is SecurityException -> FailureKind.SECURITY
        is IOException -> FailureKind.STORAGE
        else -> FailureKind.EXECUTION
    }
}

enum class FailureKind { TIMEOUT, INVARIANT, SECURITY, STORAGE, EXECUTION }
class InvariantViolation : IllegalStateException("Core invariant violated")
class InvariantChecker {
    fun requireValid(valid: Boolean) { if (!valid) throw InvariantViolation() }
}
