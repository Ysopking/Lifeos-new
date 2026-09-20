package app.lifeos.core.image.nativebackend

import android.os.ParcelFileDescriptor
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
        check(descriptor >= 0) { "Sync fence ownership has already been transferred or closed" }
        val current = descriptor
        descriptor = -1
        return current
    }

    override fun close() {
        val current = descriptor
        descriptor = -1
        if (current >= 0) {
            runCatching { ParcelFileDescriptor.adoptFd(current).close() }
        }
    }

    companion object {
        internal fun fromNative(descriptor: Int): MmsiSyncFence? =
            if (descriptor >= 0) MmsiSyncFence(descriptor) else null
    }
}
