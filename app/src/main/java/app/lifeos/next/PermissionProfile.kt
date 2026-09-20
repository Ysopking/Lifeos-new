package app.lifeos.next

internal enum class PermissionProfileId {
    INITIAL_DATA,
    INTERACTION,
    BROAD_FILES,
    INFRASTRUCTURE,
}

internal enum class PermissionSpecialAccess {
    BROAD_FILE_ACCESS,
}

internal data class PermissionProfile(
    val id: PermissionProfileId,
    val runtimePermissions: Set<String> = emptySet(),
    val specialAccess: Set<PermissionSpecialAccess> = emptySet(),
    val manifestPermissions: Set<String> = emptySet(),
    val rationaleTags: Set<String> = emptySet(),
) {
    init {
        require(runtimePermissions.none { it.isBlank() })
        require(manifestPermissions.none { it.isBlank() })
        require(rationaleTags.none { it.isBlank() })
    }
}

internal enum class PermissionProfileState {
    GRANTED,
    MISSING,
    OWNER_ACTION_REQUIRED,
    UNSUPPORTED,
}

internal data class PermissionProfileEvaluation(
    val profile: PermissionProfile,
    val state: PermissionProfileState,
    val missingRuntimePermissions: List<String> = emptyList(),
    val missingSpecialAccess: Set<PermissionSpecialAccess> = emptySet(),
)

internal data class RuntimePermissionRequestPlan(
    val profileIds: Set<PermissionProfileId>,
    val permissions: List<String>,
) {
    init {
        require(permissions == permissions.distinct().sorted()) {
            "Runtime permission request plan must be distinct and sorted"
        }
    }

    val required: Boolean
        get() = permissions.isNotEmpty()
}

internal class PermissionProfileEvaluator(
    private val runtimePermissionGranted: (String) -> Boolean,
    private val specialAccessSupported: (PermissionSpecialAccess) -> Boolean,
    private val specialAccessGranted: (PermissionSpecialAccess) -> Boolean,
) {
    fun evaluate(profile: PermissionProfile): PermissionProfileEvaluation {
        val unsupported = profile.specialAccess.filterNot(specialAccessSupported).toSet()
        if (unsupported.isNotEmpty()) {
            return PermissionProfileEvaluation(
                profile = profile,
                state = PermissionProfileState.UNSUPPORTED,
                missingSpecialAccess = unsupported,
            )
        }
        val missingRuntime = profile.runtimePermissions
            .filterNot(runtimePermissionGranted)
            .sorted()
        if (missingRuntime.isNotEmpty()) {
            return PermissionProfileEvaluation(
                profile = profile,
                state = PermissionProfileState.MISSING,
                missingRuntimePermissions = missingRuntime,
            )
        }
        val missingSpecial = profile.specialAccess.filterNot(specialAccessGranted).toSet()
        if (missingSpecial.isNotEmpty()) {
            return PermissionProfileEvaluation(
                profile = profile,
                state = PermissionProfileState.OWNER_ACTION_REQUIRED,
                missingSpecialAccess = missingSpecial,
            )
        }
        return PermissionProfileEvaluation(profile, PermissionProfileState.GRANTED)
    }
}

internal object PrivatePermissionProfiles {
    fun initialData(runtimePermissions: Set<String>): PermissionProfile =
        PermissionProfile(
            id = PermissionProfileId.INITIAL_DATA,
            runtimePermissions = runtimePermissions,
            manifestPermissions = runtimePermissions,
            rationaleTags = setOf("initial-data", "owner-authorized-device-read"),
        )

    fun interaction(
        recordAudioPermission: String,
        postNotificationsPermission: String?,
    ): PermissionProfile = PermissionProfile(
        id = PermissionProfileId.INTERACTION,
        runtimePermissions = buildSet {
            add(recordAudioPermission)
            postNotificationsPermission?.let(::add)
        },
        manifestPermissions = buildSet {
            add(recordAudioPermission)
            postNotificationsPermission?.let(::add)
        },
        rationaleTags = setOf("voice", "notifications"),
    )

    fun broadFiles(manageExternalStoragePermission: String): PermissionProfile =
        PermissionProfile(
            id = PermissionProfileId.BROAD_FILES,
            specialAccess = setOf(PermissionSpecialAccess.BROAD_FILE_ACCESS),
            manifestPermissions = setOf(manageExternalStoragePermission),
            rationaleTags = setOf("owner-confirmed-broad-files"),
        )

    fun infrastructure(
        internetPermission: String,
        receiveBootCompletedPermission: String,
    ): PermissionProfile = PermissionProfile(
        id = PermissionProfileId.INFRASTRUCTURE,
        manifestPermissions = setOf(internetPermission, receiveBootCompletedPermission),
        rationaleTags = setOf("network-policy-gated", "boot-restore"),
    )
}
