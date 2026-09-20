package app.lifeos.next

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionProfileTest {
    @Test
    fun `runtime evaluator reports only missing permissions in stable order`() {
        val profile = PermissionProfile(
            id = PermissionProfileId.INTERACTION,
            runtimePermissions = setOf("permission.z", "permission.a", "permission.m"),
        )
        val evaluator = PermissionProfileEvaluator(
            runtimePermissionGranted = { it == "permission.m" },
            specialAccessSupported = { true },
            specialAccessGranted = { true },
        )

        val result = evaluator.evaluate(profile)

        assertEquals(PermissionProfileState.MISSING, result.state)
        assertEquals(listOf("permission.a", "permission.z"), result.missingRuntimePermissions)
    }

    @Test
    fun `special access requires owner action and never becomes a runtime grant`() {
        val profile = PermissionProfile(
            id = PermissionProfileId.BROAD_FILES,
            specialAccess = setOf(PermissionSpecialAccess.BROAD_FILE_ACCESS),
        )
        val evaluator = PermissionProfileEvaluator(
            runtimePermissionGranted = { true },
            specialAccessSupported = { true },
            specialAccessGranted = { false },
        )

        val result = evaluator.evaluate(profile)

        assertEquals(PermissionProfileState.OWNER_ACTION_REQUIRED, result.state)
        assertTrue(PermissionSpecialAccess.BROAD_FILE_ACCESS in result.missingSpecialAccess)
        assertTrue(result.missingRuntimePermissions.isEmpty())
    }

    @Test
    fun `request plan is profile identified and sorted`() {
        val plan = RuntimePermissionRequestPlan(
            profileIds = setOf(PermissionProfileId.INITIAL_DATA, PermissionProfileId.INTERACTION),
            permissions = listOf("permission.a", "permission.z"),
        )

        assertTrue(plan.required)
        assertEquals(2, plan.profileIds.size)
        assertFalse(RuntimePermissionRequestPlan(emptySet(), emptyList()).required)
    }
}
