package app.lifeos.core.image.nativebackend

/** Native sync-file composition for joining independent MMSI producer branches. */
class NativeMmsiSyncBridge {
    fun merge(first: MmsiSyncFence?, second: MmsiSyncFence?): MmsiSyncFence? {
        if (first == null) return second
        if (second == null) return first
        ensureLoaded()
        val firstFd = first.take()
        val secondFd = second.take()
        val mergedFd = nativeMerge(firstFd, secondFd)
        check(mergedFd >= 0) { "Could not merge MMSI sync fences" }
        return MmsiSyncFence.fromNative(mergedFd)
    }

    private external fun nativeMerge(firstFenceFd: Int, secondFenceFd: Int): Int

    companion object {
        @Volatile private var loaded = false

        private fun ensureLoaded() {
            if (loaded) return
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("lifeos_mmsi_native")
                    loaded = true
                }
            }
        }
    }
}
