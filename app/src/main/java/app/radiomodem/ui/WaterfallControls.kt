package app.radiomodem.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
    // Everything fits across the screen: no horizontal scrolling anywhere in this menu.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val pad = PaddingValues(horizontal = 4.dp)
        OutlinedButton({ vm.zoomBy(1 / 1.5f, vm.rxHz) }, Modifier.weight(0.8f), contentPadding = pad) { Text("−", fontSize = 18.sp) }
        OutlinedButton({ vm.zoomBy(1.5f, vm.rxHz) }, Modifier.weight(0.8f), contentPadding = pad) { Text("+", fontSize = 18.sp) }
        OutlinedButton({ vm.zoomFull() }, Modifier.weight(1f), contentPadding = pad) { Text("Full", maxLines = 1) }
        OutlinedButton({ vm.centerViewOnCarrier() }, Modifier.weight(1.3f), contentPadding = pad) { Text("Center", maxLines = 1) }
        OutlinedButton({ vm.tuneTo(vm.referenceHz); vm.centerViewOnCarrier() }, Modifier.weight(1.5f), contentPadding = pad) {
            Text("Set ${vm.referenceHz.toInt()}", maxLines = 1)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PaletteMenu(settings)
        TextButton({ settings.waterfallLevels = !settings.waterfallLevels }) {
            Text(if (settings.waterfallLevels) "Levels ▾" else "Levels ▸")
        }
        Spacer(Modifier.weight(1f))
        Text("Auto", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(8.dp))
        Switch(settings.waterfallAuto, { settings.waterfallAuto = it })
    }
    // The sliders stay put; touching one takes over from Auto level.
    val brightness = remember { MutableInteractionSource() }
    val contrast = remember { MutableInteractionSource() }
    val touched = brightness.collectIsPressedAsState().value || brightness.collectIsDraggedAsState().value ||
        contrast.collectIsPressedAsState().value || contrast.collectIsDraggedAsState().value
    LaunchedEffect(touched) { if (touched) settings.waterfallAuto = false }
    if (settings.waterfallLevels) {
        Text("Brightness  ${vm.floorDb.toInt()} dB", style = MaterialTheme.typography.labelMedium)
        // Dragging right lowers the floor, which brightens the picture.
        Slider(-vm.floorDb, { vm.floorDb = -it }, interactionSource = brightness, valueRange = 40f..130f)
        Text("Contrast  ${vm.rangeDb.toInt()} dB span", style = MaterialTheme.typography.labelMedium)
        Slider(110f - vm.rangeDb, { vm.rangeDb = 110f - it }, interactionSource = contrast, valueRange = 20f..90f)
    }
}

@Composable
private fun PaletteMenu(settings: AppSettings) {
    var open by remember { mutableStateOf(false) }
    val current = Palettes.names.firstOrNull { it.first == settings.waterfallPalette }?.second ?: "Classic"
    Box {
        OutlinedButton({ open = true }, contentPadding = PaddingValues(start = 14.dp, end = 8.dp)) {
            Text("Colours: $current  ▾")
        }
        DropdownMenu(open, { open = false }) {
            Palettes.names.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = {
                        Canvas(Modifier.size(width = 56.dp, height = 14.dp)) {
                            val n = 28
                            for (k in 0 until n) drawRect(Color(Palettes.color(id, k / (n - 1f))), Offset(size.width * k / n, 0f), Size(size.width / n + 1f, size.height))
                        }
                    },
                    trailingIcon = { if (id == settings.waterfallPalette) Text("✓") },
                    onClick = { settings.waterfallPalette = id; open = false },
                )
            }
        }
    }
}

/** Fires once on press, then repeats (faster the longer it is held) until released. */
@Composable
internal fun StepButton(text: String, modifier: Modifier, onStep: () -> Unit) {
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
