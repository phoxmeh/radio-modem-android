package app.radiomodem.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.radiomodem.schedule.ChartType
import app.radiomodem.schedule.FaxStation
import app.radiomodem.schedule.Instance
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** One colour per chart type, tuned for both themes. */
fun ChartType.color(dark: Boolean): Color = when (this) {
    ChartType.Analysis -> if (dark) Color(0xFF6C9EF8) else Color(0xFF2F63C9)
    ChartType.Forecast -> if (dark) Color(0xFF5FD0A4) else Color(0xFF1C8A63)
    ChartType.Satellite -> if (dark) Color(0xFFF2A65A) else Color(0xFFC96A12)
    ChartType.Waves -> if (dark) Color(0xFF4FC3E0) else Color(0xFF1B8DA8)
    ChartType.Ice -> if (dark) Color(0xFFB7D7F5) else Color(0xFF6A93BD)
    ChartType.Ocean -> if (dark) Color(0xFFC59BF0) else Color(0xFF7E4DB5)
    ChartType.Other -> if (dark) Color(0xFF9AA0A8) else Color(0xFF727780)
}

/** Time zone handling for the schedule: UTC as published, or the phone's local time. */
class Tz(val utc: Boolean) {
    val zone: ZoneId = if (utc) ZoneOffset.UTC else ZoneId.systemDefault()
    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(zone)
    private val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).withZone(zone)
    fun hhmm(ms: Long): String = hm.format(Instant.ofEpochMilli(ms))
    fun dayLabel(ms: Long): String = day.format(Instant.ofEpochMilli(ms))
    val name: String get() = if (utc) "UTC" else zone.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
    fun at(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)
}

class TimelineLayout(val startMs: Long, val hours: Int, val dpPerHour: Float, val stations: List<FaxStation>, val instances: List<Instance>) {
    val laneH = 26f; val rowPad = 8f; val headerH = 36f
    fun lanes(s: FaxStation) = (instances.filter { it.station.id == s.id }.maxOfOrNull { it.lane } ?: 0) + 1
    fun rowHeight(s: FaxStation) = lanes(s) * laneH + rowPad
    fun rowTop(index: Int) = (0 until index).sumOf { rowHeight(stations[it]).toDouble() }.toFloat()
    val bodyHeight get() = stations.sumOf { rowHeight(it).toDouble() }.toFloat()
    val width get() = hours * dpPerHour
    fun x(ms: Long) = (ms - startMs) / 3_600_000f * dpPerHour
}

/** Station names, pinned at the left; shares its vertical scroll with the timeline body. */
@Composable
fun StationLabels(layout: TimelineLayout, vScroll: androidx.compose.foundation.ScrollState, width: androidx.compose.ui.unit.Dp) =
    Column(Modifier.width(width)) {
        Spacer(Modifier.height(layout.headerH.dp))
        Column(Modifier.weight(1f).verticalScroll(vScroll)) {
            layout.stations.forEach { s ->
                Column(Modifier.height(layout.rowHeight(s).dp).padding(horizontal = 6.dp), verticalArrangement = Arrangement.Center) {
                    Text(s.place, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.call.substringBefore('/'), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

/** The scrollable part: hour axis on top, one row of coloured blocks per station, and a "now" line. */
@Composable
fun TimelineBody(
    layout: TimelineLayout, tz: Tz, nowMs: Long, hScroll: androidx.compose.foundation.ScrollState,
    vScroll: androidx.compose.foundation.ScrollState, modifier: Modifier, onTap: (Instance) -> Unit,
) {
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer()
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val onBg = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val stripe = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val blockText = Color(0xFF101418)

    Column(modifier.horizontalScroll(hScroll)) {
        // --- axis ---
        Canvas(Modifier.width(layout.width.dp).height(layout.headerH.dp)) {
            val label = TextStyle(fontSize = 11.sp, color = onBg)
            var t = tz.at(layout.startMs).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()
            if (t < layout.startMs) t += 3_600_000L
            while (t < layout.startMs + layout.hours * 3_600_000L) {
                val x = layout.x(t) * density
                val z = tz.at(t)
                drawLine(grid, Offset(x, size.height * 0.55f), Offset(x, size.height), 1f)
                val text = if (z.minute == 0) "%02d".format(z.hour) else tz.hhmm(t)
                drawText(measurer, text, Offset(x + 3f * density, size.height * 0.45f), label)
                if (z.hour == 0) drawText(measurer, tz.dayLabel(t), Offset(x + 3f * density, 1f * density), label.copy(fontSize = 10.sp, color = onBg.copy(alpha = 0.75f)))
                t += 3_600_000L
            }
            // first label of the window also names the day
            drawText(measurer, tz.dayLabel(layout.startMs) + " · " + tz.name, Offset(3f * density, 1f * density),
                label.copy(fontSize = 10.sp, color = onBg.copy(alpha = 0.75f)))
            val nx = layout.x(nowMs) * density
            drawLine(Color(0xFFE5484D), Offset(nx, 0f), Offset(nx, size.height), 2f * density)
        }
        // --- rows ---
        Box(Modifier.weight(1f).verticalScroll(vScroll)) {
            Canvas(
                Modifier.width(layout.width.dp).height(layout.bodyHeight.dp)
                    .pointerInput(layout, tz) {
                        detectTapGestures { p ->
                            val xd = p.x / density; val yd = p.y / density
                            val row = (0 until layout.stations.size).firstOrNull { yd >= layout.rowTop(it) && yd < layout.rowTop(it) + layout.rowHeight(layout.stations[it]) }
                                ?: return@detectTapGestures
                            val station = layout.stations[row]
                            val lane = ((yd - layout.rowTop(row) - layout.rowPad / 2) / layout.laneH).toInt()
                            layout.instances.filter { it.station.id == station.id && it.lane == lane }
                                .minByOrNull { i ->
                                    val x0 = layout.x(i.startMs); val x1 = maxOf(layout.x(i.endMs), x0 + 14f)
                                    if (xd in x0..x1) 0f else minOf(kotlin.math.abs(xd - x0), kotlin.math.abs(xd - x1))
                                }?.let { i ->
                                    val x0 = layout.x(i.startMs); val x1 = maxOf(layout.x(i.endMs), x0 + 14f)
                                    if (xd >= x0 - 6f && xd <= x1 + 6f) onTap(i)
                                }
                        }
                    },
            ) {
                // stripes and hour grid
                var top = 0f
                layout.stations.forEachIndexed { i, s ->
                    val h = layout.rowHeight(s) * density
                    if (i % 2 == 0) drawRect(stripe, Offset(0f, top), Size(size.width, h))
                    top += h
                }
                var t = tz.at(layout.startMs).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()
                if (t < layout.startMs) t += 3_600_000L
                while (t < layout.startMs + layout.hours * 3_600_000L) {
                    val x = layout.x(t) * density
                    drawLine(grid.copy(alpha = if (tz.at(t).hour == 0) 0.9f else 0.35f), Offset(x, 0f), Offset(x, size.height), if (tz.at(t).hour == 0) 2f else 1f)
                    t += 3_600_000L
                }
                // blocks
                val titleStyle = TextStyle(fontSize = 10.sp, color = blockText)
                for (i in layout.instances) {
                    val row = layout.stations.indexOfFirst { it.id == i.station.id }
                    if (row < 0) continue
                    val x0 = layout.x(i.startMs) * density
                    val w = maxOf(layout.x(i.endMs) * density - x0, 6f * density)
                    val y = (layout.rowTop(row) + layout.rowPad / 2 + i.lane * layout.laneH) * density
                    val past = i.endMs < nowMs
                    val c = i.broadcast.type.color(dark).copy(alpha = if (past) 0.38f else 1f)
                    drawRoundRect(c, Offset(x0, y), Size(w - 1.5f * density, (layout.laneH - 3f) * density), CornerRadius(4f * density))
                    if (w > 52f * density) drawText(measurer, i.title, Offset(x0 + 4f * density, y + 3f * density), titleStyle,
                        overflow = TextOverflow.Ellipsis, maxLines = 1, size = Size(w - 8f * density, (layout.laneH - 6f) * density))
                }
                val nx = layout.x(nowMs) * density
                drawLine(Color(0xFFE5484D), Offset(nx, 0f), Offset(nx, size.height), 2f * density)
            }
        }
    }
}

private fun Color.luminanceIsDark(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f
