package app.lifeos.next

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import app.lifeos.next.ui.LifeOsRoot

class ChatMainActivity : ComponentActivity() {
    private lateinit var model: LifeOsChatViewModel
    private lateinit var memoryModel: LifeOsMemoryViewModel
    private lateinit var assetReviewModel: OwnerAssetReviewViewModel
    private lateinit var goalsModel: LifeOsGoalsViewModel
    private lateinit var decisionTraceModel: LifeOsDecisionTraceViewModel

    private val initialDataPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        (application as? LifeOsApplication)?.refreshInitialDataBootstrap()
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
        setContent {
            LifeOsRoot(
                model = model,
                memoryModel = memoryModel,
                assetReviewModel = assetReviewModel,
                goalsModel = goalsModel,
                decisionTraceModel = decisionTraceModel,
                onRequestMicrophonePermission = {
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                },
            )
        }

        if (savedInstanceState == null && owner.shouldRequestInitialDataPermissions()) {
            val missing = owner.initialDataPermissionsToRequest()
            if (missing.isNotEmpty()) {
                owner.markInitialDataPermissionsRequested()
                initialDataPermissions.launch(missing.toTypedArray())
            }
        }
    }
}
