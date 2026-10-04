package app.radiomodem.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.radiomodem.AudioViewModel
import app.radiomodem.Palettes
import app.radiomodem.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Live waterfall controls under the picture. The top row is always there: carrier readout with -10/-1/+1/+10 Hz steps
 * (hold to repeat) and a button that centres the view on the carrier. The gear opens the view menu: zoom, jump to the
 * reference centre, automatic levels, colour palette.
 */
@Composable
fun WaterfallControls(vm: AudioViewModel, settings: AppSettings) {
    var typing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            StepButton("−10", Modifier.weight(1f)) { vm.nudge(-10f) }
            StepButton("−1", Modifier.weight(1f)) { vm.nudge(-1f) }
            Surface(
                onClick = { typing = true }, Modifier.weight(2.1f).height(40.dp), shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("${vm.rxHz.toInt()} Hz", fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            StepButton("+1", Modifier.weight(1f)) { vm.nudge(1f) }
            StepButton("+10", Modifier.weight(1f)) { vm.nudge(10f) }
            FilledTonalIconToggleButton(settings.waterfallControls, { settings.waterfallControls = it }, Modifier.size(40.dp)) {
                Text("⚙", fontSize = 18.sp)
            }
        }
        if (settings.waterfallControls) ViewMenu(vm, settings)
    }
    if (typing) CarrierDialog(vm) { typing = false }
}

@Composable
private fun ViewMenu(vm: AudioViewModel, settings: AppSettings) = Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Zoom", style = MaterialTheme.typography.labelMedium)
        OutlinedButton({ vm.zoomBy(1 / 1.5f, vm.rxHz) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("−", fontSize = 18.sp) }
        OutlinedButton({ vm.zoomBy(1.5f, vm.rxHz) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("+", fontSize = 18.sp) }
        OutlinedButton({ vm.zoomFull() }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Full") }
        OutlinedButton({ vm.centerViewOnCarrier() }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Find carrier") }
        OutlinedButton({ vm.tuneTo(vm.referenceHz); vm.centerViewOnCarrier() }, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text("Set ${vm.referenceHz.toInt()}")
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(settings.waterfallAuto, { settings.waterfallAuto = !settings.waterfallAuto }, { Text("Auto level") })
        Palettes.names.forEach { (id, label) ->
            FilterChip(settings.waterfallPalette == id, { settings.waterfallPalette = id }, { Text(label) })
        }
    }
    if (!settings.waterfallAuto) {
        Text("Brightness  ${vm.floorDb.toInt()} dB", style = MaterialTheme.typography.labelMedium)
        // Dragging right lowers the floor, which brightens the picture.
        Slider(-vm.floorDb, { vm.floorDb = -it }, valueRange = 40f..130f)
        Text("Contrast  ${vm.rangeDb.toInt()} dB span", style = MaterialTheme.typography.labelMedium)
        Slider(110f - vm.rangeDb, { vm.rangeDb = 110f - it }, valueRange = 20f..90f)
    }
}

/** Fires once on press, then repeats (faster the longer it is held) until released. */
@Composable
private fun StepButton(text: String, modifier: Modifier, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    var held by remember { mutableStateOf(false) }
    Box(
        modifier.height(40.dp)
            .background(if (held) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    held = true; step()
                    var wait = 380L; var n = 0
                    while (true) {
                        val up = withTimeoutOrNull(wait) { waitForUpOrCancellation() }
                        if (up != null) break
                        step(); n++
                        wait = if (n < 6) 110L else if (n < 20) 60L else 30L
                    }
                    held = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1,
            color = if (held) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun CarrierDialog(vm: AudioViewModel, onDone: () -> Unit) {
    var text by remember { mutableStateOf(vm.rxHz.toInt().toString()) }
    val value = text.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Carrier frequency") },
        text = {
            OutlinedTextField(text, { text = it.filter(Char::isDigit).take(4) }, singleLine = true, suffix = { Text("Hz") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = value == null || value > AudioViewModel.MAX_HZ)
        },
        confirmButton = {
            TextButton({ value?.let { vm.tuneTo(it.toFloat()); vm.centerViewOnCarrier() }; onDone() },
                enabled = value != null && value <= AudioViewModel.MAX_HZ) { Text("Set") }
        },
        dismissButton = { TextButton(onDone) { Text("Cancel") } },
    )
}
