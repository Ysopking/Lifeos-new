package app.lifeos.core.runtime.resource

/**
 * Allows reservation collections to be folded directly into held usage. A reservation contributes
 * its full reserved amount until it is committed or released.
 */
internal operator fun ResourceBudgetUsage.plus(
    reservation: ResourceBudgetReservation,
): ResourceBudgetUsage = this + reservation.reserved
