package app.lifeos.next

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Keeps instrumentation-only UI contract tests independent from the full productive LIFEOS boot.
 * The F11 workflow separately cold-starts ChatMainActivity with the real LifeOsApplication.
 */
class LifeOsTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application {
        return super.newApplication(cl, Application::class.java.name, context)
    }
}
