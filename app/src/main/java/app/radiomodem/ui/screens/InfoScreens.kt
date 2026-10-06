package app.radiomodem.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.radiomodem.SessionLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun LogsScreen(logger: SessionLogger, onBack: () -> Unit, open: (File) -> Unit) {
    var files by remember { mutableStateOf(logger.files()) }
    ScreenPage("Session logs", onBack) {
        if (files.isEmpty()) Hint("No logs yet. A log file is created the first time text is decoded in a session (it can be turned off under Settings > Startup and logging).")
        files.forEach { f ->
            Row(Modifier.fillMaxWidth().clickable { open(f) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val stamp = runCatching {
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(f.nameWithoutExtension)!!)
                    }.getOrDefault(f.name)
                    Text(stamp, style = MaterialTheme.typography.bodyLarge)
                    Hint("${(f.length() + 512) / 1024} KB")
                }
                TextButton({ logger.delete(f); files = logger.files() }) { Text("Delete") }
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun LogViewScreen(file: File, logger: SessionLogger, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val text = remember(file) { runCatching { file.readText() }.getOrDefault("(cannot read this log)") }
    ScreenPage(file.nameWithoutExtension, onBack, actions = {
        TextButton({
            val uri = FileProvider.getUriForFile(ctx, "app.radiomodem.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Share") }
        TextButton({ logger.delete(file); Toast.makeText(ctx, "Log deleted", Toast.LENGTH_SHORT).show(); onBack() }) { Text("Delete") }
    }) {
        SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    ScreenPage("About", onBack) {
        Text("Radio Modem", style = MaterialTheme.typography.headlineSmall)
        Text("Version $version")
        Text("Receive digital modes (PSK, QPSK, MFSK with pictures, Olivia, weather fax) on Android, with CAT rig control, broadcast schedules for weather fax and Shortwave Radiogram, and background monitoring.")
        HorizontalDivider()
        SectionLabel("License")
        Text("Free software under the GNU General Public License, version 3 or (at your option) any later version. It comes with ABSOLUTELY NO WARRANTY. " +
            "The decoders are ports of algorithms from fldigi, which is GPLv3, so the whole app is distributed under the same terms.")
        HorizontalDivider()
        SectionLabel("Credits")
        listOf(
            "fldigi — Dave Freese W1HKJ and contributors (GPLv3). PSK, QPSK, MFSK and weather-fax demodulators, the Olivia receiver wrapper, varicode tables, Viterbi decoder and interleaver are ported from it.",
            "flrig — Dave Freese W1HKJ and contributors (GPLv3). The Yaesu FT-891, FT-991A and FTdx10 modes, bandwidths and CAT command sets are taken from its rig classes.",
            "Olivia / MFSK library — Pawel Jalocha SP9VRC, vendored unchanged via fldigi.",
            "gMFSK — Tomi Manninen OH2BNS, the origin of much of fldigi's modem code.",
            "usb-serial-for-android — Mike Wakerly and contributors (MIT). USB serial CAT.",
            "Oboe — Google (Apache 2.0). Low-latency audio capture.",
            "PdfBox-Android — Tom Roush, port of Apache PDFBox (Apache 2.0). Reads NOAA's published fax schedule PDF.",
            "Tesseract OCR and Tesseract4Android (Apache 2.0), with the Tesseract English model. Reads the Shortwave Radiogram schedule picture.",
            "Fax schedules — NOAA/NWS (US government work), DWD, UK Met Office and other national weather services, as published by them.",
            "Shortwave Radiogram — produced by Dr. Kim Andrew Elliott and team (swradiogram.net). The weekly schedule is read from the blog and not republished.",
            "Android Jetpack and Compose — Google (Apache 2.0). User interface.",
        ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        HorizontalDivider()
        Hint("Weather-fax and amateur-radio frequency lists are starting points only; check current schedules and band plans.")
    }
}

/** First-run checklist, also reachable from the menu. */
@Composable
fun HelpScreen(micGranted: Boolean, requestMic: () -> Unit, openAudio: () -> Unit, openRig: () -> Unit, onDone: () -> Unit) =
    ScreenPage("Getting started", onDone) {
        Step(1, "Allow microphone access", if (micGranted) "Granted. This is also what lets Android deliver audio from a USB sound card." else "Needed to receive any audio, including from a USB sound card.") {
            if (!micGranted) Button(requestMic) { Text("Grant permission") }
        }
        Step(2, "Choose an audio source", "Phone microphone, headset or a USB sound card connected to the radio. Watch the level bar while a signal is playing.") {
            OutlinedButton(openAudio) { Text("Open Audio setup") }
        }
        Step(3, "Connect your radio (optional)", "Without CAT the app still decodes audio; with it, the dial, mode, bandwidth and power controls talk to the radio.") {
            OutlinedButton(openRig) { Text("Open Rig setup") }
        }
        Step(4, "Tune the decoder", "Tap a signal on the waterfall to set the decode frequency. The shaded band shows the width of the selected mode and the red line is the carrier. Drag to scroll and pinch to zoom the waterfall; drag the bar under it to resize.", null)
        Step(5, "Pick a mode", "Use the mode chip at the top of the main screen, or the Modem drawer (swipe in from the right edge) for more options such as AFC, Reverse and squelch.", null)
        Step(6, "Pictures and fax", "MFSK picture transmissions and weather fax appear in the receive area as they arrive and are saved to your gallery. Weather fax: tune USB at the station's assigned frequency minus 1.9 kHz.", null)
        Button(onDone, Modifier.fillMaxWidth()) { Text("Start receiving") }
    }

@Composable
private fun Step(n: Int, title: String, body: String, action: (@Composable () -> Unit)?) = Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(28.dp)) {
        Box(contentAlignment = Alignment.Center) { Text("$n", style = MaterialTheme.typography.labelLarge) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Hint(body)
        action?.invoke()
    }
}
