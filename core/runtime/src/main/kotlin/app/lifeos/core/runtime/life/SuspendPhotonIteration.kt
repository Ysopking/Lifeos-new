package app.lifeos.core.runtime.life

import app.lifeos.core.model.Photon
import kotlin.reflect.KSuspendFunction1

/**
 * Suspend-aware overload for bound suspend Photon function references.
 *
 * This is intentionally narrower than the standard Iterable.forEach: ordinary lambdas continue to
 * resolve to the stdlib overload, while a KSuspendFunction1 can be invoked sequentially from an
 * enclosing suspend operation without blocking or launching detached work.
 */
suspend fun List<Photon>.forEach(action: KSuspendFunction1<Photon, Unit>) {
    for (photon in this) action(photon)
}
