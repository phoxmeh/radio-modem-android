package app.radiomodem.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.radiomodem.data.MemoryViewModel
import app.radiomodem.radiogram.*
import app.radiomodem.settings.AppSettings
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate

/** This week's Shortwave Radiogram transmissions: when, on what frequency, from where, with Tune and reminders. */
@Composable
fun RadiogramScreen(vm: RadiogramViewModel, mem: MemoryViewModel, onBack: () -> Unit, onTune: (RadiogramBroadcast) -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { AppSettings.get(ctx) }
    val tz = Tz(settings.scheduleUtc)
    var showNotes by remember { mutableStateOf(false) }
    var showLineup by remember { mutableStateOf(false) }
    var askExact by remember { mutableStateOf<RadiogramBroadcast?>(null) }
    var pending by remember { mutableStateOf<RadiogramBroadcast?>(null) }

    // Keep the countdowns current, and refresh once when the saved copy is old.
    LaunchedEffect(Unit) { while (true) { vm.tick(); delay(15_000) } }
    LaunchedEffect(Unit) { vm.reschedule() }
    LaunchedEffect(Unit) { vm.refresh { slots -> mem.import(RadiogramEntries.memories(slots)) } }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.let { b -> vm.toggleReminder(b); if (!vm.canBeExact()) askExact = b }
        pending = null
    }
    fun remind(b: RadiogramBroadcast) {
        if (vm.has(b)) { vm.toggleReminder(b); return }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pending = b; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else { vm.toggleReminder(b); if (!vm.canBeExact()) askExact = b }
    }

    val view = vm.view()
    val now = vm.nowMs
    val next = view.broadcasts.firstOrNull { it.endMs > now }

    ScreenPage("Shortwave Radiogram", onBack, actions = {
        TextButton({ settings.scheduleUtc = !settings.scheduleUtc }) { Text(if (settings.scheduleUtc) "UTC" else "Local") }
        TextButton({ vm.refresh(force = true) { slots -> mem.import(RadiogramEntries.memories(slots)) } }, enabled = !vm.refreshing) { Text(if (vm.refreshing) "Updating…" else "Update") }
    }) {
        // ---- this week ----
        val w = view.week
        Text(
            if (w.program != null && !view.projected) "Program ${w.program} · ${dateRange(w.start, w.end)}" else "Week of ${dateRange(view.start, view.start.plusDays(7))}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            when {
                view.projected -> "This week's post is not out yet (they appear on Thursdays). Showing the usual table carried forward."
                w.rowsFrom == "image" -> "Read from the picture in the latest post."
                w.rowsFrom == "previous" -> "The latest post's picture could not be read, so the last known table is used with this week's dates."
                else -> "Bundled schedule. Tap Update to read the latest post."
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Successful reads are already described above; show only problems and "already current".
        if (vm.message.isNotEmpty() && !vm.message.startsWith("Program ")) Hint(vm.message)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (w.postUrl.isNotEmpty()) OutlinedButton({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(w.postUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open post") }
            if (w.intro.isNotEmpty()) OutlinedButton({ showNotes = !showNotes }) { Text(if (showNotes) "Hide notes" else "This week's notes") }
        }
        if (showNotes) Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) { Text(w.intro, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }

        // ---- next up ----
        if (next != null) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (next.startMs <= now) "On air now" else "Next · " + countdown(next.startMs - now), style = MaterialTheme.typography.labelLarge)
                Text("${tz.dayLabel(next.startMs)} · ${tz.hhmm(next.startMs)}–${hhmmEnd(tz, next)} ${tz.name}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("${next.kHz} kHz · ${next.transmitter}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button({ onTune(next) }) { Text("Tune") }
                    ReminderToggle(vm.has(next), next.startMs > now) { remind(next) }
                }
            }
        }

        ChoiceRow("Remind me before a transmission", listOf(5, 10, 15, 30).map { it.toString() to "$it minutes before" }, vm.leadMin.toString(),
            subtitle = if (vm.canBeExact()) null else "Approximate: allow Alarms & reminders for on-time alerts") { vm.setLead(it.toInt()) }
        HorizontalDivider()

        // ---- the week, by day ----
        view.broadcasts.groupBy { tz.at(it.startMs).toLocalDate() }.forEach { (day, list) ->
            Text(tz.dayLabel(list.first().startMs), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            list.forEach { b ->
                val done = b.endMs <= now
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = if (b == next) 6.dp else 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            val dim = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                            Text("${tz.hhmm(b.startMs)}–${hhmmEnd(tz, b)} ${tz.name}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = dim)
                            Text("${b.kHz} kHz · ${b.transmitter}", color = dim)
                            Text(
                                when { done -> "Done"; b.startMs <= now -> "On air now"; else -> "in " + countdown(b.startMs - now) },
                                style = MaterialTheme.typography.labelMedium, color = if (b.startMs <= now && !done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ReminderToggle(vm.has(b), !done && b.startMs > now) { remind(b) }
                        TextButton({ onTune(b) }, enabled = !done) { Text("Tune") }
                    }
                }
            }
        }

        // ---- lineup ----
        if (w.lineup.isNotEmpty()) {
            HorizontalDivider()
            TextButton({ showLineup = !showLineup }, contentPadding = PaddingValues(0.dp)) { Text(if (showLineup) "This week's lineup ▾" else "This week's lineup ▸") }
            if (showLineup) w.lineup.forEach { Text("${it.offset.padStart(5)}  ${it.mode}: ${it.title}", style = MaterialTheme.typography.bodyMedium) }
        }
        Hint("Tuning sets the radio's default mode (Rig setup), the MFSK32 decoder, and the audio carrier to 1500 Hz. The programme starts in MFSK32, then uses MFSK64 for pictures. Shortwave Radiogram is an AM transmission, so USB or AM both work.")
    }

    askExact?.let { b ->
        AlertDialog(
            onDismissRequest = { askExact = null },
            title = { Text("Allow on-time reminders?") },
            text = { Text("Reminder set. Android delivers reminders on the minute only if this app may set exact alarms; otherwise they can arrive several minutes late. You can allow it now in Android's settings.") },
            confirmButton = {
                TextButton({
                    askExact = null
                    if (Build.VERSION.SDK_INT >= 31) ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton({ askExact = null }) { Text("Not now") } },
        )
    }
}

@Composable
private fun ReminderToggle(on: Boolean, enabled: Boolean, onClick: () -> Unit) =
    FilledTonalIconToggleButton(on, { onClick() }, enabled = enabled) {
        Icon(Icons.Default.Notifications, contentDescription = if (on) "Reminder on" else "Remind me")
    }

private fun hhmmEnd(tz: Tz, b: RadiogramBroadcast): String =
    if (tz.utc && tz.at(b.endMs).let { it.hour == 0 && it.minute == 0 }) "24:00" else tz.hhmm(b.endMs)

private fun countdown(ms: Long): String {
    val m = (ms / 60_000L).coerceAtLeast(0)
    return when { m >= 2880 -> "${m / 1440} days"; m >= 60 -> "${m / 60} h ${m % 60} min"; else -> "$m min" }
}

private fun dateRange(a: LocalDate, b: LocalDate): String {
    fun d(x: LocalDate) = "${x.dayOfMonth} ${x.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)}"
    return "${d(a)}–${d(b)} ${b.year}"
}
