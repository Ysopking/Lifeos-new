package app.lifeos.next.kernel

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import app.lifeos.core.data.policy.EncryptedOwnerPolicyRepository
import app.lifeos.core.data.resource.EncryptedResourceBudgetRepository
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeProcessRegistry
import app.lifeos.core.runtime.policy.OwnerPolicyLedger
import app.lifeos.core.runtime.resource.ResourceBudgetCoordinator

/**
 * Private, non-exported startup hook. It registers before Application.onCreate and waits for the
 * productive generated-tool registries to be constructed synchronously by LifeOsKernelFactory.
 * The ready callback installs V10 before kernel.start(), without polling or a parallel registry.
 */
class HotSwapStartupProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = requireNotNull(context).applicationContext
        GeneratedToolRuntimeProcessRegistry.whenReady { _, _ ->
            PrivateHotSwapRuntimeRegistry.install(
                PrivateHotSwapRuntime.create(
                    context = appContext,
                    ownerPolicy = OwnerPolicyLedger(EncryptedOwnerPolicyRepository(appContext)),
                    budgets = ResourceBudgetCoordinator(EncryptedResourceBudgetRepository(appContext)),
                )
            )
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
