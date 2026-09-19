package app.lifeos.core.runtime.evolution

fun interface NovelPromotionRuntimeEventSink {
    suspend fun onActivated(result: BoundedNovelPromotionResult)
}

/** Non-owning process hook for observing successful guarded novel-capability activation. */
object NovelPromotionRuntimeEventRegistry {
    private val slot =
        app.lifeos.core.runtime.process.NonOwningRuntimeSlot<NovelPromotionRuntimeEventSink>(
            "Novel promotion event sink"
        )

    fun install(value: NovelPromotionRuntimeEventSink) {
        slot.install(value)
    }

    suspend fun publishActivated(result: BoundedNovelPromotionResult) {
        slot.currentOrNull()?.onActivated(result)
    }

    internal fun clearForTests() {
        slot.clear()
    }
}
