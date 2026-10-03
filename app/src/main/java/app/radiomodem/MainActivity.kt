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
    private var micGranted by androidx.compose.runtime.mutableStateOf(false)
    private val usbAttached = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) { rigModel.autoConnect() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val audio = audioModel
        val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            micGranted = ok
            if (ok && !audio.running) audio.start()
        }
        micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) audio.start()
        else if (AppSettings.get(this).onboardingDone) permission.launch(Manifest.permission.RECORD_AUDIO)  // first run: the help screen explains first
        val pickWav = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { loadWav(audio, uri.lastPathSegment ?: "file", contentResolver.openInputStream(it)?.readBytes()) }
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
            useMic = { audio.stop(); audio.start() },
            requestMic = { permission.launch(Manifest.permission.RECORD_AUDIO) },
            importMemories = { importMem.launch(arrayOf("application/json", "text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
            exportMemoriesJson = { exportJson.launch("saved-frequencies.json") },
            exportMemoriesCsv = { exportCsv.launch("saved-frequencies.csv") },
        )
        androidx.core.content.ContextCompat.registerReceiver(this, usbAttached,
            android.content.IntentFilter(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent { RadioApp(audio, rigModel, memModel, files, micGranted) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // Dev hook: adb shell am start -n app.radiomodem/.MainActivity --es wav /path/readable/by/app.wav
    private fun handleIntent(intent: android.content.Intent) {
        // Optional: --es modem MFSK32 selects a decoder before the file plays (e.g. "BPSK31", "Olivia 16/500").
        intent.getStringExtra("modem")?.let { n -> app.radiomodem.Modems.idOf(n).takeIf { it >= 0 }?.let { audioModel.selectModem(it) } }
        if (intent.hasExtra("carrier")) audioModel.tuneTo(intent.getFloatExtra("carrier", 1000f))  // --ef carrier 1500
        val path = intent.getStringExtra("wav") ?: return
        val f = java.io.File(path)
        android.util.Log.d("RadioModem", "wav extra $path readable=${f.canRead()}")
        if (f.canRead()) loadWav(audioModel, f.name, f.readBytes())
    }

    private fun loadWav(audio: AudioViewModel, name: String, bytes: ByteArray?) {
        val samples = bytes?.let { WavLoader.load(it) }
        android.util.Log.d("RadioModem", "loadWav $name bytes=${bytes?.size} samples=${samples?.size}")
        if (samples == null) Toast.makeText(this, "Unsupported WAV (need 16-bit PCM)", Toast.LENGTH_LONG).show()
        else audio.playSamples(name, samples)
    }

    override fun onResume() {
        super.onResume()
        micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted && !audioModel.running && audioModel.playingFile == null) audioModel.start()
        rigModel.autoConnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbAttached)
    }
}
