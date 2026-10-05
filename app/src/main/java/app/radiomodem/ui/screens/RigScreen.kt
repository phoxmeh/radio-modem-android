package app.radiomodem.ui.screens

import androidx.compose.foundation.clickable
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

/** Every saved rig as a row; tap one to open its settings. */
@Composable
fun RigListScreen(rig: RigViewModel, onBack: () -> Unit, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val models = remember { RigProfiles.available(ctx) }
    var adding by remember { mutableStateOf(false) }

    ScreenPage("Rigs", onBack) {
        Hint("Tap a rig to change its settings. The rig marked Active is the one the app is using now; you can also switch from the top of the main menu.")
        rig.rigs.forEach { r ->
            val model = RigProfiles.load(ctx, r.profileId)
            val active = r.id == rig.activeId
            Surface(
                onClick = { open(r.id) }, shape = MaterialTheme.shapes.medium, tonalElevation = if (active) 6.dp else 1.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium)
                        Text(model.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (active) Text(
                            if (model.audioOnly) "Active · audio only" else if (rig.connected) "Active · connected" else "Active · not connected",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Button({ adding = true }, Modifier.fillMaxWidth()) { Text("Add a rig") }
    }

    if (adding) AlertDialog(
        onDismissRequest = { adding = false },
        title = { Text("What kind of rig?") },
        text = {
            Column {
                models.forEach { (id, name) ->
                    Text(name, Modifier.fillMaxWidth().clickable { adding = false; open(rig.addRig(id)) }.padding(vertical = 14.dp),
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton({ adding = false }) { Text("Cancel") } },
    )
}

/** One rig's settings: name and model, tuning defaults, connection and serial details. */
@Composable
fun RigEditScreen(rig: RigViewModel, id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c = rig.rigs.firstOrNull { it.id == id }
    if (c == null) { LaunchedEffect(Unit) { onBack() }; return }
    val model = RigProfiles.load(ctx, c.profileId)
    val active = id == rig.activeId
    var drivers by remember { mutableStateOf(rig.drivers()) }
    var selected by remember(id) { mutableIntStateOf(drivers.indexOfFirst { it.device.vendorId == c.usbVendor && it.device.productId == c.usbProduct }.coerceAtLeast(0)) }
    var port by remember(id) { mutableIntStateOf(c.usbPort) }
    var advanced by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val models = remember { RigProfiles.available(ctx) }
    val baud = c.baud.takeIf { it > 0 } ?: model.serial.baud

    ScreenPage(c.name, onBack) {
        if (active) {
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Text("Active rig. ${rig.status}", Modifier.padding(12.dp))
            }
        } else {
            Button({ rig.selectRig(id) }, Modifier.fillMaxWidth()) { Text("Use this rig") }
        }

        OutlinedTextField(c.name, { rig.updateConfig(c.copy(name = it)) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        ChoiceRow("Model", models, c.profileId) { rig.updateConfig(c.copy(profileId = it, defaultBw = "", defaultMode = if (RigProfiles.load(ctx, it).audioOnly) "" else "USB")) }
        if (model.audioOnly) {
            Hint("The app listens to the radio's audio and cannot change its frequency or mode. The frequency you enter or recall is a label: tune the radio to it yourself. Connect the radio's headphone or line output to the phone (or a USB sound card) in Audio setup.")
        } else {
            Hint("Modes, bandwidths and commands come from flrig's ${model.name} definition.")
            HorizontalDivider()

            SectionLabel("When tuning")
            Hint("Used when you tap a saved frequency or a fax schedule entry, so the radio is set the way this model needs. A saved frequency can name its own mode instead.")
            ChoiceRow("Mode", listOf("" to "Leave the radio's mode alone") + model.modes.map { it.name to it.name }, c.defaultMode) {
                rig.updateConfig(c.copy(defaultMode = it, defaultBw = ""))
            }
            val table = model.modes.firstOrNull { it.name == c.defaultMode }?.let { model.bandwidths[it.bwTable] }
            ChoiceRow("Bandwidth", listOf("" to "Leave the radio's filter alone") + (table?.labels ?: emptyList()).map { it to "$it Hz" }, c.defaultBw,
                enabled = table != null && table.labels.size > 1,
                subtitle = if (table == null || table.labels.size <= 1) "${c.defaultMode.ifEmpty { "This mode" }} has a fixed width" else null) {
                rig.updateConfig(c.copy(defaultBw = it))
            }
            HorizontalDivider()

            SectionLabel("Connection")
            if (!active) {
                Hint("Make this the active rig to connect it.")
            } else {
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
            }
            ChoiceRow("CAT rate (baud)", (BAUDS + baud).distinct().sorted().map { it.toString() to if (it == model.serial.baud) "$it (model default)" else "$it" },
                baud.toString(), subtitle = "Must match the radio's CAT RATE menu setting") { rig.updateConfig(c.copy(baud = it.toInt())) }
            if (active) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rig.connected) Button({ rig.disconnect() }) { Text("Disconnect") }
                    else Button({ drivers.getOrNull(selected)?.let { rig.connect(it, port, baud) } }, enabled = drivers.isNotEmpty()) { Text("Connect") }
                    OutlinedButton({ drivers = rig.drivers() }) { Text("Rescan") }
                }
                Hint("This rig's adapter is remembered and reopened automatically when plugged in (Settings > Startup and logging).")
            }
            HorizontalDivider()

            TextButton({ advanced = !advanced }, contentPadding = PaddingValues(0.dp)) { Text(if (advanced) "Serial details ▾" else "Serial details ▸") }
            if (advanced) SerialDetails(c) { rig.updateConfig(it) }
        }

        HorizontalDivider()
        TextButton({ confirmDelete = true }, enabled = rig.rigs.size > 1) {
            Text("Delete this rig", color = if (rig.rigs.size > 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (rig.rigs.size <= 1) Hint("The only saved rig cannot be deleted.")
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${c.name}?") },
        text = { Text("The rig and its remembered frequency are removed. Saved frequencies are not affected.") },
        confirmButton = { TextButton({ confirmDelete = false; rig.deleteRig(c.id); onBack() }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } },
    )
}

/** Port settings that differ between radios and adapters; the defaults suit most Yaesu radios. Applied on the next Connect. */
@Composable
private fun SerialDetails(c: RigConfig, change: (RigConfig) -> Unit) = Column {
    ChoiceRow("Data bits", listOf("7" to "7", "8" to "8"), c.dataBits.toString()) { change(c.copy(dataBits = it.toInt())) }
    ChoiceRow("Stop bits", listOf("1" to "1", "2" to "2"), c.stopBits.toString()) { change(c.copy(stopBits = it.toInt())) }
    ChoiceRow("Parity", listOf("0" to "None", "2" to "Even", "1" to "Odd"), c.parity.toString()) { change(c.copy(parity = it.toInt())) }
    ChoiceRow("Flow control", listOf("none" to "None", "rtscts" to "RTS/CTS", "xonxoff" to "XON/XOFF"), c.flow) { change(c.copy(flow = it)) }
    val lines = listOf("leave" to "Leave alone", "low" to "Low", "high" to "High")
    ChoiceRow("DTR when opened", lines, c.dtr) { change(c.copy(dtr = it)) }
    ChoiceRow("RTS when opened", lines, c.rts) { change(c.copy(rts = it)) }
    Hint("DTR and RTS key the transmitter on some radios, so they are left alone unless this radio needs a fixed level.")
}
