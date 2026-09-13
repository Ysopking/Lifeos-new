package app.lifeos.next

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import app.lifeos.next.ui.LifeOsRoot

class ChatMainActivity : ComponentActivity() {
    private val initialDataPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        (application as? LifeOsApplication)?.refreshInitialDataBootstrap()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owner = application as LifeOsApplication
        val model = ViewModelProvider(this)[LifeOsChatViewModel::class.java]
        setContent { LifeOsRoot(model) }

        if (savedInstanceState == null && owner.shouldRequestInitialDataPermissions()) {
            val missing = owner.initialDataPermissionsToRequest()
            if (missing.isNotEmpty()) {
                owner.markInitialDataPermissionsRequested()
                initialDataPermissions.launch(missing.toTypedArray())
            }
        }
    }
}
