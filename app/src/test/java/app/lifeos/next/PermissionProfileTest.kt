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

    @Test
    fun `notification listener is a first class special access profile`() {
        val profile = PrivatePermissionProfiles.assistantAccess()
        val evaluator = PermissionProfileEvaluator(
            runtimePermissionGranted = { true },
            specialAccessSupported = { true },
            specialAccessGranted = { false },
        )

        val result = evaluator.evaluate(profile)

        assertEquals(PermissionProfileId.ASSISTANT_ACCESS, profile.id)
        assertEquals(PermissionProfileState.OWNER_ACTION_REQUIRED, result.state)
        assertEquals(
            setOf(PermissionSpecialAccess.NOTIFICATION_LISTENER),
            result.missingSpecialAccess,
        )
    }

    @Test
    fun `special access request plan is deterministic`() {
        val plan = SpecialAccessRequestPlan(
            profileIds = setOf(
                PermissionProfileId.BROAD_FILES,
                PermissionProfileId.ASSISTANT_ACCESS,
            ),
            accesses = listOf(
                PermissionSpecialAccess.BROAD_FILE_ACCESS,
                PermissionSpecialAccess.NOTIFICATION_LISTENER,
            ),
        )

        assertTrue(plan.required)
        assertFalse(SpecialAccessRequestPlan(emptySet(), emptyList()).required)
    }

    @Test
    fun `productive PIM writes stay separate from initial data reads`() {
        val profile = PrivatePermissionProfiles.pimWrite(
            writeCalendarPermission = "android.permission.WRITE_CALENDAR",
            writeContactsPermission = "android.permission.WRITE_CONTACTS",
        )

        assertEquals(PermissionProfileId.PIM_WRITE, profile.id)
        assertEquals(
            setOf(
                "android.permission.WRITE_CALENDAR",
                "android.permission.WRITE_CONTACTS",
            ),
            profile.runtimePermissions,
        )
        assertFalse("android.permission.READ_CALENDAR" in profile.runtimePermissions)
        assertFalse("android.permission.READ_CONTACTS" in profile.runtimePermissions)
    }

    @Test
    fun `device access convergence ignores unsupported special access as missing`() {
        val snapshot = DeviceAccessSnapshot(
            runtimePermissionsMissing = emptyList(),
            specialAccessMissing = emptyList(),
            profileStates = mapOf(
                PermissionProfileId.BROAD_FILES to PermissionProfileState.UNSUPPORTED,
            ),
        )

        assertTrue(snapshot.converged)
    }
}
