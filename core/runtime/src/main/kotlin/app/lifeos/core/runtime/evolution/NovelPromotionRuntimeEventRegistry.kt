package app.lifeos.core.runtime.evolution

fun interface NovelPromotionRuntimeEventSink {
    suspend fun onActivated(result: BoundedNovelPromotionResult)
}

/** Process hook for observing successful guarded novel-capability activation without owning policy. */
object NovelPromotionRuntimeEventRegistry {
    @Volatile
    private var sink: NovelPromotionRuntimeEventSink? = null

    fun install(value: NovelPromotionRuntimeEventSink) {
        sink = value
    }

    suspend fun publishActivated(result: BoundedNovelPromotionResult) {
        sink?.onActivated(result)
    }
}
