package app.lifeos.core.data.security

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File

internal object UnifiedVaultIo {
    private const val CONTAINER_OVERHEAD_BYTES = 1024

    fun maxContainerBytes(maxPlaintextBytes: Int): Int {
        require(maxPlaintextBytes > 0)
        return Math.addExact(maxPlaintextBytes, CONTAINER_OVERHEAD_BYTES)
    }

    fun atomicWrite(target: File, bytes: ByteArray) =
        atomicWrite(AtomicFile(target), bytes)

    fun atomicWrite(target: AtomicFile, bytes: ByteArray) {
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    fun readAtomic(target: File, maxPlaintextBytes: Int): ByteArray =
        readAtomic(AtomicFile(target), maxPlaintextBytes)

    fun readAtomic(target: AtomicFile, maxPlaintextBytes: Int): ByteArray {
        val maxContainerBytes = maxContainerBytes(maxPlaintextBytes)
        return target.openRead().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxContainerBytes) {
                    "Encrypted vault file too large"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
}
