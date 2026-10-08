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
            repo.refresh()
            schedule(ctx)
            return Result.success()
        }
    }
}
