package app.radiomodem.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.radiomodem.schedule.*

/**
 * Upcoming weather-fax broadcasts for the stations you pick: a scrollable timeline with one row per station, a red
 * "now" line, and a detail sheet with Tune buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(vm: ScheduleViewModel, onBack: () -> Unit, onTune: (FaxStation, FaxFrequency) -> Unit) {
    val settings = vm.settings
    val tz = Tz(settings.scheduleUtc)
    val now = vm.nowMs
    val dpPerHour = settings.scheduleDpPerHour
    var picker by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Instance?>(null) }

    val instances = remember(vm.data, vm.selectedIds, vm.types, vm.windowStartMs) { vm.instances() }
    val stations = vm.stations
    val layout = remember(instances, stations, dpPerHour, vm.windowStartMs) {
        TimelineLayout(vm.windowStartMs, ScheduleViewModel.WINDOW_HOURS.toInt(), dpPerHour, stations, instances)
    }
    val next = remember(instances, now / 15_000) { ScheduleEngine.nextUp(instances, now) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Fax schedule") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    TextButton({ settings.scheduleUtc = !settings.scheduleUtc }) { Text(if (settings.scheduleUtc) "UTC" else "Local") }
                    TextButton({ vm.refresh() }, enabled = !vm.refreshing) { Text(if (vm.refreshing) "Updating…" else "Update") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            NextUpBar(next, tz, now) { selected = it }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ChartType.values().forEach { t ->
                    FilterChip(t in vm.types, { vm.toggleType(t) }, { Text(t.label, fontSize = 12.sp) },
                        leadingIcon = { Box(Modifier.size(10.dp).background(t.color(isSystemDark()), CircleShape)) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ picker = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Stations (${stations.size})", fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("Zoom", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 4.dp))
                OutlinedButton({ settings.scheduleDpPerHour = (dpPerHour / 1.4f).coerceAtLeast(50f) },
                    contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("−") }
                Spacer(Modifier.width(4.dp))
                OutlinedButton({ settings.scheduleDpPerHour = (dpPerHour * 1.4f).coerceAtMost(320f) },
                    contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) { Text("+") }
            }
            vm.message?.let {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (!vm.refreshing) TextButton({ vm.message = null }) { Text("OK") }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    vm.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    stations.isEmpty() -> EmptyState(vm.selectedIds.isEmpty()) { picker = true }
                    else -> {
                        val hScroll = rememberScrollState()
                        val vScroll = rememberScrollState()
                        val density = LocalDensity.current.density
                        val labelW = 92.dp
                        // Open with "now" about a quarter of the way across, once.
                        val viewportDp = LocalConfiguration.current.screenWidthDp - labelW.value
                        var positioned by remember { mutableStateOf(false) }
                        LaunchedEffect(layout.width, hScroll.maxValue) {
                            if (!positioned && hScroll.maxValue > 0) {
                                hScroll.scrollTo(((layout.x(now) - viewportDp * 0.25f) * density).toInt().coerceAtLeast(0))
                                positioned = true
                            }
                        }
                        Row(Modifier.fillMaxSize()) {
                            StationLabels(layout, vScroll, labelW)
                            TimelineBody(layout, tz, now, hScroll, vScroll, Modifier.weight(1f).fillMaxHeight()) { selected = it }
                        }
                    }
                }
            }
            Text(
                vm.source + (vm.settings.scheduleLastCheck.toLongOrNull()?.let { " · checked ${tz.dayLabel(it)} ${tz.hhmm(it)}" } ?: " · not checked yet"),
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (picker) StationPicker(vm) { picker = false }
    selected?.let { DetailSheet(it, vm, tz, now, { selected = null }) { s, f -> selected = null; onTune(s, f) } }
}

@Composable
private fun isSystemDark() = MaterialTheme.colorScheme.background.let { (it.red + it.green + it.blue) / 3f < 0.5f }

@Composable
private fun EmptyState(noneChosen: Boolean, choose: () -> Unit) = Column(
    Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
) {
    Text(if (noneChosen) "No stations chosen" else "The chosen stations have no schedule data", style = MaterialTheme.typography.titleMedium)
    Hint("Pick the stations you want to follow.")
    Spacer(Modifier.height(8.dp))
    Button(choose) { Text("Choose stations") }
}

private fun relative(ms: Long): String {
    val m = Math.round(ms / 60_000.0)
    return when {
        m < 1 -> "now"
        m < 60 -> "$m min"
        else -> "${m / 60} h ${"%02d".format(m % 60)} min"
    }
}

@Composable
private fun NextUpBar(next: Instance?, tz: Tz, now: Long, open: (Instance) -> Unit) {
    val dark = isSystemDark()
    Surface(
        onClick = { next?.let(open) }, modifier = Modifier.fillMaxWidth().padding(8.dp), enabled = next != null,
        shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (next == null) {
                Text("No upcoming broadcasts for the selected stations and chart types.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Box(Modifier.size(12.dp).background(next.broadcast.type.color(dark), CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${next.station.place} · ${next.title}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    val whenText = if (next.isOn(now)) "On air now, ends in ${relative(next.endMs - now)}" else "Starts in ${relative(next.startMs - now)}"
                    Text("$whenText · ${tz.hhmm(next.startMs)} ${tz.name}", style = MaterialTheme.typography.bodySmall)
                }
                Text("NEXT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationPicker(vm: ScheduleViewModel, onDismiss: () -> Unit) = ModalBottomSheet(onDismiss) {
    val chosen = vm.selectedIds
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Stations", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton({ vm.setStations(FaxStations.all.filter { vm.data.schedules.containsKey(it.id) }.map { it.id }.toSet()) }) { Text("All") }
            TextButton({ vm.setStations(emptySet()) }) { Text("None") }
        }
        FaxStations.regions.forEach { region ->
            SectionLabel(region)
            FaxStations.all.filter { it.region == region }.forEach { s ->
                val has = vm.data.schedules.containsKey(s.id)
                Row(Modifier.fillMaxWidth().clickable(enabled = has) { vm.toggleStation(s.id) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(s.id in chosen, { vm.toggleStation(s.id) }, enabled = has)
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge)
                        Hint(if (has) "${s.call} · ${(vm.data.schedules[s.id]?.broadcasts?.size ?: 0)} broadcasts · schedule dated ${s.dated}" else "${s.call} · schedule not available yet")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(i: Instance, vm: ScheduleViewModel, tz: Tz, now: Long, onDismiss: () -> Unit, tune: (FaxStation, FaxFrequency) -> Unit) =
    ModalBottomSheet(onDismiss) {
        val dark = isSystemDark()
        val s = i.station
        val local = Tz(!tz.utc)
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(i.broadcast.type.color(dark), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(i.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text("${s.title} · ${s.call}", style = MaterialTheme.typography.bodyMedium)
            Text("${tz.dayLabel(i.startMs)}, ${tz.hhmm(i.startMs)}–${tz.hhmm(i.endMs)} ${tz.name}" +
                "   (${local.hhmm(i.startMs)} ${local.name})", style = MaterialTheme.typography.bodyMedium)
            Text(
                (if (i.isOn(now)) "On air now. " else if (i.startMs > now) "Starts in ${relative(i.startMs - now)}. " else "Already broadcast. ") +
                    "${i.broadcast.lpm} lines per minute, IOC ${i.broadcast.ioc}. ${i.broadcast.type.label} chart. " +
                    "End time is estimated (schedules give start times only).",
                style = MaterialTheme.typography.bodySmall,
            )
            i.broadcast.days?.let { d ->
                val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                Text("Only on: " + d.sorted().joinToString(", ") { names[it - 1] } + " (UTC days)", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()

            val onAir = ScheduleEngine.frequenciesFor(i)
            SectionLabel("Tune to a frequency on air at this time")
            if (onAir.isEmpty()) Hint("No listed frequency is on air at this hour; check the station's hours below.")
            onAir.forEach { f ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${kHz(f.kHz)} kHz", style = MaterialTheme.typography.bodyLarge)
                        Hint("USB dial ${"%.1f".format(s.dialKHz(f))} kHz" + if (f.hours.isNotEmpty()) " · ${f.hours}" else "")
                    }
                    Button({ tune(s, f) }) { Text("Tune") }
                }
            }
            HorizontalDivider()
            SectionLabel("When each frequency is on air (UTC)")
            s.freqs.forEach { f -> FrequencyHours(f, i.broadcast.minute, i.broadcast.onlyKHz) }
            Hint("The line marks this broadcast. Frequencies change with the time of day: lower ones usually at night, higher ones by day.")
            if (s.note.isNotEmpty()) Hint(s.note)
            vm.data.schedules[s.id]?.note?.takeIf { it.isNotEmpty() }?.let { Hint(it) }
            Hint("${s.operator}. Station details dated ${s.dated}.")
        }
    }

private fun kHz(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else v.toString().trimEnd('0')

/** A 24-hour bar: filled where the frequency is on air, with a marker at the broadcast. */
@Composable
private fun FrequencyHours(f: FaxFrequency, marker: Int, only: Double?) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val dim = only != null && only != f.kHz
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (dim) 0.45f else 1f)) {
        Text("${kHz(f.kHz)}", Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium)
        Canvas(Modifier.weight(1f).height(14.dp)) {
            drawRect(track, Offset.Zero, size)
            val w = f.window
            val segs = if (w == null) listOf(0 to 1440) else if (w.startMin <= w.endMin) listOf(w.startMin to w.endMin) else listOf(w.startMin to 1440, 0 to w.endMin)
            for ((a, b) in segs) drawRect(primary, Offset(size.width * a / 1440f, 0f), Size(size.width * (b - a) / 1440f, size.height))
            val x = size.width * marker / 1440f
            drawLine(Color(0xFFE5484D), Offset(x, 0f), Offset(x, size.height), 3f)
        }
        Text(if (f.window == null) "all day" else "%02d:%02d–%02d:%02d".format(f.window!!.startMin / 60, f.window!!.startMin % 60, f.window!!.endMin / 60, f.window!!.endMin % 60),
            Modifier.width(92.dp).padding(start = 6.dp), style = MaterialTheme.typography.labelSmall)
    }
}

