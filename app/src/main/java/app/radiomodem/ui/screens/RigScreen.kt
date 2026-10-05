package app.radiomodem.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.radiomodem.rig.RigConfig
import app.radiomodem.rig.RigProfiles
import app.radiomodem.rig.RigViewModel

private val BAUDS = listOf(4800, 9600, 19200, 38400, 57600, 115200)

/** Saved rigs: pick or add one at the top, then its model, connection, serial details and tuning defaults. */
@Composable
fun RigSetupScreen(rig: RigViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = rig.config
    var drivers by remember { mutableStateOf(rig.drivers()) }
    var selected by remember(c.id) { mutableIntStateOf(drivers.indexOfFirst { it.device.vendorId == c.usbVendor && it.device.productId == c.usbProduct }.coerceAtLeast(0)) }
    var port by remember(c.id) { mutableIntStateOf(c.usbPort) }
    var advanced by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val models = remember { RigProfiles.available(ctx) }
    val baud = c.baud.takeIf { it > 0 } ?: rig.profile.serial.baud

    ScreenPage("Rigs", onBack) {
        SectionLabel("Saved rigs")
        DropdownField("Active rig", rig.rigs.map { it.id to it.name }, rig.activeId) { rig.selectRig(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ rig.addRig() }) { Text("New rig") }
            OutlinedButton({ confirmDelete = true }, enabled = rig.rigs.size > 1) { Text("Delete") }
        }
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Text(rig.status, Modifier.padding(12.dp))
        }
        HorizontalDivider()

        SectionLabel("Radio")
        OutlinedTextField(c.name, { rig.updateConfig(c.copy(name = it)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        DropdownField("Model", models, c.profileId) { rig.updateConfig(c.copy(profileId = it, defaultBw = "", defaultMode = if (it == c.profileId) c.defaultMode else "USB")) }
        Hint("Modes, bandwidths and commands come from flrig's ${rig.profile.name} definition.")
        HorizontalDivider()

        SectionLabel("When tuning")
        Hint("Used when you tap a saved frequency or a fax schedule entry, so the radio is set the way this model needs. A saved frequency can name its own mode instead.")
        DropdownField("Mode", listOf("" to "Leave the radio's mode alone") + rig.profile.modes.map { it.name to it.name }, c.defaultMode) {
            rig.updateConfig(c.copy(defaultMode = it, defaultBw = ""))
        }
        val table = rig.profile.modes.firstOrNull { it.name == c.defaultMode }?.let { rig.profile.bandwidths[it.bwTable] }
        DropdownField("Bandwidth", listOf("" to "Leave the radio's filter alone") + (table?.labels ?: emptyList()).map { it to "$it Hz" }, c.defaultBw,
            enabled = table != null && table.labels.size > 1) { rig.updateConfig(c.copy(defaultBw = it)) }
        HorizontalDivider()

        SectionLabel("Connection")
        if (drivers.isEmpty()) {
            Hint("No USB serial device found. Plug the radio's USB cable (or a CAT adapter) into the phone through an OTG adapter, then tap Rescan.")
        } else {
            drivers.forEachIndexed { i, d ->
                FilterChip(selected == i, { selected = i; port = 0 }, {
                    Text("${d.device.productName ?: d.javaClass.simpleName} (${d.ports.size} port${if (d.ports.size > 1) "s" else ""})")
                })
            }
            val ports = drivers.getOrNull(selected)?.ports?.size ?: 1
            if (ports > 1) {
                Text("Port")
                Hint("FT-891: port 0 is the Enhanced (CAT) port.")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(ports) { FilterChip(port == it, { port = it }, { Text("$it") }) }
                }
            }
        }
        DropdownField("CAT rate (baud)", (BAUDS + baud).distinct().sorted().map { it.toString() to if (it == rig.profile.serial.baud) "$it (model default)" else "$it" },
            baud.toString()) { rig.updateConfig(c.copy(baud = it.toInt())) }
        Hint("Must match the radio's CAT RATE menu setting.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rig.connected) Button({ rig.disconnect() }) { Text("Disconnect") }
            else Button({ drivers.getOrNull(selected)?.let { rig.connect(it, port, baud) } }, enabled = drivers.isNotEmpty()) { Text("Connect") }
            OutlinedButton({ drivers = rig.drivers() }) { Text("Rescan") }
        }
        Hint("This rig's adapter is remembered and reopened automatically when plugged in (Settings > Startup and logging).")
        HorizontalDivider()

        TextButton({ advanced = !advanced }, contentPadding = PaddingValues(0.dp)) { Text(if (advanced) "Serial details ▾" else "Serial details ▸") }
        if (advanced) SerialDetails(c) { rig.updateConfig(it) }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${c.name}?") },
        text = { Text("The rig and its remembered frequency are removed. Saved frequencies are not affected.") },
        confirmButton = { TextButton({ confirmDelete = false; rig.deleteRig(c.id) }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}

/** Port settings that differ between radios and adapters; the defaults suit most Yaesu radios. Applied on the next Connect. */
@Composable
private fun SerialDetails(c: RigConfig, change: (RigConfig) -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownField("Data bits", listOf("7" to "7", "8" to "8"), c.dataBits.toString(), Modifier.weight(1f)) { change(c.copy(dataBits = it.toInt())) }
        DropdownField("Stop bits", listOf("1" to "1", "2" to "2"), c.stopBits.toString(), Modifier.weight(1f)) { change(c.copy(stopBits = it.toInt())) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DropdownField("Parity", listOf("0" to "None", "2" to "Even", "1" to "Odd"), c.parity.toString(), Modifier.weight(1f)) { change(c.copy(parity = it.toInt())) }
        DropdownField("Flow control", listOf("none" to "None", "rtscts" to "RTS/CTS", "xonxoff" to "XON/XOFF"), c.flow, Modifier.weight(1f)) { change(c.copy(flow = it)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val lines = listOf("leave" to "Leave alone", "low" to "Low", "high" to "High")
        DropdownField("DTR when opened", lines, c.dtr, Modifier.weight(1f)) { change(c.copy(dtr = it)) }
        DropdownField("RTS when opened", lines, c.rts, Modifier.weight(1f)) { change(c.copy(rts = it)) }
    }
    Hint("DTR and RTS key the transmitter on some radios, so they are left alone unless this radio needs a fixed level.")
}
