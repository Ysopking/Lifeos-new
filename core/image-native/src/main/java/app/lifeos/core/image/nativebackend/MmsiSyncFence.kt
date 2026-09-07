package app.lifeos.core.image.nativebackend

import android.system.Os
import java.io.Closeable

/**
 * Owns a Linux sync-fence file descriptor.
 * `take()` transfers ownership to a native API that consumes the descriptor.
 */
class MmsiSyncFence internal constructor(
    private var descriptor: Int,
) : Closeable {
    val isValid: Boolean get() = descriptor >= 0

    internal fun take(): Int {
        val current = descriptor
        descriptor = -1
        return current
    }

    override fun close() {
        val current = descriptor
        descriptor = -1
        if (current >= 0) {
            runCatching { Os.close(current) }
        }
    }

    companion object {
        internal fun fromNative(descriptor: Int): MmsiSyncFence? =
            if (descriptor >= 0) MmsiSyncFence(descriptor) else null
    }
}
