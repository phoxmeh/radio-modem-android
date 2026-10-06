package app.radiomodem.radiogram

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.radiomodem.MainActivity
import app.radiomodem.settings.AppSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reminders for individual Shortwave Radiogram transmissions. Each one is an alarm a few minutes before the start
 * that posts a notification. The list is kept on disk so the alarms can be set again after a reboot, and reminders
 * for transmissions that have started are dropped.
 */
class RadiogramReminders(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("radiogram_reminders", Context.MODE_PRIVATE)
    private val alarms = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun all(): List<RadiogramBroadcast> = prune()

    fun has(key: String) = read().any { it.key == key }

    fun set(b: RadiogramBroadcast, leadMin: Int) {
        write(read().filter { it.key != b.key } + b)
        schedule(b, leadMin)
    }

    fun clear(key: String) {
        read().firstOrNull { it.key == key }?.let { cancel(it) }
        write(read().filter { it.key != key })
    }

    /** After the lead time changes or the phone restarts. */
    fun rescheduleAll(leadMin: Int) = prune().forEach { schedule(it, leadMin) }

    /** True when alarms can fire on the minute; otherwise Android may delay them by several minutes. */
    fun canBeExact() = android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    private fun prune(): List<RadiogramBroadcast> {
        val now = System.currentTimeMillis()
        val keep = read().filter { it.startMs > now }
        if (keep.size != read().size) write(keep)
        return keep
    }

    private fun schedule(b: RadiogramBroadcast, leadMin: Int) {
        val at = maxOf(System.currentTimeMillis() + 2_000, b.startMs - leadMin * 60_000L)
        val pi = pending(b)
        if (canBeExact()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun cancel(b: RadiogramBroadcast) = alarms.cancel(pending(b))

    private fun pending(b: RadiogramBroadcast): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setData(Uri.parse("radiogram://reminder/${b.key}"))
            .putExtra("startMs", b.startMs).putExtra("endMs", b.endMs).putExtra("kHz", b.kHz).putExtra("tx", b.transmitter)
        return PendingIntent.getBroadcast(ctx, b.key.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun read(): List<RadiogramBroadcast> = runCatching {
        JSONArray(prefs.getString("list", "[]")).let { a ->
            (0 until a.length()).map { a.getJSONObject(it).let { o -> RadiogramBroadcast(o.getLong("s"), o.getLong("e"), o.getInt("k"), o.getString("t")) } }
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<RadiogramBroadcast>) {
        prefs.edit().putString("list", JSONArray(list.map { JSONObject().put("s", it.startMs).put("e", it.endMs).put("k", it.kHz).put("t", it.transmitter) }).toString()).apply()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val start = intent.getLongExtra("startMs", 0); val end = intent.getLongExtra("endMs", 0)
        val kHz = intent.getIntExtra("kHz", 0); val tx = intent.getStringExtra("tx").orEmpty()
        if (start == 0L || end < System.currentTimeMillis()) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Shortwave Radiogram reminders", NotificationManager.IMPORTANCE_HIGH))
        val fmt = java.text.SimpleDateFormat("HHmm", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val mins = ((start - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle(if (mins <= 0) "Shortwave Radiogram is starting" else "Shortwave Radiogram in $mins min")
            .setContentText("$kHz kHz · $tx · ${fmt.format(java.util.Date(start))}–${fmt.format(java.util.Date(end)).replace("0000", "2400")} UTC")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$kHz kHz from $tx, ${fmt.format(java.util.Date(start))}–${fmt.format(java.util.Date(end))} UTC.\nTune USB or AM, MFSK32, waterfall at 1500 Hz."))
            .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        if (NotificationManagerCompat.from(ctx).areNotificationsEnabled()) nm.notify((start / 60_000L).toInt() xor kHz, n)
        RadiogramReminders(ctx).clear("$start|$kHz")
    }
    companion object { const val CHANNEL = "radiogram_reminders" }
}

/** Alarms do not survive a restart, so the saved reminders are set again. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) RadiogramReminders(ctx).rescheduleAll(AppSettings.get(ctx).radiogramLeadMin.toInt())
    }
}
