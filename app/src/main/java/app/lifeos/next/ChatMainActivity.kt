package app.lifeos.next

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import app.lifeos.core.runtime.life.InitialDataBootstrapSnapshot
import app.lifeos.next.kernel.InitialCognitiveContextPhase
import app.lifeos.next.kernel.InitialCognitiveContextRuntimeRegistry
import app.lifeos.next.ui.LifeOsRoot
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChatMainActivity : ComponentActivity() {
    private lateinit var model: LifeOsChatViewModel
    private lateinit var memoryModel: LifeOsMemoryViewModel
    private lateinit var assetReviewModel: OwnerAssetReviewViewModel
    private lateinit var goalsModel: LifeOsGoalsViewModel
    private lateinit var decisionTraceModel: LifeOsDecisionTraceViewModel
    private lateinit var toolCenterModel: LifeOsToolCenterViewModel

    @Volatile
    private var permissionRefreshBaseline: InitialDataBootstrapSnapshot? = null

    @Volatile
    private var awaitingPostPermissionRefresh: Boolean = false

    private val initialDataPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val owner = application as? LifeOsApplication ?: return@registerForActivityResult
        // A permission decision changes the source universe. Never expose the pre-dialog memory
        // snapshot while the authorized sources are being read and the life-memory graph rebuilt.
        permissionRefreshBaseline = owner.latestInitialDataBootstrap
        awaitingPostPermissionRefresh = true
        InitialCognitiveContextRuntimeRegistry.markBuildingMemory()
        owner.refreshInitialDataBootstrap()
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
        model = ViewModelProvider(this)[LifeOsChatViewModel::class.java]
        memoryModel = ViewModelProvider(this)[LifeOsMemoryViewModel::class.java]
        assetReviewModel = ViewModelProvider(this)[OwnerAssetReviewViewModel::class.java]
        goalsModel = ViewModelProvider(this)[LifeOsGoalsViewModel::class.java]
        decisionTraceModel = ViewModelProvider(this)[LifeOsDecisionTraceViewModel::class.java]
        toolCenterModel = ViewModelProvider(this)[LifeOsToolCenterViewModel::class.java]

        observeInitialCognitiveContext(owner)

        setContent {
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
        }

        if (savedInstanceState == null && owner.shouldRequestInitialDataPermissions()) {
            val missing = owner.initialDataPermissionsToRequest()
            if (missing.isNotEmpty()) {
                InitialCognitiveContextRuntimeRegistry.markWaitingForPermissions()
                lifecycleScope.launch {
                    // Let the first crash-safe pass seal its pre-permission source state. This gives
                    // us an exact baseline that the post-permission refresh must supersede, even when
                    // the user denies every requested permission and the resulting content is equal.
                    while (
                        isActive &&
                        owner.latestInitialDataBootstrap == null &&
                        owner.initialDataBootstrapFailure == null
                    ) {
                        delay(50)
                    }
                    owner.markInitialDataPermissionsRequested()
                    initialDataPermissions.launch(missing.toTypedArray())
                }
            } else {
                InitialCognitiveContextRuntimeRegistry.markBuildingMemory()
            }
        } else if (InitialCognitiveContextRuntimeRegistry.current().phase == InitialCognitiveContextPhase.PREPARING) {
            InitialCognitiveContextRuntimeRegistry.markBuildingMemory()
        }
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
