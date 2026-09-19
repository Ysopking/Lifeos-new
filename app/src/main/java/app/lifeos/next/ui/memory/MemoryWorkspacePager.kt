package app.lifeos.next.ui.memory

data class MemoryWorkspacePageState(
    val nowLimit: Int = DEFAULT_NOW_PAGE,
    val topicAtomLimitPerGroup: Int = DEFAULT_TOPIC_ATOMS_PER_GROUP,
    val crystalLimit: Int = DEFAULT_CRYSTAL_PAGE,
    val episodeLimit: Int = DEFAULT_EPISODE_PAGE,
) {
    init {
        require(nowLimit > 0)
        require(topicAtomLimitPerGroup > 0)
        require(crystalLimit > 0)
        require(episodeLimit > 0)
    }

    fun expandNow(): MemoryWorkspacePageState =
        copy(nowLimit = boundedAdd(nowLimit, NOW_PAGE_STEP))

    fun expandTopics(): MemoryWorkspacePageState = copy(
        topicAtomLimitPerGroup = boundedAdd(
            topicAtomLimitPerGroup,
            TOPIC_ATOM_PAGE_STEP,
        ),
        crystalLimit = boundedAdd(crystalLimit, CRYSTAL_PAGE_STEP),
    )

    fun expandTimeline(): MemoryWorkspacePageState =
        copy(episodeLimit = boundedAdd(episodeLimit, EPISODE_PAGE_STEP))

    private fun boundedAdd(value: Int, increment: Int): Int =
        Math.addExact(value, increment).coerceAtMost(MAX_VISIBLE_ITEMS)

    companion object {
        const val DEFAULT_NOW_PAGE = 40
        const val NOW_PAGE_STEP = 40
        const val DEFAULT_TOPIC_ATOMS_PER_GROUP = 24
        const val TOPIC_ATOM_PAGE_STEP = 24
        const val DEFAULT_CRYSTAL_PAGE = 24
        const val CRYSTAL_PAGE_STEP = 24
        const val DEFAULT_EPISODE_PAGE = 40
        const val EPISODE_PAGE_STEP = 40
        const val MAX_VISIBLE_ITEMS = 20_000
    }
}

/**
 * Pure UI paging. The unpaged semantic projection remains authoritative and source resolution
 * continues to use the complete search index; paging only bounds objects published to Compose.
 */
object MemoryWorkspacePager {
    fun page(
        full: MemoryWorkspaceUiModel,
        state: MemoryWorkspacePageState,
    ): MemoryWorkspaceUiModel {
        val now = full.now.take(state.nowLimit)
        val topicGroups = full.topicGroups.map { group ->
            group.copy(atoms = group.atoms.take(state.topicAtomLimitPerGroup))
        }
        val crystals = full.crystals.take(state.crystalLimit)
        val episodes = full.episodes.take(state.episodeLimit)

        return full.copy(
            now = now,
            topicGroups = topicGroups,
            crystals = crystals,
            episodes = episodes,
            paging = MemoryWorkspacePagingUi(
                visibleNowCount = now.size,
                totalNowCount = full.now.size,
                visibleTopicAtomCount = topicGroups.sumOf { it.atoms.size },
                totalTopicAtomCount = full.topicGroups.sumOf { it.atoms.size },
                visibleCrystalCount = crystals.size,
                totalCrystalCount = full.crystals.size,
                visibleEpisodeCount = episodes.size,
                totalEpisodeCount = full.episodes.size,
            ),
        )
    }
}
