package app.lifeos.next

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    private lateinit var storageMaintenanceModel: StorageMaintenanceViewModel
    private lateinit var personalConversationImportModel: PersonalConversationImportViewModel
    private lateinit var accessConvergence: AccessConvergenceCoordinator

    private var modelsReady by mutableStateOf(false)

    @Volatile
    private var permissionRefreshBaseline: InitialDataBootstrapSnapshot? = null

    @Volatile
    private var awaitingPostPermissionRefresh: Boolean = false

    @Volatile
    private var contextIngestionStarted: Boolean = false

    private val runtimePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (::model.isInitialized) {
            result[Manifest.permission.RECORD_AUDIO]?.let(model::onMicrophonePermissionResult)
        }

        val owner = application as? LifeOsApplication ?: return@registerForActivityResult
        continueAccessConvergence(owner)
    }

    private val broadFileAccess = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val owner = application as? LifeOsApplication ?: return@registerForActivityResult
        continueAccessConvergence(owner)
    }

    private val notificationAccess = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val owner = application as? LifeOsApplication ?: return@registerForActivityResult
        continueAccessConvergence(owner)
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
        enableEdgeToEdge()
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
                    storageMaintenanceModel = storageMaintenanceModel,
                    personalConversationImportModel = personalConversationImportModel,
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
        storageMaintenanceModel = ViewModelProvider(this)[StorageMaintenanceViewModel::class.java]
        personalConversationImportModel = ViewModelProvider(this)[PersonalConversationImportViewModel::class.java]
        accessConvergence = AccessConvergenceCoordinator(
            runtimePlan = owner::runtimePermissionRequestPlan,
            specialPlan = owner::specialAccessRequestPlan,
        )
        observeInitialCognitiveContext(owner)
        modelsReady = true
        continueAccessConvergence(owner)
    }

    private fun continueAccessConvergence(owner: LifeOsApplication) {
        when (val action = accessConvergence.next()) {
            is AccessConvergenceAction.RuntimePermissions -> {
                accessConvergence.markLaunched(action)
                InitialCognitiveContextRuntimeRegistry.markWaitingForPermissions()
                runtimePermissions.launch(action.permissions.toTypedArray())
            }

            is AccessConvergenceAction.SpecialAccess -> {
                accessConvergence.markLaunched(action)
                InitialCognitiveContextRuntimeRegistry.markWaitingForPermissions()
                when (action.access) {
                    PermissionSpecialAccess.BROAD_FILE_ACCESS -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            broadFileAccess.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:$packageName"),
                                )
                            )
                        } else {
                            continueAccessConvergence(owner)
                        }
                    }

                    PermissionSpecialAccess.NOTIFICATION_LISTENER ->
                        notificationAccess.launch(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
                }
            }

            AccessConvergenceAction.Complete ->
                beginContextIngestion(owner)
        }
    }

    private fun beginContextIngestion(owner: LifeOsApplication) {
        if (contextIngestionStarted) return
        contextIngestionStarted = true

        permissionRefreshBaseline = owner.latestInitialDataBootstrap
        awaitingPostPermissionRefresh = true
        InitialCognitiveContextRuntimeRegistry.markBuildingMemory()
        owner.refreshInitialDataBootstrap()
        owner.refreshStorageIntelligence()
        owner.refreshLiveSources()
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
}
