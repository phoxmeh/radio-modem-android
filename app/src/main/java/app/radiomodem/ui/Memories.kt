package app.radiomodem.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.radiomodem.AudioViewModel
import app.radiomodem.Modems
import app.radiomodem.data.Memory
import app.radiomodem.data.MemoryViewModel
import app.radiomodem.rig.RigViewModel
import app.radiomodem.ui.screens.DropdownField


/** Applies a saved frequency: rig mode/frequency/bandwidth, then the decoder and its audio carrier. */
fun recall(m: Memory, rig: RigViewModel, audio: AudioViewModel) {
    // Blank mode / bandwidth mean the rig's own defaults from Rig setup.
    rig.applyTuningDefaults(m.mode, m.bandwidth, m.freqHz)
    Modems.idOf(m.modem).takeIf { it >= 0 }?.let { audio.selectModem(it) }
    m.carrierHz?.let { audio.tuneTo(it.toFloat()) }
}

/** Snapshot of the current radio and decoder state, ready to name and save. */
fun captureCurrent(rig: RigViewModel, audio: AudioViewModel): Memory {
    val table = rig.bwTable
    val mhz = "%.3f".format(rig.freqHz / 1e6)
    return Memory(
        name = "$mhz ${rig.mode.name}", freqHz = rig.freqHz, mode = rig.mode.name,
        bandwidth = table?.takeIf { it.labels.size > 1 }?.labels?.getOrNull(rig.bwIdx),
        modem = Modems.all[audio.modemId].name, carrierHz = audio.rxHz.toInt(),
    )
}

private fun describe(m: Memory): String = buildString {
    append("%d.%03d.%03d".format(m.freqHz / 1_000_000, (m.freqHz / 1000) % 1000, m.freqHz % 1000))
    append(if (m.mode.isBlank()) " · rig default mode" else " · ${m.mode}")
    m.bandwidth?.let { append(" · $it Hz") }
    m.modem?.let { append(" · $it"); m.carrierHz?.let { c -> append(" @ $c Hz") } }
}

@Composable
fun MemoriesDialog(
    mem: MemoryViewModel, rig: RigViewModel, audio: AudioViewModel, files: FileActions,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<Memory?>(null) }
    var menu by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onDismiss) { Text("Close") }
                    Text("Saved frequencies", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton({ editing = captureCurrent(rig, audio) }) { Text("★ Save current") }
                    Box {
                        TextButton({ menu = true }) { Text("⋮") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Import (JSON or CSV)…") }, { menu = false; files.importMemories() })
                            DropdownMenuItem({ Text("Export JSON…") }, { menu = false; files.exportMemoriesJson() })
                            DropdownMenuItem({ Text("Export CSV…") }, { menu = false; files.exportMemoriesCsv() })
                            DropdownMenuItem({ Text("Restore starter lists") }, {
                                menu = false; info = "Restored ${mem.restoreStarters()} starter entries"
                            })
                        }
                    }
                }
                OutlinedTextField(mem.query, { mem.query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Search name, MHz, mode, notes") })
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(mem.groupFilter == null, { mem.groupFilter = null }, { Text("All") })
                    mem.groups.forEach { g -> FilterChip(mem.groupFilter == g, { mem.groupFilter = g }, { Text(g) }) }
                }
                info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                val list = mem.visible
                if (list.isEmpty()) Text("Nothing saved yet. Tap “Save current” to store the radio's present frequency and mode.",
                    Modifier.padding(16.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(list, key = { it.id }) { m ->
                        MemoryRow(m, onRecall = { recall(m, rig, audio); onDismiss() }, onEdit = { editing = m },
                            onDelete = { mem.delete(m) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    editing?.let { m ->
        MemoryEditDialog(m, mem.groups, isNew = mem.items.none { it.id == m.id }, rig, audio,
            onSave = { mem.upsert(it); editing = null }, onDismiss = { editing = null })
    }
}

@Composable
private fun MemoryRow(m: Memory, onRecall: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onRecall).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(m.name, style = MaterialTheme.typography.bodyLarge)
            Text(describe(m), style = MaterialTheme.typography.bodySmall)
            if (m.notes.isNotBlank()) Text(m.notes, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (m.group.isNotEmpty()) Text(m.group, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary)
        }
        Box {
            TextButton({ menu = true }) { Text("⋮") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Edit") }, { menu = false; onEdit() })
                DropdownMenuItem({ Text("Delete") }, { menu = false; onDelete() })
            }
        }
    }
}

@Composable
fun MemoryEditDialog(
    initial: Memory, groups: List<String>, isNew: Boolean, rig: RigViewModel, audio: AudioViewModel,
    onSave: (Memory) -> Unit, onDismiss: () -> Unit,
) {
    var m by remember { mutableStateOf(initial) }
    var mhz by remember { mutableStateOf("%.6f".format(initial.freqHz / 1e6)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Save frequency" else "Edit frequency") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(m.name, { m = m.copy(name = it) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(m.group, { m = m.copy(group = it) }, label = { Text("Group") }, singleLine = true)
                if (groups.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.forEach { g -> AssistChip({ m = m.copy(group = g) }, { Text(g) }) }
                }
                OutlinedTextField(mhz, { mhz = it }, label = { Text("Dial frequency (MHz)") }, singleLine = true)
                Text(describe(m.copy(freqHz = mhz.toDoubleOrNull()?.let { Math.round(it * 1e6) } ?: m.freqHz)),
                    style = MaterialTheme.typography.bodySmall)
                TextButton({ val c = captureCurrent(rig, audio); m = c.copy(id = m.id, name = m.name, group = m.group, notes = m.notes, seedKey = m.seedKey); mhz = "%.6f".format(c.freqHz / 1e6) }) {
                    Text("Use current radio settings")
                }
                // Blank = whatever the active rig is set to use when tuning (Rig setup > When tuning).
                val modes = rig.profile.modes.map { it.name }
                DropdownField("Mode", listOf("" to "Rig default (${rig.config.defaultMode.ifEmpty { "unchanged" }})") + modes.map { it to it }, m.mode) {
                    m = m.copy(mode = it, bandwidth = null)
                }
                val table = rig.profile.modes.firstOrNull { it.name == m.mode }?.let { rig.profile.bandwidths[it.bwTable] }
                if (m.mode.isNotEmpty() && table != null && table.labels.size > 1)
                    DropdownField("Bandwidth", listOf("" to "Rig default") + table.labels.map { it to "$it Hz" }, m.bandwidth ?: "") {
                        m = m.copy(bandwidth = it.ifEmpty { null })
                    }
                OutlinedTextField(m.notes, { m = m.copy(notes = it) }, label = { Text("Notes") }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton({
                val f = mhz.toDoubleOrNull()?.let { Math.round(it * 1e6) }
                if (f != null && m.name.isNotBlank()) onSave(m.copy(freqHz = f, name = m.name.trim(), group = m.group.trim()))
            }, enabled = m.name.isNotBlank() && mhz.toDoubleOrNull() != null) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
