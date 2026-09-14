package app.lifeos.next

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.lifeos.core.runtime.life.InitialDataBootstrapSnapshot
import app.lifeos.next.kernel.InitialCognitiveContextPhase
import app.lifeos.next.kernel.InitialCognitiveContextRuntimeRegistry
import app.lifeos.next.ui.LifeOsRoot
import app.lifeos.next.ui.LifeOsStartupScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChatMainActivity : ComponentActivity() {
    private lateinit var model: LifeOsChatViewModel
    private lateinit var memoryModel: LifeOsMemoryViewModel
    private lateinit var assetReviewModel: OwnerAssetReviewViewModel
    private lateinit var goalsModel: LifeOsGoalsViewModel
    private lateinit var decisionTraceModel: LifeOsDecisionTraceViewModel
    private lateinit var toolCenterModel: LifeOsToolCenterViewModel

    private var modelsReady by mutableStateOf(false)

    @Volatile
    private var permissionRefreshBaseline: InitialDataBootstrapSnapshot? = null

    @Volatile
    private var awaitingPostPermissionRefresh: Boolean = false

    @Volatile
    private var permissionSequenceStarted: Boolean = false

    private val runtimePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val owner = application as? LifeOsApplication ?: return@registerForActivityResult
        owner.markAllRuntimePermissionsRequested()
        beginPostPermissionRefresh(owner)
        if (!requestBroadFileAccessIfNeeded(owner)) {
            requestNotificationAccessIfNeeded()
        }
    }

    private val broadFileAccess = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val owner = application as? LifeOsApplication ?: return@registerForActivityResult
        beginPostPermissionRefresh(owner)
        requestNotificationAccessIfNeeded()
    }

    private val notificationAccess = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (InitialCognitiveContextRuntimeRegistry.current().phase == InitialCognitiveContextPhase.WAITING_FOR_PERMISSIONS) {
            InitialCognitiveContextRuntimeRegistry.markBuildingMemory()
        }
    }

    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (::model.isInitialized) {
            model.onMicrophonePermissionResult(granted)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owner = application as LifeOsApplication

        setContent {
            val startup by owner.startupState.collectAsStateWithLifecycle()
            if (startup.ready && modelsReady) {
                LifeOsRoot(
                    model = model,
                    memoryModel = memoryModel,
                    assetReviewModel = assetReviewModel,
                    goalsModel = goalsModel,
                    decisionTraceModel = decisionTraceModel,
                    toolCenterModel = toolCenterModel,
                    onRequestMicrophonePermission = {
                        microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                )
            } else {
                LifeOsStartupScreen(startup)
            }
        }

        lifecycleScope.launch {
            owner.startupState.collect { startup ->
                if (startup.ready && !modelsReady) {
                    initializeRuntimeUi(owner)
                }
            }
        }
    }

    private fun initializeRuntimeUi(owner: LifeOsApplication) {
        if (modelsReady) return
        model = ViewModelProvider(this)[LifeOsChatViewModel::class.java]
        memoryModel = ViewModelProvider(this)[LifeOsMemoryViewModel::class.java]
        assetReviewModel = ViewModelProvider(this)[OwnerAssetReviewViewModel::class.java]
        goalsModel = ViewModelProvider(this)[LifeOsGoalsViewModel::class.java]
        decisionTraceModel = ViewModelProvider(this)[LifeOsDecisionTraceViewModel::class.java]
        toolCenterModel = ViewModelProvider(this)[LifeOsToolCenterViewModel::class.java]
        observeInitialCognitiveContext(owner)
        modelsReady = true
        startPermissionSequence(owner)
    }

    private fun startPermissionSequence(owner: LifeOsApplication) {
        if (permissionSequenceStarted) return
        permissionSequenceStarted = true

        val missingRuntime = owner.allRuntimePermissionsToRequest()
        if (missingRuntime.isNotEmpty() && owner.shouldRequestAllRuntimePermissions()) {
            InitialCognitiveContextRuntimeRegistry.markWaitingForPermissions()
            lifecycleScope.launch {
                awaitInitialBootstrap(owner)
                runtimePermissions.launch(missingRuntime.toTypedArray())
            }
            return
        }

        if (requestBroadFileAccessIfNeeded(owner)) return
        if (requestNotificationAccessIfNeeded()) return
        if (InitialCognitiveContextRuntimeRegistry.current().phase == InitialCognitiveContextPhase.PREPARING) {
            InitialCognitiveContextRuntimeRegistry.markBuildingMemory()
        }
    }

    private suspend fun awaitInitialBootstrap(owner: LifeOsApplication) {
        while (
            isActive &&
            owner.latestInitialDataBootstrap == null &&
            owner.initialDataBootstrapFailure == null
        ) {
            delay(50)
        }
    }

    private fun requestBroadFileAccessIfNeeded(owner: LifeOsApplication): Boolean {
        if (!owner.shouldRequestBroadFileAccess()) return false
        owner.markBroadFileAccessRequested()
        InitialCognitiveContextRuntimeRegistry.markWaitingForPermissions()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            broadFileAccess.launch(intent)
            return true
        }
        return false
    }

    private fun requestNotificationAccessIfNeeded(): Boolean {
        if (NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) {
            return false
        }
        val preferences = getSharedPreferences(ASSISTANT_ACCESS_PREFS, MODE_PRIVATE)
        if (preferences.getBoolean(NOTIFICATION_ACCESS_REQUESTED, false)) {
            return false
        }
        preferences.edit().putBoolean(NOTIFICATION_ACCESS_REQUESTED, true).apply()
        InitialCognitiveContextRuntimeRegistry.markWaitingForPermissions()
        notificationAccess.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        return true
    }

    private fun beginPostPermissionRefresh(owner: LifeOsApplication) {
        permissionRefreshBaseline = owner.latestInitialDataBootstrap
        awaitingPostPermissionRefresh = true
        InitialCognitiveContextRuntimeRegistry.markBuildingMemory()
        owner.refreshInitialDataBootstrap()
    }

    private fun observeInitialCognitiveContext(owner: LifeOsApplication) {
        lifecycleScope.launch {
            while (isActive) {
                val current = InitialCognitiveContextRuntimeRegistry.current()
                val failure = owner.initialDataBootstrapFailure
                if (failure != null && current.phase != InitialCognitiveContextPhase.WAITING_FOR_PERMISSIONS) {
                    InitialCognitiveContextRuntimeRegistry.fail(failure)
                } else {
                    val bootstrap = owner.latestInitialDataBootstrap
                    val memory = owner.lifeMemoryRuntime.current()
                    val postPermissionRefreshComplete = !awaitingPostPermissionRefresh ||
                        (bootstrap != null && bootstrap !== permissionRefreshBaseline)
                    if (
                        current.phase != InitialCognitiveContextPhase.WAITING_FOR_PERMISSIONS &&
                        postPermissionRefreshComplete &&
                        bootstrap != null &&
                        memory != null
                    ) {
                        InitialCognitiveContextRuntimeRegistry.publish(bootstrap, memory)
                        awaitingPostPermissionRefresh = false
                        permissionRefreshBaseline = null
                    }
                }
                delay(100)
            }
        }
    }

    private companion object {
        const val ASSISTANT_ACCESS_PREFS = "lifeos-assistant-access"
        const val NOTIFICATION_ACCESS_REQUESTED = "notification-access-requested"
    }
}
