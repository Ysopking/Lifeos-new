package app.lifeos.next

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/**
 * Lightweight runner used only by the F11 accessibility contract.
 *
 * F11 separately proves that ChatMainActivity cold-starts with the real LifeOsApplication before
 * this runner is invoked. The semantic contract itself does not need the productive kernel boot,
 * so replacing the target Application here prevents no-KVM emulator startup starvation from
 * turning a pure accessibility assertion into a kernel-startup ANR.
 */
class LifeOsAccessibilityContractRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, Application::class.java.name, context)
}
