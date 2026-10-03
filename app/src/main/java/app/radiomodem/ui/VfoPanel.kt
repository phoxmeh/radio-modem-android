package app.radiomodem.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.radiomodem.rig.Bands
import app.radiomodem.rig.RigViewModel

@Composable
fun VfoPanel(rig: RigViewModel, onRigSetup: () -> Unit, onSave: () -> Unit) = Column(
    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
) {
    var typeDialog by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FrequencyDigits(rig.freqHz, rig::stepFrequency)
        Column {
            IconTextButton("Type") { typeDialog = true }
            IconTextButton("★ Save", onSave)
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            TextButton(onRigSetup, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (rig.connected) "● ${rig.profile.name}" else "○ No rig", fontSize = 13.sp)
            }
            LinearProgressIndicator(progress = { rig.smeter }, Modifier.width(72.dp).padding(end = 8.dp))
        }
    }

    // Band, mode and bandwidth are used occasionally, so they collapse into three buttons; tapping one shows
    // its choices in a single row below, tapping another swaps the row, tapping the active one hides it.
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val band = Bands.of(rig.freqHz)
    val table = rig.bwTable
    val bwLabel = table?.labels?.getOrNull(rig.bwIdx) ?: "–"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectorButton("Band", band?.name ?: "–", open == "band", Modifier.weight(1f)) { open = if (open == "band") null else "band" }
        SelectorButton("Mode", rig.mode.name, open == "mode", Modifier.weight(1f)) { open = if (open == "mode") null else "mode" }
        SelectorButton("BW", bwLabel, open == "bw", Modifier.weight(1f)) { open = if (open == "bw") null else "bw" }
    }
    when (open) {
        "band" -> ChipRow { Bands.all.forEach { b -> FilterChip(band == b, { rig.selectBand(b) }, { Text(b.name) }) } }
        "mode" -> ChipRow { rig.profile.modes.forEachIndexed { i, m -> FilterChip(rig.modeIdx == i, { rig.setMode(i) }, { Text(m.name) }) } }
        "bw" -> if (table != null && table.labels.size > 1) ChipRow {
            table.labels.forEachIndexed { i, l -> FilterChip(rig.bwIdx == i, { rig.setBandwidth(i) }, { Text(l) }) }
        } else Text("${rig.mode.name} has a fixed bandwidth (${table?.labels?.firstOrNull() ?: "n/a"}).", style = MaterialTheme.typography.bodySmall)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("RF power ${rig.powerW} W", Modifier.width(100.dp), style = MaterialTheme.typography.bodySmall)
        Slider(
            rig.powerW.toFloat(), { rig.setPower(it.toInt()) },
            valueRange = rig.profile.powerMin.toFloat()..rig.profile.powerMax.toFloat(),
        )
    }

    if (typeDialog) FrequencyDialog(rig.freqHz, { typeDialog = false }) { rig.setFrequency(it); typeDialog = false }
}

@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) = Row(
    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    horizontalArrangement = Arrangement.spacedBy(6.dp), content = content,
)

@Composable
private fun IconTextButton(label: String, onClick: () -> Unit) =
    TextButton(onClick, contentPadding = PaddingValues(horizontal = 6.dp)) { Text(label, fontSize = 12.sp) }

/** MHz.kHz.Hz readout with a ▲/▼ pair on every digit. */
@Composable
private fun FrequencyDigits(hz: Long, onStep: (Long) -> Unit) {
    val digits = "%08d".format(hz.coerceIn(0, 99_999_999))
    Row(verticalAlignment = Alignment.CenterVertically) {
        var weight = 10_000_000L
        digits.forEachIndexed { i, ch ->
            val w = weight
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("▲", Modifier.clickable { onStep(w) }.padding(horizontal = 5.dp, vertical = 3.dp), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary)
                Text(ch.toString(), fontSize = 30.sp, fontFamily = FontFamily.Monospace,
                    color = if (hz < w && w > 1 && digits.take(i).all { it == '0' } && i < 2) Color.Gray else Color.Unspecified)
                Text("▼", Modifier.clickable { onStep(-w) }.padding(horizontal = 5.dp, vertical = 3.dp), fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (i == 1 || i == 4) Text(".", fontSize = 30.sp, fontFamily = FontFamily.Monospace)
            weight /= 10
        }
    }
}

@Composable
private fun FrequencyDialog(current: Long, onDismiss: () -> Unit, onSet: (Long) -> Unit) {
    var text by remember { mutableStateOf("%.6f".format(current / 1e6)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Frequency (MHz)") },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        },
        confirmButton = {
            TextButton({ text.toDoubleOrNull()?.let { onSet((it * 1e6).toLong()) } }) { Text("Set") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

/** "Band  20m": the label and the current value, highlighted while its row is open. */
@Composable
private fun SelectorButton(label: String, value: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) =
    FilterChip(active, onClick, { Text("$label: $value", maxLines = 1, fontSize = 13.sp) }, modifier)
