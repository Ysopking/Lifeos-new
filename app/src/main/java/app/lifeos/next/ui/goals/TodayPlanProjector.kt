package app.lifeos.next.ui.goals

import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.goal.GoalPlanId
import app.lifeos.core.runtime.goal.GoalStepId
import app.lifeos.core.runtime.goal.GoalStepState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

enum class TodayPlanTiming {
    OVERDUE,
    NOW,
    TODAY,
    UNSCHEDULED,
}

enum class TodayContextKind {
    MESSAGE,
    NOTIFICATION,
}

data class TodayContextItem(
    val photonId: PhotonId,
    val kind: TodayContextKind,
    val sourceApp: String,
    val title: String,
    val body: String,
    val conversation: String,
    val createdAt: Instant,
    val urgency: Int,
    val relatedStepIds: Set<GoalStepId>,
)

data class TodayPlanItem(
    val planId: GoalPlanId,
    val planTitle: String,
    val stepId: GoalStepId,
    val objective: String,
    val state: GoalStepState,
    val presentationKind: GoalStepPresentationKind,
    val timing: TodayPlanTiming,
    val deadline: Instant?,
    val priority: Int,
    val blockedByDependencies: Boolean,
)

data class TodayPlanUiModel(
    val date: LocalDate,
    val items: List<TodayPlanItem>,
    val liveContext: List<TodayContextItem> = emptyList(),
) {
    val overdueCount: Int
        get() = items.count { it.timing == TodayPlanTiming.OVERDUE }

    val actionableCount: Int
        get() = items.count {
            !it.blockedByDependencies &&
                (it.state == GoalStepState.READY || it.state == GoalStepState.RUNNING)
        }
}

/**
 * Pure, read-only "Heute" projection over the existing V7 goal workspace and canonical live context.
 *
 * This is deliberately not a second planner: it never persists state, invents deadlines, creates
 * owner actions or changes V7 transitions. Live context may only reorder work inside the same timing
 * class, so a notification can never outrank a real overdue/current/today boundary.
 */
object TodayPlanProjector {
    fun project(
        workspace: GoalWorkspaceUiModel,
        at: Instant,
        zoneId: ZoneId,
        photons: List<Photon> = emptyList(),
    ): TodayPlanUiModel {
        val today = at.atZone(zoneId).toLocalDate()
        val baseItems = workspace.plans
            .asSequence()
            .filterNot { it.status == GoalPlanUiStatus.COMPLETED || it.status == GoalPlanUiStatus.CANCELLED }
            .flatMap { plan ->
                plan.steps.asSequence().mapNotNull { step ->
                    projectStep(plan, step, at, today, zoneId)
                }
            }
            .toList()
        val context = projectLiveContext(
            photons = photons,
            items = baseItems,
            at = at,
            today = today,
            zoneId = zoneId,
        )
        val affinityByStep = baseItems.associate { item ->
            item.stepId to context.maxOfOrNull { live -> affinity(item, live) }.orZero()
        }
        val items = baseItems.sortedWith(
            compareBy<TodayPlanItem> { it.timing.sortRank() }
                .thenByDescending { affinityByStep[it.stepId].orZero() }
                .thenByDescending { it.priority }
                .thenBy { it.deadline ?: Instant.MAX }
                .thenBy { it.planId.value }
                .thenBy { it.stepId.value },
        )
        return TodayPlanUiModel(
            date = today,
            items = items,
            liveContext = context,
        )
    }

    private fun projectStep(
        plan: GoalPlanUiModel,
        step: GoalStepUiModel,
        at: Instant,
        today: LocalDate,
        zoneId: ZoneId,
    ): TodayPlanItem? {
        if (step.state == GoalStepState.COMPLETED || step.state == GoalStepState.CANCELLED) return null

        val deadlineDate = step.deadline?.atZone(zoneId)?.toLocalDate()
        val overdue = step.deadline?.isBefore(at) == true
        val dueToday = deadlineDate == today
        val current = step.id == plan.currentStepId || step.state == GoalStepState.RUNNING
        val readyOwnerAction = step.presentationKind == GoalStepPresentationKind.OWNER_ACTION &&
            (step.state == GoalStepState.READY || step.id == plan.nextStepId)

        if (!overdue && !dueToday && !current && !readyOwnerAction) return null

        val timing = when {
            overdue -> TodayPlanTiming.OVERDUE
            current -> TodayPlanTiming.NOW
            dueToday -> TodayPlanTiming.TODAY
            else -> TodayPlanTiming.UNSCHEDULED
        }
        return TodayPlanItem(
            planId = plan.id,
            planTitle = plan.title,
            stepId = step.id,
            objective = step.objective,
            state = step.state,
            presentationKind = step.presentationKind,
            timing = timing,
            deadline = step.deadline,
            priority = step.priority,
            blockedByDependencies = step.unmetDependencyIds.isNotEmpty(),
        )
    }

    private fun projectLiveContext(
        photons: List<Photon>,
        items: List<TodayPlanItem>,
        at: Instant,
        today: LocalDate,
        zoneId: ZoneId,
    ): List<TodayContextItem> {
        val dayStart = today.atStartOfDay(zoneId).toInstant()
        val dayEnd = today.plusDays(1).atStartOfDay(zoneId).toInstant()
        return photons
            .asSequence()
            .filter { "live-context" in it.tags }
            .filter { it.provenance.createdAt >= dayStart && it.provenance.createdAt < dayEnd }
            .filter { it.provenance.createdAt <= at }
            .mapNotNull { photon -> liveContextItem(photon, items) }
            .sortedWith(
                compareByDescending<TodayContextItem> { it.urgency }
                    .thenByDescending { it.createdAt }
                    .thenBy { it.photonId.value },
            )
            .take(MAX_CONTEXT_ITEMS)
            .toList()
    }

    private fun liveContextItem(
        photon: Photon,
        items: List<TodayPlanItem>,
    ): TodayContextItem? {
        val fields = photon.content.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null
                else line.substring(0, separator) to line.substring(separator + 1)
            }
            .toMap()
        val title = fields["title"].orEmpty().trim().take(MAX_DISPLAY_TEXT)
        val body = fields["text"].orEmpty().trim().take(MAX_DISPLAY_TEXT)
        val conversation = fields["conversation"].orEmpty().trim().take(MAX_DISPLAY_TEXT)
        if (title.isBlank() && body.isBlank() && conversation.isBlank()) return null

        val sourceApp = fields["package"].orEmpty().trim().ifBlank { photon.provenance.actor }
        val kind = if ("message" in photon.tags) TodayContextKind.MESSAGE else TodayContextKind.NOTIFICATION
        val contextTokens = tokens("$title $conversation $body")
        val related = items
            .asSequence()
            .filter { item ->
                tokens("${item.planTitle} ${item.objective}").any(contextTokens::contains)
            }
            .map { it.stepId }
            .toSet()
        return TodayContextItem(
            photonId = photon.id,
            kind = kind,
            sourceApp = sourceApp,
            title = title,
            body = body,
            conversation = conversation,
            createdAt = photon.provenance.createdAt,
            urgency = urgencyOf("$title $conversation $body"),
            relatedStepIds = related,
        )
    }

    private fun affinity(item: TodayPlanItem, context: TodayContextItem): Int {
        val itemTokens = tokens("${item.planTitle} ${item.objective}")
        if (itemTokens.isEmpty()) return 0
        val contextTokens = tokens("${context.title} ${context.conversation} ${context.body}")
        val overlap = itemTokens.count(contextTokens::contains)
        return overlap * TOKEN_MATCH_WEIGHT +
            if (item.stepId in context.relatedStepIds) context.urgency else 0
    }

    private fun urgencyOf(text: String): Int {
        val normalized = text.lowercase(Locale.ROOT)
        return URGENCY_MARKERS.count { marker -> marker in normalized }.coerceAtMost(MAX_URGENCY)
    }

    private fun tokens(text: String): Set<String> = TOKEN_PATTERN
        .findAll(text.lowercase(Locale.ROOT))
        .map { it.value }
        .filter { it.length >= MIN_TOKEN_LENGTH && it !in STOP_WORDS }
        .toSet()

    private fun TodayPlanTiming.sortRank(): Int = when (this) {
        TodayPlanTiming.OVERDUE -> 0
        TodayPlanTiming.NOW -> 1
        TodayPlanTiming.TODAY -> 2
        TodayPlanTiming.UNSCHEDULED -> 3
    }

    private fun Int?.orZero(): Int = this ?: 0

    private const val MAX_CONTEXT_ITEMS = 5
    private const val MAX_DISPLAY_TEXT = 240
    private const val MIN_TOKEN_LENGTH = 4
    private const val TOKEN_MATCH_WEIGHT = 10
    private const val MAX_URGENCY = 4
    private val TOKEN_PATTERN = Regex("[\\p{L}\\p{N}]+")
    private val URGENCY_MARKERS = listOf(
        "heute",
        "jetzt",
        "dringend",
        "deadline",
        "termin",
        "erinner",
        "bis morgen",
        "kannst du",
        "bitte",
    )
    private val STOP_WORDS = setOf(
        "aber", "auch", "dass", "dein", "deine", "eine", "einer", "eines", "heute",
        "jetzt", "kannst", "morgen", "nicht", "oder", "sind", "über", "wird", "wurde",
    )
}
