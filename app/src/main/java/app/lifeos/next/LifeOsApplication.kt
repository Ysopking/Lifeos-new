package app.lifeos.next

import android.app.Application
import app.lifeos.next.kernel.LifeOsKernel
import app.lifeos.next.kernel.LifeOsKernelFactory

/** Process-level owner for the LIFEOS kernel instance. */
class LifeOsApplication : Application() {
    lateinit var kernel: LifeOsKernel
        private set

    override fun onCreate() {
        super.onCreate()
        kernel = LifeOsKernelFactory(this).create()
        kernel.start()
    }
}
