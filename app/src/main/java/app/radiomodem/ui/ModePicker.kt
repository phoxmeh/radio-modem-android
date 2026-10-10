package app.radiomodem.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.radiomodem.AudioViewModel
import app.radiomodem.Modems

/**
 * Family buttons (BPSK, QPSK, MFSK, Olivia, WEFAX, SSTV) with the selected family's variants underneath.
 * Used at the top of the Modem drawer and in the toolbar's mode sheet. `onPicked` fires after a variant is chosen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FamilyPicker(audio: AudioViewModel, onPicked: () -> Unit = {}) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    val family = Modems.all[audio.modemId].family
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Modems.families.forEach { f ->
            FilterChip(family == f, { if (f != family) audio.selectFamily(f) }, { Text(f) })
        }
    }
    VariantPicker(audio, family, onPicked)
}

@Composable
private fun VariantPicker(audio: AudioViewModel, family: String, onPicked: () -> Unit) {
    fun pick(id: Int) { audio.selectModem(id); onPicked() }
    when (family) {
        "Olivia" -> {
            val idx = (audio.modemId - Modems.OLIVIA_BASE).coerceIn(0, 24)
            val toneIdx = idx / 5; val bwIdx = idx % 5
            Text("Tones", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Modems.oliviaTones.forEachIndexed { i, t -> FilterChip(toneIdx == i, { pick(Modems.oliviaId(i, bwIdx)) }, { Text("$t") }) }
            }
            Text("Bandwidth (Hz)", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Modems.oliviaBandwidths.forEachIndexed { i, bw -> FilterChip(bwIdx == i, { pick(Modems.oliviaId(toneIdx, i)) }, { Text("$bw") }) }
            }
        }
        "WEFAX" -> {
            Text("Index of cooperation", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(Modems.WEFAX_576 to "IOC 576", Modems.WEFAX_288 to "IOC 288").forEach { (id, label) ->
                    FilterChip(audio.modemId == id, { pick(id) }, { Text(label) })
                }
            }
        }
        else -> {
            Text(if (family == "MFSK") "Variant" else if (family == "SSTV") "Mode" else "Baud rate", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val order = if (family == "SSTV") Modems.sstvPickerOrder.map { Modems.idOf(it) } else Modems.all.indices.filter { Modems.all[it].family == family }
                order.forEach { id ->
                    val m = Modems.all[id]
                    FilterChip(audio.modemId == id, { pick(id) }, { Text(m.name.removePrefix(family)) })
                }
            }
        }
    }
}
