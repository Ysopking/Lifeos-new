package app.lifeos.next

import kotlin.test.Test
import kotlin.test.assertEquals

class AccessConvergenceCoordinatorTest {
    @Test
    fun `runtime access is presented before special access`() {
        val coordinator = AccessConvergenceCoordinator(
            runtimePlan = {
                RuntimePermissionRequestPlan(
                    profileIds = setOf(PermissionProfileId.INITIAL_DATA),
                    permissions = listOf("permission.contacts"),
                )
            },
            specialPlan = {
                SpecialAccessRequestPlan(
                    profileIds = setOf(PermissionProfileId.BROAD_FILES),
                    accesses = listOf(PermissionSpecialAccess.BROAD_FILE_ACCESS),
                )
            },
        )

        val runtime = coordinator.next()
        assertEquals(
            AccessConvergenceAction.RuntimePermissions(
                listOf("permission.contacts")
            ),
            runtime,
        )

        coordinator.markLaunched(runtime)
        assertEquals(
            AccessConvergenceAction.SpecialAccess(
                PermissionSpecialAccess.BROAD_FILE_ACCESS
            ),
            coordinator.next(),
        )
    }

    @Test
    fun `denied access is not repeated inside one coordinator session`() {
        val coordinator = AccessConvergenceCoordinator(
            runtimePlan = {
                RuntimePermissionRequestPlan(
                    profileIds = setOf(PermissionProfileId.INTERACTION),
                    permissions = listOf("permission.microphone"),
                )
            },
            specialPlan = {
                SpecialAccessRequestPlan(emptySet(), emptyList())
            },
        )

        val first = coordinator.next()
        coordinator.markLaunched(first)

        assertEquals(
            AccessConvergenceAction.Complete,
            coordinator.next(),
        )
    }

    @Test
    fun `new process coordinator retries access that is still missing`() {
        fun create() = AccessConvergenceCoordinator(
            runtimePlan = {
                RuntimePermissionRequestPlan(
                    profileIds = setOf(PermissionProfileId.INITIAL_DATA),
                    permissions = listOf("permission.calendar"),
                )
            },
            specialPlan = {
                SpecialAccessRequestPlan(emptySet(), emptyList())
            },
        )

        val firstProcess = create()
        val action = firstProcess.next()
        firstProcess.markLaunched(action)
        assertEquals(AccessConvergenceAction.Complete, firstProcess.next())

        assertEquals(
            AccessConvergenceAction.RuntimePermissions(
                listOf("permission.calendar")
            ),
            create().next(),
        )
    }

    @Test
    fun `special access is attempted once then convergence completes`() {
        val coordinator = AccessConvergenceCoordinator(
            runtimePlan = {
                RuntimePermissionRequestPlan(emptySet(), emptyList())
            },
            specialPlan = {
                SpecialAccessRequestPlan(
                    profileIds = setOf(PermissionProfileId.ASSISTANT_ACCESS),
                    accesses = listOf(PermissionSpecialAccess.NOTIFICATION_LISTENER),
                )
            },
        )

        val special = coordinator.next()
        coordinator.markLaunched(special)

        assertEquals(
            AccessConvergenceAction.Complete,
            coordinator.next(),
        )
    }
}
