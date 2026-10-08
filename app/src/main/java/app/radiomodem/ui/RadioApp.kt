package app.radiomodem.ui

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.radiomodem.AudioViewModel
import app.radiomodem.Modems
import app.radiomodem.data.MemoryViewModel
import app.radiomodem.rig.RigViewModel
import app.radiomodem.settings.AppSettings
import app.radiomodem.ui.screens.*
import kotlinx.coroutines.launch
import java.io.File

/**
 * Root of the UI: theme, a simple screen stack (main -> settings -> ...), and the main receive screen.
 * Screens are named strings so the stack survives rotation.
 */
@Composable
fun RadioApp(
    audio: AudioViewModel = viewModel(), rig: RigViewModel = viewModel(), mem: MemoryViewModel = viewModel(),
    files: FileActions = FileActions(), micGranted: Boolean = true,
    openRoute: String? = null, onRouteOpened: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val settings = AppSettings.get(ctx)
    val dark = when (settings.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }

    // Keep the system bar icons readable against the app's own theme (it may differ from the system's).
    (ctx as? ComponentActivity)?.let { act ->
        DisposableEffect(dark) {
            val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            act.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            onDispose {}
        }
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var stack by rememberSaveable { mutableStateOf(if (settings.onboardingDone) "main" else "main,help") }
            var logPath by rememberSaveable { mutableStateOf("") }
            // A notification can ask for a screen (e.g. the new Shortwave Radiogram schedule).
            LaunchedEffect(openRoute) { if (openRoute != null) { stack = "main,$openRoute"; onRouteOpened() } }
            val current = stack.substringAfterLast(',')
            fun push(screen: String) { stack = "$stack,$screen" }
            fun pop() { stack = if (',' in stack) stack.substringBeforeLast(',') else "main" }
            BackHandler(enabled = current != "main") { pop() }

            when (current) {
                "settings" -> SettingsHome(::pop, ::push)
                "settings_display" -> DisplaySettings(::pop)
                "settings_waterfall" -> WaterfallSettings(::pop)
                "settings_decoding" -> DecodingSettings(::pop)
                "settings_pictures" -> PictureSettings(::pop)
                "settings_station" -> StationSettings(::pop)
                "settings_general" -> GeneralSettings(::pop) { push("help") }
                "audio" -> AudioSetupScreen(audio, ::pop) { files.useMic() }
                "rig" -> RigListScreen(rig, ::pop) { push("rig_edit:$it") }
                "logs" -> LogsScreen(audio.logger, ::pop) { logPath = it.absolutePath; push("log_view") }
                "log_view" -> if (logPath.isNotEmpty() && File(logPath).exists()) LogViewScreen(File(logPath), audio.logger, ::pop) else LaunchedEffect(Unit) { pop() }
                "schedule" -> ScheduleScreen(viewModel(), ::pop) { station, freq ->
                    // Tune the radio and decoder to this frequency (rig default mode, weather fax, 1900 Hz subcarrier), then show the receiver.
                    recall(
                        app.radiomodem.data.Memory(name = "${station.title} · ${freq.kHz} kHz", freqHz = Math.round(station.dialKHz(freq) * 1000),
                            mode = "", modem = "WEFAX", carrierHz = 1900),  // blank = the rig's default mode and bandwidth
                        rig, audio,
                    )
                    pop()
                }
                "radiogram" -> RadiogramScreen(viewModel(), mem, ::pop) { b ->
                    // The programme is AM on the listed frequency, MFSK32 with the waterfall at 1500 Hz; mode and bandwidth are the rig's defaults.
                    recall(
                        app.radiomodem.data.Memory(name = "Shortwave Radiogram · ${b.transmitter} · ${b.kHz} kHz", freqHz = b.kHz * 1000L,
                            mode = "", modem = "MFSK32", carrierHz = 1500),
                        rig, audio,
                    )
                    pop()
                }
                "about" -> AboutScreen(::pop)
                "help" -> HelpScreen(micGranted, files.requestMic, { push("audio") }, { push("rig") }) { settings.onboardingDone = true; pop() }
                else -> if (current.startsWith("rig_edit:")) RigEditScreen(rig, current.removePrefix("rig_edit:"), ::pop)
                        else MainScreen(audio, rig, mem, files, settings, ::push)
            }
        }
    }
}

/** Left drawer (menu), right drawer (modem), toggleable VFO and waterfall, then the receive feed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    audio: AudioViewModel, rig: RigViewModel, mem: MemoryViewModel, files: FileActions, settings: AppSettings, go: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val left = rememberDrawerState(DrawerValue.Closed)
    val right = rememberDrawerState(DrawerValue.Closed)
    var showMemories by rememberSaveable { mutableStateOf(false) }
    var saveCurrent by remember { mutableStateOf(false) }
    var modeSheet by remember { mutableStateOf(false) }
    var quitDialog by remember { mutableStateOf(false) }
    val micOk = androidx.core.content.ContextCompat.checkSelfPermission(LocalContext.current, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // Back closes an open drawer or sheet before it would leave the screen.
    BackHandler(enabled = left.currentValue == DrawerValue.Open || right.currentValue == DrawerValue.Open) {
        scope.launch { if (left.currentValue == DrawerValue.Open) left.close() else right.close() }
    }

    // Back on the main screen asks instead of leaving: the user may want monitoring to carry on in the background.
    BackHandler(enabled = left.currentValue != DrawerValue.Open && right.currentValue != DrawerValue.Open) { quitDialog = true }
    if (quitDialog) AlertDialog(
        onDismissRequest = { quitDialog = false },
        title = { Text("Leave Radio Modem?") },
        text = {
            Text(if (audio.running) "Keep monitoring carries on listening and decoding in the background (a notification with a Stop button stays on screen). Quit stops listening and closes the app."
                 else "Monitoring is off. Quit closes the app.")
        },
        confirmButton = {
            Row {
                if (audio.running) TextButton({ quitDialog = false; files.keepRunning() }) { Text("Keep monitoring") }
                TextButton({ quitDialog = false; files.quit() }) { Text("Quit") }
            }
        },
        dismissButton = { TextButton({ quitDialog = false }) { Text("Cancel") } },
    )

    val view = LocalView.current
    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Right drawer: outer drawer laid out RTL so it slides in from the right edge.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = right,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    ModalDrawerSheet { ModemDrawer(audio, settings) }
                }
            },
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                ModalNavigationDrawer(
                    drawerState = left,
                    drawerContent = {
                        ModalDrawerSheet {
                            MenuDrawer(audio, rig, close = { scope.launch { left.close() } }, go = go,
                                onMemories = { showMemories = true }, onQuit = { quitDialog = true }, files = files, micOk = micOk)
                        }
                    },
                ) {
                    if (showMemories) MemoriesDialog(mem, rig, audio, files) { showMemories = false }
                    if (saveCurrent) MemoryEditDialog(captureCurrent(rig, audio), mem.groups, true, rig, audio,
                        onSave = { mem.upsert(it); saveCurrent = false }, onDismiss = { saveCurrent = false })
                    if (modeSheet) ModalBottomSheet({ modeSheet = false }) {
                        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
                            Text("Mode", style = MaterialTheme.typography.titleMedium)
                            FamilyPicker(audio) { modeSheet = false }
                        }
                    }
                    Scaffold { pad ->
                        Column(Modifier.padding(pad).fillMaxSize()) {
                            Toolbar(
                                settings, audio,
                                onMenu = { scope.launch { left.open() } },
                                onMode = { modeSheet = true },
                                onClear = { audio.clearText() },
                                onSchedule = { go("schedule") },
                                hasVfo = !rig.profile.audioOnly,
                                onModem = { scope.launch { right.open() } },
                            )
                            if (settings.showVfo && !rig.profile.audioOnly) VfoPanel(rig, onRigSetup = { go("rig") }, onSave = { saveCurrent = true })
                            if (settings.showWaterfall) {
                                Box(Modifier.fillMaxWidth().height(settings.waterfallDp.dp)) { Waterfall(audio) }
                                WaterfallControls(audio, settings)
                                ResizeHandle { settings.waterfallDp = (settings.waterfallDp + it).coerceIn(110f, 480f) }
                            }
                            if (audio.isFax) FaxView(audio, Modifier.weight(1f).fillMaxWidth())
                            else RxFeed(audio, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/**
 * Fixed (never scrolls): menu on the left, modem drawer on the right, everything else between. Labels shorten
 * on narrow screens so both drawer buttons always stay visible.
 */
@Composable
private fun Toolbar(
    settings: AppSettings, audio: AudioViewModel,
    onMenu: () -> Unit, onMode: () -> Unit, onClear: () -> Unit, onSchedule: () -> Unit, hasVfo: Boolean, onModem: () -> Unit,
) = BoxWithConstraints(Modifier.fillMaxWidth()) {
    // Long mode names (e.g. "WEFAX 576", "Olivia 16/500") need the room, so "Waterfall" shortens to "WF" then.
    val compact = maxWidth < 400.dp || Modems.all[audio.modemId].name.length > 8
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onMenu, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("☰", fontSize = 20.sp) }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically) {
            // An audio-only rig has no CAT, so there is nothing for a VFO to control.
            if (hasVfo) FilterChip(settings.showVfo, { settings.showVfo = !settings.showVfo }, { Text("VFO", fontSize = 13.sp, maxLines = 1) })
            FilterChip(settings.showWaterfall, { settings.showWaterfall = !settings.showWaterfall },
                { Text(if (compact) "WF" else "Waterfall", fontSize = 13.sp, maxLines = 1) })
            AssistChip(onMode, { Text(Modems.all[audio.modemId].name + " ▾", fontSize = 13.sp, maxLines = 1) })
            // Weather fax has its own Clear under the picture; here a calendar opens the broadcast schedule instead.
            if (audio.isFax) IconButton(onSchedule) { Icon(Icons.Default.DateRange, contentDescription = "Fax schedule") }
            else TextButton(onClear, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Clear", maxLines = 1) }
        }
        TextButton(onModem, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("⋮", fontSize = 20.sp) }
    }
}

@Composable
private fun ResizeHandle(onDrag: (Float) -> Unit) = Box(
    Modifier.fillMaxWidth().height(14.dp)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> onDrag(dy / density) } },
    contentAlignment = Alignment.Center,
) { Box(Modifier.width(40.dp).height(4.dp).background(MaterialTheme.colorScheme.outline)) }

/** Quick switch between saved rigs, at the top of the menu. */
@Composable
private fun RigPicker(rig: RigViewModel, go: (String) -> Unit, close: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text("Rig", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton({ open = true }, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 14.dp)) {
                Text((if (rig.connected) "● " else "○ ") + rig.config.name, Modifier.weight(1f), maxLines = 1)
                Text("▾")
            }
            DropdownMenu(open, { open = false }) {
                rig.rigs.forEach { r ->
                    DropdownMenuItem({ Text(r.name) }, { open = false; rig.selectRig(r.id) },
                        trailingIcon = { if (r.id == rig.activeId) Text(if (rig.connected) "● connected" else "✓") })
                }
                HorizontalDivider()
                DropdownMenuItem({ Text("Manage rigs…") }, { open = false; close(); go("rig") })
            }
        }
        Text(rig.status, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MenuDrawer(
    audio: AudioViewModel, rig: RigViewModel, close: () -> Unit, go: (String) -> Unit, onMemories: () -> Unit, onQuit: () -> Unit, files: FileActions, micOk: Boolean,
) = Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
    @Composable fun item(label: String, action: () -> Unit) =
        NavigationDrawerItem(label = { Text(label) }, selected = false, onClick = { close(); action() })
    Text("Radio Modem", style = MaterialTheme.typography.titleLarge)
    val playingFile = audio.playingFile
    Text(
        if (playingFile == null) "Input: live audio" else "Input: file $playingFile",
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp),
    )
    RigPicker(rig, go, close)
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    // Listening is on or off here; turning it off and on again also re-initialises the audio input after a hiccup.
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Monitoring", style = MaterialTheme.typography.titleSmall)
            Text(
                when { audio.failed -> "Audio input failed"; audio.running -> "Listening"; else -> "Stopped" },
                style = MaterialTheme.typography.bodySmall,
                color = if (audio.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (audio.running) TextButton({ audio.restartMonitoring() }) { Text("Restart") }
        Switch(audio.running, { if (it) { if (!micOk) files.requestMic() else audio.startMonitoring() } else audio.stopMonitoring() })
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    item("Saved frequencies", onMemories)
    item("Fax schedule") { go("schedule") }
    item("Shortwave Radiogram") { go("radiogram") }
    item("Audio setup") { go("audio") }
    item("Rig setup") { go("rig") }
    item("Session logs") { go("logs") }
    item("Settings") { go("settings") }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    item("Getting started") { go("help") }
    item("About") { go("about") }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    item("Quit…") { onQuit() }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("Test tools", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
    item("Play WAV file…") { files.playWav() }
    if (playingFile != null) item("Use live audio input") { files.useMic() }
}

/** Mode buttons first, then only the settings that mean something for the selected mode. */
@Composable
private fun ModemDrawer(audio: AudioViewModel, settings: AppSettings) = Column(
    Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(8.dp),
) {
    Text("Modem", style = MaterialTheme.typography.titleLarge)
    FamilyPicker(audio)
    val family = Modems.all[audio.modemId].family
    HorizontalDivider()

    when (family) {
        "WEFAX" -> FaxControls(audio, settings)
        else -> {
            if (family == "MFSK") {
                if (audio.modemId in Modems.PICTURE_MODES)
                    Hint("Picture transmissions are announced in the text and appear inline as they arrive. Tap one to zoom. Saving is under Settings > Pictures.")
                else Hint("${Modems.all[audio.modemId].name} has no picture option; use MFSK16, 32, 64 or 128 for pictures.")
            }
            TrackingSwitches(audio, family)
            Hint(
                when (family) {
                    "Olivia" -> "Olivia tracks small offsets itself. Turn Reverse on when the sender is on the opposite sideband."
                    "BPSK" -> "BPSK is the same on either sideband, so there is no Reverse. AFC follows slow drift."
                    else -> "If a decoder shows only garbage on a clear signal, try Reverse; QPSK on LSB needs it."
                }
            )
            HorizontalDivider()
            Text("${Modems.all[audio.modemId].name} · carrier ${audio.rxHz.toInt()} Hz · ${Modems.all[audio.modemId].bandwidthHz.toInt()} Hz wide")
            Text("Signal quality")
            LinearProgressIndicator(progress = { audio.quality.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            Text("Squelch: ${"%.2f".format(audio.squelch)}")
            Slider(audio.squelch, { audio.updateSquelch(it) }, valueRange = 0f..0.8f)
            TextButton({ audio.updateSquelch(settings.squelchFor(family)) }) { Text("Reset squelch to default") }
        }
    }

}

/** AFC and Reverse, shown only where the mode supports them. */
@Composable
private fun TrackingSwitches(audio: AudioViewModel, family: String) {
    if (Modems.supportsAfc(family)) Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(audio.afc, { audio.updateAfc(it) })
        Text("  AFC (follow drift)", style = MaterialTheme.typography.bodySmall)
    }
    if (Modems.supportsReverse(family)) Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(audio.reverse, { audio.updateReverse(it) })
        Text("  Reverse (other sideband)", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun FaxControls(audio: AudioViewModel, settings: AppSettings) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Lines per minute", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val default = if (audio.modemId == Modems.WEFAX_288) 60 else 120
        listOf(60, 90, 120, 240).forEach { lpm ->
            FilterChip((audio.faxLpm == 0 && lpm == default) || audio.faxLpm == lpm, { audio.updateFaxLpm(lpm) }, { Text("$lpm") })
        }
    }
    Text("Input filter", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val current = if (audio.faxFilter >= 0) audio.faxFilter else if (audio.modemId == Modems.WEFAX_288) 2 else 1
        listOf("Narrow", "Medium", "Wide").forEachIndexed { i, n -> FilterChip(current == i, { audio.updateFaxFilter(i) }, { Text(n) }) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(settings.faxAutoSlant, { audio.updateFaxAutoSlant(it) })
        Text("  Auto slant", style = MaterialTheme.typography.bodySmall)
    }
    if (settings.faxAutoSlant) {
        Text("Stop adjusting after", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(100 to "100", 200 to "200", 400 to "400", 800 to "800", 2800 to "Never").forEach { (n, label) ->
                FilterChip(settings.faxAutoStopLines.toInt() == n, { audio.updateFaxAutoStop(n) }, { Text(label) })
            }
        }
        Hint("Auto slant measures the line length from the phasing lines, then refines it from the picture at 48, 96, 192 … lines. Past the limit it stops, so a long picture cannot be skewed by later content. Slant, shift, AFC, Skip APT and Clear are under the picture.")
    }
    if (audio.faxMeasuredLpm > 0) Hint("Measured from phasing: ${"%.1f".format(audio.faxMeasuredLpm)} LPM")
    Hint("Tune USB at the station's assigned frequency minus 1.9 kHz; the saved weather-fax entries already do. Carrier ${audio.rxHz.toInt()} Hz.")
    }
