package app.radiomodem

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import app.radiomodem.data.MemoryViewModel
import app.radiomodem.settings.AppSettings
import app.radiomodem.ui.FileActions
import app.radiomodem.rig.RigViewModel
import app.radiomodem.ui.RadioApp

class MainActivity : ComponentActivity() {
    private val audioModel by viewModels<AudioViewModel>()
    private val memModel by viewModels<MemoryViewModel>()
    private val rigModel by viewModels<RigViewModel>()
    /** A screen to open on arrival, e.g. from a notification (intent extra "route"). */
    private var openRoute by androidx.compose.runtime.mutableStateOf<String?>(null)
    private var micGranted by androidx.compose.runtime.mutableStateOf(false)
    private val usbAttached = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) { rigModel.autoConnect() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        app.radiomodem.radiogram.RadiogramPoller.schedule(this)  // keeps the weekly-post check queued (a force-stop clears it)
        val audio = audioModel
        val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            micGranted = ok
            if (ok && audio.monitorWanted && !audio.running) audio.startMonitoring()
        }
        micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted && audio.monitorWanted) audio.startMonitoring()
        else if (AppSettings.get(this).onboardingDone) permission.launch(Manifest.permission.RECORD_AUDIO)  // first run: the help screen explains first
        val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        if (android.os.Build.VERSION.SDK_INT >= 33 && !AppSettings.get(this).notifAsked &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && AppSettings.get(this).onboardingDone) {
            AppSettings.get(this).notifAsked = true  // the monitoring notification (with Stop) needs this; ask once
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val pickWav = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { playWav(audio, uri.lastPathSegment ?: "file") { contentResolver.openInputStream(it) ?: throw java.io.IOException("cannot open") } }
        }
        if (savedInstanceState == null) handleIntent(intent)
        val importMem = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            val n = runCatching { memModel.importText(contentResolver.openInputStream(uri)!!.bufferedReader().readText()) }
            Toast.makeText(this, n.fold({ "Imported $it new entries" }, { "Import failed: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
        val exportJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { contentResolver.openOutputStream(it)?.use { o -> o.write(memModel.exportJson().toByteArray()) } }
        }
        val exportCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let { contentResolver.openOutputStream(it)?.use { o -> o.write(memModel.exportCsv().toByteArray()) } }
        }
        val files = FileActions(
            playWav = { pickWav.launch(arrayOf("audio/*")) },
            useMic = { audio.stop(); audio.startMonitoring() },
            keepRunning = { moveTaskToBack(true) },
            quit = { audio.stopMonitoring(); finishAndRemoveTask() },
            requestMic = { permission.launch(Manifest.permission.RECORD_AUDIO) },
            importMemories = { importMem.launch(arrayOf("application/json", "text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
            exportMemoriesJson = { exportJson.launch("saved-frequencies.json") },
            exportMemoriesCsv = { exportCsv.launch("saved-frequencies.csv") },
        )
        androidx.core.content.ContextCompat.registerReceiver(this, usbAttached,
            android.content.IntentFilter(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent { RadioApp(audio, rigModel, memModel, files, micGranted, openRoute) { openRoute = null } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // Dev hook: adb shell am start -n app.radiomodem/.MainActivity --es wav /path/readable/by/app.wav
    private fun handleIntent(intent: android.content.Intent) {
        intent.getStringExtra("route")?.let { openRoute = it }
        // Optional: --es modem MFSK32 selects a decoder before the file plays (e.g. "BPSK31", "Olivia 16/500").
        intent.getStringExtra("modem")?.let { n -> app.radiomodem.Modems.idOf(n).takeIf { it >= 0 }?.let { audioModel.selectModem(it) } }
        if (intent.hasExtra("carrier")) audioModel.tuneTo(intent.getFloatExtra("carrier", 1000f))  // --ef carrier 1500
        val path = intent.getStringExtra("wav") ?: return
        val f = java.io.File(path)
        android.util.Log.d("RadioModem", "wav extra $path readable=${f.canRead()}")
        if (f.canRead()) playWav(audioModel, f.name) { f.inputStream() }
    }

    private fun playWav(audio: AudioViewModel, name: String, open: () -> java.io.InputStream) {
        audio.playWav(name, open) { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() } }
    }

    override fun onResume() {
        super.onResume()
        micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted && audioModel.monitorWanted && !audioModel.running && audioModel.playingFile == null) audioModel.startMonitoring()
        rigModel.autoConnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbAttached)
    }
}
