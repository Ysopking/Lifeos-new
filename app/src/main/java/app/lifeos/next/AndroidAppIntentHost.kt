package app.lifeos.next

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import app.lifeos.core.runtime.android.AppIntentAction
import app.lifeos.core.runtime.android.AppIntentExtra
import app.lifeos.core.runtime.android.AppIntentHost
import app.lifeos.core.runtime.android.AppIntentLaunchReceipt
import app.lifeos.core.runtime.android.AppIntentRequest
import app.lifeos.core.runtime.android.AppIntentTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android host adapter for B408.
 *
 * The core runtime owns syntax bounds, exact target binding and Owner Policy. This adapter only
 * resolves exported/enabled activities and performs the final explicit-component handoff. It never
 * accepts raw caller flags and never grants URI permissions.
 */
internal class AndroidAppIntentHost(
    private val context: Context,
) : AppIntentHost {
    override suspend fun resolve(
        request: AppIntentRequest,
    ): List<AppIntentTarget> = withContext(Dispatchers.IO) {
        val intent = buildIntent(request, target = null, forLaunch = false)
        val targets = context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .asSequence()
            .mapNotNull { resolveInfo ->
                val activity = resolveInfo.activityInfo ?: return@mapNotNull null
                if (!activity.enabled || !activity.exported) return@mapNotNull null
                val packageName = activity.packageName?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val className = activity.name?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                AppIntentTarget(packageName, className)
            }
            .filter { target ->
                request.packageName == null || target.packageName == request.packageName
            }
            .filter { target ->
                request.exactTarget == null || target == request.exactTarget
            }
            .distinct()
            .sortedWith(compareBy({ it.packageName }, { it.className }))
            .toList()

        require(targets.size <= MAX_RESOLVED_TARGETS) {
            "android-intent-resolution-exceeded-bound"
        }
        targets
    }

    override suspend fun launch(
        request: AppIntentRequest,
        target: AppIntentTarget,
    ): AppIntentLaunchReceipt = withContext(Dispatchers.Main.immediate) {
        val current = resolve(request)
        require(target in current) {
            "android-intent-target-no-longer-resolvable"
        }

        val intent = buildIntent(
            request = request,
            target = target,
            forLaunch = true,
        )
        check(intent.component == ComponentName(target.packageName, target.className))
        check(
            intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0 &&
                intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0
        ) {
            "B408 intent must not grant URI permissions"
        }

        context.startActivity(intent)
        AppIntentLaunchReceipt.create(request, target)
    }

    private fun buildIntent(
        request: AppIntentRequest,
        target: AppIntentTarget?,
        forLaunch: Boolean,
    ): Intent {
        val intent = Intent(request.action.androidAction)

        when (request.action) {
            AppIntentAction.MAIN -> intent.addCategory(Intent.CATEGORY_LAUNCHER)
            AppIntentAction.VIEW -> {
                if (request.deepLink != null) {
                    intent.addCategory(Intent.CATEGORY_BROWSABLE)
                }
            }
            AppIntentAction.DIAL,
            AppIntentAction.SEND_TO,
            -> Unit
        }

        request.deepLink?.let { link ->
            intent.data = Uri.parse(link.canonicalUri)
        }
        request.packageName?.let(intent::setPackage)
        request.extras.forEach { extra ->
            when (extra) {
                is AppIntentExtra.Text -> intent.putExtra(extra.key, extra.value)
                is AppIntentExtra.Flag -> intent.putExtra(extra.key, extra.value)
                is AppIntentExtra.Number -> intent.putExtra(extra.key, extra.value)
            }
        }

        target?.let {
            intent.component = ComponentName(it.packageName, it.className)
        }
        if (forLaunch) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return intent
    }

    private companion object {
        const val MAX_RESOLVED_TARGETS = 32
    }
}
