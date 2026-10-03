package app.radiomodem.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.radiomodem.AudioViewModel
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private fun db(linear: Float) = if (linear <= 1e-6f) -120f else 20f * log10(linear)

@Composable
fun AudioSetupScreen(audio: AudioViewModel, onBack: () -> Unit, useLive: () -> Unit) = ScreenPage("Audio setup", onBack) {
    audio.deviceListVersion  // refresh when devices come and go
    val devices = audio.inputDevices()
    val preferred = audio.preferredDevice

    audio.playingFile?.let {
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Playing file: $it")
                Hint("Live audio input is paused while a file plays.")
                OutlinedButton(useLive) { Text("Use live audio input") }
            }
        }
    }

    SectionLabel("Input level")
    val peakDb = db(audio.inputPeak)
    LinearProgressIndicator(progress = { ((peakDb + 60f) / 60f).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(10.dp),
        color = if (audio.inputPeak > 0.97f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    Text("Peak ${"%.0f".format(peakDb)} dBFS · RMS ${"%.0f".format(db(audio.inputRms))} dBFS")
    Hint(
        when {
            audio.inputPeak > 0.97f -> "Clipping. Turn the radio's USB audio level or the gain below down."
            audio.inputPeak < 0.003f -> "No signal. Check the source below and that the radio is producing audio."
            else -> "Aim for peaks between about -20 and -6 dBFS on a typical signal."
        }
    )

    val gainDb = 20f * log10(audio.inputGain)
    SettingSlider("Input gain", "${if (gainDb >= 0) "+" else ""}${"%.0f".format(gainDb)} dB", gainDb, -12f..24f,
        subtitle = "Applied in software to all decoders and the waterfall.") { audio.inputGain = 10f.pow(it.roundToInt() / 20f) }

    HorizontalDivider()
    SectionLabel("Input device")
    Hint("Capturing from: ${audio.inputDeviceName.ifEmpty { if (audio.failed) "nothing (could not open the audio input)" else "…" }}" +
        (if (audio.running) " at ${app.radiomodem.Dsp.sampleRate()} Hz" else ""))
    DeviceRow("System default", "Let Android choose (usually the phone microphone, or a plugged-in headset)", preferred == null) { audio.selectInputDevice(null) }
    devices.forEach { d ->
        DeviceRow(audio.describe(d), "id ${d.id}", preferred?.id == d.id) { audio.selectInputDevice(d) }
    }
    Hint("Your choice is remembered by device name, and reselected automatically when it is plugged in again (turn this off under Settings > Startup and logging).")
    OutlinedButton({ audio.restartLive() }) { Text("Restart audio input") }
}

@Composable
private fun DeviceRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(subtitle)
        }
    }
