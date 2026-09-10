package app.lifeos.core.runtime.goal

import app.lifeos.core.language.EntityType
import app.lifeos.core.language.GoalFrame
import app.lifeos.core.language.IntentType
import app.lifeos.core.language.SemanticEntity
import app.lifeos.core.model.Photon
import app.lifeos.core.model.PhotonId
import app.lifeos.core.model.PhotonPhase
import app.lifeos.core.model.PhotonRelation
import app.lifeos.core.model.Provenance
import app.lifeos.core.model.RelationType
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Base64
import kotlin.math.min

data class LocalReminderRecord(
    val triggerAt: Instant,
    val zoneId: String,
    val message: String,
) {
    init {
        require(zoneId.isNotBlank())
        require(message.isNotBlank())
    }

    fun encode(): String = buildString {
        appendLine("reminder/v1")
        append("triggerAt=").appendLine(triggerAt.toString())
        append("zoneId=").appendLine(zoneId)
        append("messageBase64=").append(
            Base64.getUrlEncoder().withoutPadding().encodeToString(message.toByteArray(Charsets.UTF_8))
        )
    }

    companion object {
        fun decode(photon: Photon): LocalReminderRecord {
            require(photon.mimeType == LocalScheduleGoalEngine.REMINDER_MIME) { "Photon is not a LIFEOS reminder" }
            val lines = photon.content.lineSequence().toList()
            require(lines.firstOrNull() == "reminder/v1") { "Unsupported reminder format" }
            val fields = lines.drop(1).associate { line ->
                val split = line.indexOf('=')
                require(split > 0) { "Malformed reminder field" }
                line.substring(0, split) to line.substring(split + 1)
            }
            require(fields.keys == setOf("triggerAt", "zoneId", "messageBase64")) { "Reminder fields are incomplete" }
            val message = String(
                Base64.getUrlDecoder().decode(fields.getValue("messageBase64")),
                Charsets.UTF_8,
            )
            return LocalReminderRecord(
                triggerAt = Instant.parse(fields.getValue("triggerAt")),
                zoneId = fields.getValue("zoneId"),
                message = message,
            )
        }
    }
}

sealed interface LocalScheduleGoalResult {
    data class Scheduled(
        val photon: Photon,
        val record: LocalReminderRecord,
    ) : LocalScheduleGoalResult

    data class Blocked(
        val reason: String,
    ) : LocalScheduleGoalResult {
        init { require(reason.isNotBlank()) }
    }

    data class Unsupported(val intent: IntentType) : LocalScheduleGoalResult
}

/**
 * Deterministic local reminder planning. This class resolves only the time contract and emits a
 * persistent reminder Photon; the Android app owns the actual OS alarm/notification side effect.
 * Missing time information is never silently invented.
 */
class LocalScheduleGoalEngine {
    fun supports(intent: IntentType): Boolean = intent == IntentType.SCHEDULE

    fun execute(
        goal: GoalFrame,
        sourcePhoton: Photon,
        goalPhotonId: PhotonId,
        zoneId: ZoneId,
        createdAt: Instant = Instant.now(),
    ): LocalScheduleGoalResult {
        if (!supports(goal.intent)) return LocalScheduleGoalResult.Unsupported(goal.intent)

        val timeEntity = goal.entities.firstOrNull { it.type == EntityType.TIME }
            ?: return LocalScheduleGoalResult.Blocked("reminder-time-missing")
        val localTime = parseTime(timeEntity)
            ?: return LocalScheduleGoalResult.Blocked("reminder-time-invalid:${timeEntity.rawText}")

        val reference = sourcePhoton.provenance.createdAt.atZone(zoneId)
        val dateEntity = goal.entities.firstOrNull { it.type == EntityType.DATE }
        val localDate = when {
            dateEntity != null -> parseDate(dateEntity, reference.toLocalDate())
                ?: return LocalScheduleGoalResult.Blocked("reminder-date-invalid:${dateEntity.rawText}")
            localTime.isAfter(reference.toLocalTime()) -> reference.toLocalDate()
            else -> reference.toLocalDate().plusDays(1)
        }
        val triggerAt = try {
            localDate.atTime(localTime).atZone(zoneId).toInstant()
        } catch (_: DateTimeException) {
            return LocalScheduleGoalResult.Blocked("reminder-local-time-invalid")
        }
        if (!triggerAt.isAfter(sourcePhoton.provenance.createdAt)) {
            return LocalScheduleGoalResult.Blocked("reminder-time-not-in-future")
        }

        val confidence = min(sourcePhoton.confidence, goal.confidence)
        val record = LocalReminderRecord(
            triggerAt = triggerAt,
            zoneId = zoneId.id,
            message = sourcePhoton.content.trim(),
        )
        val reminder = Photon(
            content = record.encode(),
            mimeType = REMINDER_MIME,
            phase = PhotonPhase.ACTIVE,
            semanticMass = maxOf(1.0, sourcePhoton.semanticMass + 0.20),
            energy = maxOf(1.0, sourcePhoton.energy),
            confidence = confidence,
            provenance = Provenance(
                source = "local-reminder-scheduler",
                actor = "LocalScheduleGoalEngine",
                createdAt = createdAt,
                parentIds = setOf(sourcePhoton.id, goalPhotonId),
            ),
            relations = setOf(
                PhotonRelation(sourcePhoton.id, RelationType.DERIVED_FROM, confidence),
                PhotonRelation(goalPhotonId, RelationType.REFERENCES, goal.confidence),
            ),
            tags = setOf("reminder", "scheduled", "intent:schedule"),
        )
        return LocalScheduleGoalResult.Scheduled(photon = reminder, record = record)
    }

    private fun parseDate(entity: SemanticEntity, referenceDate: LocalDate): LocalDate? {
        return when (entity.normalizedValue) {
            "relative:today" -> referenceDate
            "relative:tomorrow" -> referenceDate.plusDays(1)
            "relative:yesterday" -> referenceDate.minusDays(1)
            else -> parseNumericDate(entity.normalizedValue, referenceDate)
        }
    }

    private fun parseNumericDate(value: String, referenceDate: LocalDate): LocalDate? {
        val parts = value.split('.', '/', '-')
        if (parts.size !in 2..3) return null
        val day = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val explicitYear = parts.getOrNull(2)?.toIntOrNull()
        val initialYear = explicitYear ?: referenceDate.year
        val candidate = runCatching { LocalDate.of(initialYear, month, day) }.getOrNull() ?: return null
        return if (explicitYear == null && candidate.isBefore(referenceDate)) {
            runCatching { candidate.plusYears(1) }.getOrNull()
        } else {
            candidate
        }
    }

    private fun parseTime(entity: SemanticEntity): LocalTime? {
        val value = entity.normalizedValue.lowercase()
        return when {
            ':' in value -> {
                val parts = value.split(':')
                if (parts.size != 2) null else runCatching {
                    LocalTime.of(parts[0].toInt(), parts[1].toInt())
                }.getOrNull()
            }
            value.endsWith("uhr") -> value.removeSuffix("uhr").toIntOrNull()?.let { hour ->
                runCatching { LocalTime.of(hour, 0) }.getOrNull()
            }
            value.endsWith("am") || value.endsWith("pm") -> {
                val pm = value.endsWith("pm")
                val rawHour = value.dropLast(2).toIntOrNull() ?: return null
                if (rawHour !in 1..12) return null
                val hour = when {
                    rawHour == 12 && !pm -> 0
                    rawHour == 12 && pm -> 12
                    pm -> rawHour + 12
                    else -> rawHour
                }
                LocalTime.of(hour, 0)
            }
            else -> null
        }
    }

    companion object {
        const val REMINDER_MIME = "application/vnd.lifeos.reminder+text"
    }
}
