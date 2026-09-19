package app.lifeos.next.ui.chat

import app.lifeos.core.runtime.scale.RuntimeRetentionBudgets

data class ChatTimelinePage(
    val items: List<ChatTimelineItem>,
    val totalCount: Int,
) {
    val hasOlder: Boolean get() = items.size < totalCount
}

object ChatTimelinePager {
    const val DEFAULT_PAGE_SIZE = 60
    const val PAGE_STEP = 60
    const val MAX_VISIBLE_ITEMS = RuntimeRetentionBudgets.MAX_PRESENTATION_VISIBLE_ITEMS

    fun page(
        timeline: List<ChatTimelineItem>,
        visibleCount: Int,
    ): ChatTimelinePage {
        require(visibleCount > 0)
        val bounded = visibleCount.coerceAtMost(MAX_VISIBLE_ITEMS)
        return ChatTimelinePage(
            items = timeline.takeLast(bounded),
            totalCount = timeline.size,
        )
    }

    fun expand(visibleCount: Int): Int {
        require(visibleCount > 0)
        return Math.addExact(visibleCount, PAGE_STEP).coerceAtMost(MAX_VISIBLE_ITEMS)
    }
}
