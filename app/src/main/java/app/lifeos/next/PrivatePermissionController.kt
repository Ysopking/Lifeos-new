package app.lifeos.next

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment

internal class PrivatePermissionController(
    private val application: Application,
    private val initialDataSources: () -> AndroidInitialDataSourceCatalog,
    private val startupReady: () -> Boolean,
) {
    fun initialDataPermissionsToRequest(): List<String> =
        evaluate(initialDataProfile()).missingRuntimePermissions

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
            profileIds = profiles.map { it.id }.toSet(),
            permissions = missing,
        )
    }

    fun allRuntimePermissionsToRequest(): List<String> =
        runtimePermissionRequestPlan().permissions

    fun hasBroadFileAccess(): Boolean =
        broadFileEvaluation().state == PermissionProfileState.GRANTED

    fun shouldRequestBroadFileAccess(): Boolean {
        if (broadFileEvaluation().state != PermissionProfileState.OWNER_ACTION_REQUIRED) {
            return false
        }
        return !preferences().getBoolean(BROAD_FILE_ACCESS_REQUESTED, false)
    }

    fun markBroadFileAccessRequested() {
        preferences()
            .edit()
            .putBoolean(BROAD_FILE_ACCESS_REQUESTED, true)
            .apply()
    }

    fun shouldRequestInitialDataPermissions(): Boolean {
        val evaluation = evaluate(initialDataProfile())
        if (evaluation.missingRuntimePermissions.isEmpty()) return false
        val schema = initialDataSources().permissionSchemaFingerprint()
        return preferences().getString(INITIAL_DATA_PERMISSION_SCHEMA, null) != schema
    }

    fun shouldRequestAllRuntimePermissions(): Boolean {
        if (!runtimePermissionRequestPlan().required) return false
        return preferences().getString(ALL_RUNTIME_PERMISSION_SCHEMA, null) !=
            allRuntimePermissionSchema()
    }

    fun markInitialDataPermissionsRequested() {
        preferences()
            .edit()
            .putString(
                INITIAL_DATA_PERMISSION_SCHEMA,
                initialDataSources().permissionSchemaFingerprint(),
            )
            .apply()
    }

    fun markAllRuntimePermissionsRequested() {
        preferences()
            .edit()
            .putString(
                ALL_RUNTIME_PERMISSION_SCHEMA,
                allRuntimePermissionSchema(),
            )
            .apply()
    }

    fun declaredPermissionProfiles(): List<PermissionProfile> = listOf(
        initialDataProfile(),
        interactionProfile(),
        broadFilesProfile(),
        PrivatePermissionProfiles.infrastructure(
            internetPermission = Manifest.permission.INTERNET,
            receiveBootCompletedPermission = Manifest.permission.RECEIVE_BOOT_COMPLETED,
        ),
    )

    private fun initialDataProfile(): PermissionProfile =
        PrivatePermissionProfiles.initialData(
            runtimePermissions = initialDataSources().requiredRuntimePermissions().toSet(),
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

    private fun broadFileEvaluation(): PermissionProfileEvaluation =
        evaluate(broadFilesProfile())

    private fun evaluate(profile: PermissionProfile): PermissionProfileEvaluation =
        PermissionProfileEvaluator(
            runtimePermissionGranted = { permission ->
                application.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            },
            specialAccessSupported = { special ->
                when (special) {
                    PermissionSpecialAccess.BROAD_FILE_ACCESS ->
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                }
            },
            specialAccessGranted = { special ->
                when (special) {
                    PermissionSpecialAccess.BROAD_FILE_ACCESS ->
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                            Environment.isExternalStorageManager()
                }
            },
        ).evaluate(profile)

    private fun allRuntimePermissionSchema(): String = buildString {
        append(initialDataSources().permissionSchemaFingerprint())
        append("|record-audio")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            append("|post-notifications")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            append("|read-external-storage")
        }
    }

    private fun preferences() =
        application.getSharedPreferences(
            INITIAL_DATA_PREFS,
            Context.MODE_PRIVATE,
        )

    private companion object {
        const val INITIAL_DATA_PREFS = "lifeos-initial-data-bootstrap"
        const val INITIAL_DATA_PERMISSION_SCHEMA = "permission-schema"
        const val ALL_RUNTIME_PERMISSION_SCHEMA = "all-runtime-permission-schema"
        const val BROAD_FILE_ACCESS_REQUESTED = "broad-file-access-requested"
    }
}
