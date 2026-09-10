package app.lifeos.next

import android.app.Application
import app.lifeos.core.data.capability.EncryptedGeneratedToolStateRepository
import app.lifeos.core.runtime.capability.GeneratedToolRuntimeStatusReader
import app.lifeos.next.kernel.LifeOsKernel
import app.lifeos.next.kernel.LifeOsKernelFactory

/** Process-level owner for the LIFEOS kernel instance and read-only private diagnostics. */
class LifeOsApplication : Application() {
    lateinit var kernel: LifeOsKernel
        private set

    lateinit var generatedToolStatusReader: GeneratedToolRuntimeStatusReader
        private set

    override fun onCreate() {
        super.onCreate()
        generatedToolStatusReader = GeneratedToolRuntimeStatusReader(
            EncryptedGeneratedToolStateRepository(this),
        )
        kernel = LifeOsKernelFactory(this).create()
        kernel.start()
    }
}
