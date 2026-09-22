package app.lifeos.next

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationManagerCompat

internal class PrivatePermissionController(
    private val application: Application,
    private val initialDataSources: () -> AndroidInitialDataSourceCatalog,
    private val startupReady: () -> Boolean,
) {
    fun runtimePermissionRequestPlan(): RuntimePermissionRequestPlan {
        if (!startupReady()) {
            return RuntimePermissionRequestPlan(emptySet(), emptyList())
        }
        val profiles = listOf(initialDataProfile(), interactionProfile())
        val missing = profiles
            .flatMap { evaluate(it).missingRuntimePermissions }
            .distinct()
            .sorted()
        return RuntimePermissionRequestPlan(
            profileIds = profiles.mapTo(linkedSetOf()) { it.id },
            permissions = missing,
        )
    }

    /**
     * Productive calendar/contact writes are requested only on demand.
     *
     * They intentionally stay out of the startup runtimePermissionRequestPlan so observation-only
     * startup never expands into productive PIM authority merely because B407 is present.
     */
    fun pimWritePermissionRequestPlan(): RuntimePermissionRequestPlan {
        if (!startupReady()) {
            return RuntimePermissionRequestPlan(emptySet(), emptyList())
        }
        val profile = pimWriteProfile()
        val missing = evaluate(profile).missingRuntimePermissions
            .distinct()
            .sorted()
        return RuntimePermissionRequestPlan(
            profileIds = setOf(profile.id),
            permissions = missing,
        )
    }

    fun specialAccessRequestPlan(): SpecialAccessRequestPlan {
        if (!startupReady()) {
            return SpecialAccessRequestPlan(emptySet(), emptyList())
        }
        val profiles = listOf(broadFilesProfile(), assistantAccessProfile())
        val evaluations = profiles.map(::evaluate)
        val missing = evaluations
            .filter { it.state == PermissionProfileState.OWNER_ACTION_REQUIRED }
            .flatMap { it.missingSpecialAccess }
            .distinct()
            .sortedBy { it.ordinal }
        return SpecialAccessRequestPlan(
            profileIds = profiles.mapTo(linkedSetOf()) { it.id },
            accesses = missing,
        )
    }

    fun deviceAccessSnapshot(): DeviceAccessSnapshot {
        val evaluations = declaredPermissionProfiles().map(::evaluate)
        return DeviceAccessSnapshot(
            runtimePermissionsMissing = evaluations
                .flatMap { it.missingRuntimePermissions }
                .distinct()
                .sorted(),
            specialAccessMissing = evaluations
                .filter { it.state == PermissionProfileState.OWNER_ACTION_REQUIRED }
                .flatMap { it.missingSpecialAccess }
                .distinct()
                .sortedBy { it.ordinal },
            profileStates = evaluations.associate { it.profile.id to it.state },
        )
    }

    fun hasBroadFileAccess(): Boolean =
        evaluate(broadFilesProfile()).state == PermissionProfileState.GRANTED

    fun declaredPermissionProfiles(): List<PermissionProfile> = listOf(
        initialDataProfile(),
        interactionProfile(),
        broadFilesProfile(),
        assistantAccessProfile(),
        PrivatePermissionProfiles.infrastructure(
            internetPermission = Manifest.permission.INTERNET,
            receiveBootCompletedPermission = Manifest.permission.RECEIVE_BOOT_COMPLETED,
        ),
    )

    private fun initialDataProfile(): PermissionProfile =
        PrivatePermissionProfiles.initialData(
            runtimePermissions = initialDataSources().runtimePermissionsToRequest().toSet(),
            manifestPermissions = initialDataSources().requiredRuntimePermissions().toSet(),
        )

    private fun interactionProfile(): PermissionProfile =
        PrivatePermissionProfiles.interaction(
            recordAudioPermission = Manifest.permission.RECORD_AUDIO,
            postNotificationsPermission =
                Manifest.permission.POST_NOTIFICATIONS.takeIf {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                },
        )

    private fun broadFilesProfile(): PermissionProfile =
        PrivatePermissionProfiles.broadFiles(
            manageExternalStoragePermission = Manifest.permission.MANAGE_EXTERNAL_STORAGE,
        )

    private fun pimWriteProfile(): PermissionProfile =
        PrivatePermissionProfiles.pimWrite(
            writeCalendarPermission = Manifest.permission.WRITE_CALENDAR,
            writeContactsPermission = Manifest.permission.WRITE_CONTACTS,
        )

    private fun assistantAccessProfile(): PermissionProfile =
        PrivatePermissionProfiles.assistantAccess()

    private fun evaluate(profile: PermissionProfile): PermissionProfileEvaluation =
        PermissionProfileEvaluator(
            runtimePermissionGranted = { permission ->
                application.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            },
            specialAccessSupported = { special ->
                when (special) {
                    PermissionSpecialAccess.BROAD_FILE_ACCESS ->
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    PermissionSpecialAccess.NOTIFICATION_LISTENER -> true
                }
            },
            specialAccessGranted = { special ->
                when (special) {
                    PermissionSpecialAccess.BROAD_FILE_ACCESS ->
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                            Environment.isExternalStorageManager()
                    PermissionSpecialAccess.NOTIFICATION_LISTENER ->
                        NotificationManagerCompat
                            .getEnabledListenerPackages(application)
                            .contains(application.packageName)
                }
            },
        ).evaluate(profile)
}
