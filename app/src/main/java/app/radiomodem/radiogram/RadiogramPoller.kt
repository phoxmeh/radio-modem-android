package app.radiomodem.radiogram

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.radiomodem.settings.AppSettings
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * Looks for the new weekly post in the background. The schedule is posted on Thursday evening (US Eastern), after the
 * last transmission of the old week, so checking starts Thursday 21:00 UTC and repeats hourly until the post is found
 * (every six hours after a day without one). Between posts there is nothing to check for, so the next check waits for
 * the following Thursday. Only starts once the Radiogram screen has been opened.
 */
object RadiogramPoller {
    private const val NAME = "radiogram_poll"
    const val FIRST_CHECK_UTC = 21   // hour on the Thursday the week ends

    /** Milliseconds until the next check. Pure, for testing. */
    fun delayMs(week: RadiogramWeek, nowMs: Long): Long {
        val from = week.end.atTime(LocalTime.of(FIRST_CHECK_UTC, 0)).toInstant(ZoneOffset.UTC).toEpochMilli()
        val overdue = nowMs - from
        val wait = when {
            week.postUrl.isEmpty() -> 15 * 60_000L                 // nothing read yet: try soon
            overdue < 0 -> -overdue                                 // before the new post is due
            overdue < 24 * 3_600_000L -> 3_600_000L                 // the first day: hourly
            else -> 6 * 3_600_000L
        }
        return wait.coerceAtLeast(60_000L)
    }

    /** Queues the next check (replacing any queued one). */
    fun schedule(ctx: Context) {
        if (!AppSettings.get(ctx).radiogramUsed) return
        val delay = delayMs(RadiogramRepository(ctx).load(), System.currentTimeMillis())
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            NAME, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<PollWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }

    class PollWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val repo = RadiogramRepository(ctx)
            val r = repo.refresh()
            if (r.newPost && AppSettings.get(ctx).radiogramNotify) notifyNewSchedule(ctx, repo.load())
            schedule(ctx)
            return Result.success()
        }
    }

    private const val CHANNEL = "radiogram_schedule"

    /** "New schedule posted" with the next transmission, so the user knows when to tune in. Tapping opens the schedule. */
    private fun notifyNewSchedule(ctx: Context, week: RadiogramWeek) {
        if (!androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val now = System.currentTimeMillis()
        val next = RadiogramParser.place(week.slots, week.start).firstOrNull { it.endMs > now }
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm", java.util.Locale.getDefault())
        fun at(ms: Long, zone: java.time.ZoneId) = fmt.format(java.time.Instant.ofEpochMilli(ms).atZone(zone))
        val nextText = if (next == null) "No transmissions left this week." else
            "Next: ${at(next.startMs, java.time.ZoneId.systemDefault())} local (${at(next.startMs, java.time.ZoneOffset.UTC).takeLast(5)} UTC), ${next.kHz} kHz, ${next.transmitter}."
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "New Shortwave Radiogram schedule", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.app.PendingIntent.getActivity(
            ctx, 2, android.content.Intent(ctx, app.radiomodem.MainActivity::class.java).putExtra("route", "radiogram")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val head = "Program ${week.program ?: "?"}, ${week.start.dayOfMonth} ${week.start.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}–" +
            "${week.end.dayOfMonth} ${week.end.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}"
        val unread = if (week.rowsFrom == "image") "" else "\nThe times could not be re-read from the picture, so check them against the post."
        val n = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle("New Shortwave Radiogram schedule")
            .setContentText("$head. $nextText")
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText("$head.\n$nextText$unread"))
            .setContentIntent(open).setAutoCancel(true).setCategory(androidx.core.app.NotificationCompat.CATEGORY_EVENT)
            .build()
        nm.notify(7001, n)
    }
}
