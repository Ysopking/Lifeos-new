package app.lifeos.next.kernel

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import app.lifeos.core.data.EncryptedPhotonStore
import app.lifeos.core.model.PhotonId
import app.lifeos.core.runtime.goal.LocalReminderRecord
import app.lifeos.core.runtime.goal.LocalScheduleGoalEngine
import app.lifeos.next.MainActivity
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

interface LocalReminderScheduler {
    fun canNotify(): Boolean
    fun schedule(reminderId: PhotonId, record: LocalReminderRecord)
}

/** Uses inexact while-idle alarms so LIFEOS does not require privileged exact-alarm access. */
class AndroidLocalReminderScheduler(
    private val context: Context,
    private val now: () -> Instant = Instant::now,
) : LocalReminderScheduler {
    private val appContext = context.applicationContext
    private val alarmManager: AlarmManager = appContext.getSystemService(AlarmManager::class.java)

    override fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun schedule(reminderId: PhotonId, record: LocalReminderRecord) {
        require(record.triggerAt.isAfter(now())) { "Reminder trigger must be in the future" }
        val intent = Intent(appContext, ReminderAlarmReceiver::class.java).apply {
            action = ACTION_REMINDER
            data = reminderUri(reminderId)
            putExtra(EXTRA_REMINDER_ID, reminderId.value)
            putExtra(EXTRA_MESSAGE, record.message)
        }
        val pending = PendingIntent.getBroadcast(
            appContext,
            reminderId.value.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            record.triggerAt.toEpochMilli(),
            pending,
        )
    }

    companion object {
        const val ACTION_REMINDER = "app.lifeos.next.action.REMINDER"
        const val EXTRA_REMINDER_ID = "reminderId"
        const val EXTRA_MESSAGE = "message"

        fun reminderUri(id: PhotonId): Uri = Uri.Builder()
            .scheme("lifeos")
            .authority("reminder")
            .appendPath(id.value)
            .build()
    }
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidLocalReminderScheduler.ACTION_REMINDER) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val reminderId = intent.getStringExtra(AndroidLocalReminderScheduler.EXTRA_REMINDER_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return
        val message = intent.getStringExtra(AndroidLocalReminderScheduler.EXTRA_MESSAGE)
            ?.takeIf { it.isNotBlank() }
            ?: return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "LIFEOS Erinnerungen",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Lokal geplante LIFEOS-Erinnerungen"
            }
        )
        val openApp = PendingIntent.getActivity(
            context,
            reminderId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.Builder().scheme("lifeos").authority("reminder").appendPath(reminderId).build()
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("LIFEOS Erinnerung")
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        manager.notify(reminderId.hashCode(), notification)
    }

    private companion object {
        const val CHANNEL_ID = "lifeos.reminders.v1"
    }
}

/** Restores future reminder alarms from encrypted reminder Photons after reboot or app replacement. */
class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESTORE_ACTIONS) return
        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val now = Instant.now()
                val scheduler = AndroidLocalReminderScheduler(appContext) { now }
                EncryptedPhotonStore(appContext).loadReport().photons
                    .asSequence()
                    .filter { it.mimeType == LocalScheduleGoalEngine.REMINDER_MIME }
                    .mapNotNull { photon ->
                        runCatching { photon.id to LocalReminderRecord.decode(photon) }.getOrNull()
                    }
                    .filter { (_, record) -> record.triggerAt.isAfter(now) }
                    .forEach { (id, record) -> scheduler.schedule(id, record) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val RESTORE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
