package app.radiomodem.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.radiomodem.Palettes
import app.radiomodem.settings.AppSettings
import app.radiomodem.settings.Station

@Composable
private fun settings() = AppSettings.get(LocalContext.current)

@Composable
fun SettingsHome(onBack: () -> Unit, open: (String) -> Unit) = ScreenPage("Settings", onBack) {
    NavRow("Display", "Theme, text size, timestamps, keep screen on") { open("settings_display") }
    HorizontalDivider()
    NavRow("Waterfall", "Colour palette, speed, default brightness and zoom") { open("settings_waterfall") }
    HorizontalDivider()
    NavRow("Decoding defaults", "Squelch per mode family, AFC and Reverse defaults") { open("settings_decoding") }
    HorizontalDivider()
    NavRow("Pictures", "Auto-save, file format and folder") { open("settings_pictures") }
    HorizontalDivider()
    NavRow("Station", "Callsign, name and locator") { open("settings_station") }
    HorizontalDivider()
    NavRow("Startup and logging", "Restore last state, auto-reconnect, session logs") { open("settings_general") }
}

@Composable
fun DisplaySettings(onBack: () -> Unit) = ScreenPage("Display", onBack) {
    val s = settings()
    SettingChoice("Theme", listOf("system" to "System", "dark" to "Dark", "light" to "Light"), s.theme) { s.theme = it }
    SettingSlider("Received text size", "${s.feedTextSp.toInt()} sp", s.feedTextSp, 10f..26f) { s.feedTextSp = Math.round(it).toFloat() }
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Text("CQ CQ de W1AW pse k", Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, fontSize = s.feedTextSp.sp)
    }
    SettingSwitch("Timestamps in the feed", "Prefix each received line with [HH:MM:SS]", s.feedTimestamps) { s.feedTimestamps = it }
    SettingSwitch("Keep screen on", "Stop the display sleeping while the main screen is open", s.keepScreenOn) { s.keepScreenOn = it }
}

@Composable
fun WaterfallSettings(onBack: () -> Unit) = ScreenPage("Waterfall", onBack) {
    val s = settings()
    SectionLabel("Colour palette")
    Palettes.names.forEach { (id, label) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(s.waterfallPalette == id, { s.waterfallPalette = id })
            Text(label, Modifier.width(96.dp))
            Canvas(Modifier.weight(1f).height(18.dp)) {
                val n = 64
                for (i in 0 until n) drawRect(Color(Palettes.color(id, i / (n - 1f))), Offset(size.width * i / n, 0f), Size(size.width / n + 1f, size.height))
            }
        }
    }
    SettingChoice("Scroll speed", listOf("slow" to "Slow", "normal" to "Normal", "fast" to "Fast"), s.waterfallSpeed,
        "Faster scrolling uses a little more battery.") { s.waterfallSpeed = it }
    SettingSlider("Floor", "${s.waterfallFloorDb.toInt()} dB", s.waterfallFloorDb, -130f..-40f,
        subtitle = "Signals quieter than this are drawn as the darkest colour.") { s.waterfallFloorDb = it }
    SettingSlider("Range", "${s.waterfallRangeDb.toInt()} dB", s.waterfallRangeDb, 20f..100f,
        subtitle = "How many dB above the floor map to the brightest colour.") { s.waterfallRangeDb = it }
    SettingChoice("Default zoom", listOf("4000" to "0–4 kHz", "3000" to "0–3 kHz", "2000" to "0–2 kHz", "1000" to "0–1 kHz"),
        s.waterfallDefaultSpanHz.toInt().toString(), "The visible span when the app starts without restoring the last view.") {
        s.waterfallDefaultSpanHz = it.toFloat()
    }
    OutlinedButton({ s.viewStartHz = 0f; s.viewSpanHz = s.waterfallDefaultSpanHz }) { Text("Reset current zoom now") }
}

@Composable
fun DecodingSettings(onBack: () -> Unit) = ScreenPage("Decoding defaults", onBack) {
    val s = settings()
    Hint("Squelch decides when decoded characters are shown. These values are used when you switch to a mode family. Lower values show more (and more noise); slow modes such as Olivia need a lower threshold to lock.")
    SettingSlider("PSK (BPSK and QPSK)", "%.2f".format(s.squelchPsk), s.squelchPsk, 0f..0.8f) { s.squelchPsk = (it * 100).toInt() / 100f }
    SettingSlider("MFSK", "%.2f".format(s.squelchMfsk), s.squelchMfsk, 0f..0.8f) { s.squelchMfsk = (it * 100).toInt() / 100f }
    SettingSlider("Olivia", "%.2f".format(s.squelchOlivia), s.squelchOlivia, 0f..0.8f) { s.squelchOlivia = (it * 100).toInt() / 100f }
    HorizontalDivider()
    SettingSwitch("AFC on by default", "Follow small frequency drift (PSK, MFSK, weather fax)", s.defaultAfc) { s.defaultAfc = it }
    SettingSwitch("Reverse on by default", "For QPSK, MFSK and Olivia when you usually listen on the other sideband", s.defaultReverse) { s.defaultReverse = it }
    Hint("AFC and Reverse defaults apply at startup when \"Restore last state\" is off (Startup and logging); otherwise the last used values are restored.")
}

@Composable
fun PictureSettings(onBack: () -> Unit) = ScreenPage("Pictures", onBack) {
    val s = settings()
    SettingSwitch("Auto-save finished pictures", "Weather fax and MFSK pictures are saved when they complete", s.pictureAutoSave) { s.pictureAutoSave = it }
    SettingChoice("Format", listOf("png" to "PNG (lossless)", "jpeg" to "JPEG (smaller)"), s.pictureFormat) { s.pictureFormat = it }
    SettingSlider("JPEG quality", "${s.pictureJpegQuality.toInt()}", s.pictureJpegQuality, 40f..100f, enabled = s.pictureFormat == "jpeg") {
        s.pictureJpegQuality = it.toInt().toFloat()
    }
    var folder by remember { mutableStateOf(s.pictureFolder) }
    OutlinedTextField(folder, { folder = it; s.pictureFolder = it }, label = { Text("Folder") }, singleLine = true,
        supportingText = { Text("Saved under Pictures/${folder.ifBlank { "RadioModem" }} in your gallery") }, modifier = Modifier.fillMaxWidth())
}

@Composable
fun StationSettings(onBack: () -> Unit) = ScreenPage("Station", onBack) {
    val s = settings()
    var call by remember { mutableStateOf(s.callsign) }
    var name by remember { mutableStateOf(s.operatorName) }
    var loc by remember { mutableStateOf(s.locator) }
    Hint("Shown in the session log headers and, later, in transmit macros and logging.")
    OutlinedTextField(call, { call = Station.normalizeCallsign(it); s.callsign = call }, label = { Text("Callsign") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(name, { name = it; s.operatorName = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val valid = Station.isValidLocator(loc)
    OutlinedTextField(loc, { loc = it.trim(); if (Station.isValidLocator(loc)) s.locator = loc }, label = { Text("Grid locator") }, singleLine = true,
        isError = !valid, supportingText = { Text(if (valid) "e.g. FN31 or FN31pr" else "Use 4, 6 or 8 characters like FN31pr") }, modifier = Modifier.fillMaxWidth())
}

@Composable
fun GeneralSettings(onBack: () -> Unit, showHelp: () -> Unit) = ScreenPage("Startup and logging", onBack) {
    val s = settings()
    SectionLabel("Startup")
    SettingSwitch("Restore last state", "Reopen with the last mode, carrier, squelch, AFC/Reverse, panel layout and waterfall zoom", s.restoreState) { s.restoreState = it }
    SettingSwitch("Reconnect automatically", "Reopen the last audio input and USB radio when they are plugged in. Android may still ask for USB permission.", s.autoReconnect) { s.autoReconnect = it }
    HorizontalDivider()
    SectionLabel("Session logs")
    SettingSwitch("Log received text", "One timestamped file per session, kept in the app (Menu > Logs)", s.logEnabled) { s.logEnabled = it }
    SettingSlider("Sessions kept", "${s.logKeep.toInt()}", s.logKeep, 5f..200f, enabled = s.logEnabled) { s.logKeep = it.toInt().toFloat() }
    HorizontalDivider()
    OutlinedButton(showHelp) { Text("Show getting-started help again") }
}
