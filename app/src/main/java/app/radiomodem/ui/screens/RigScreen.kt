package app.radiomodem.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.radiomodem.rig.RigViewModel

@Composable
fun RigSetupScreen(rig: RigViewModel, onBack: () -> Unit) {
    var drivers by remember { mutableStateOf(rig.drivers()) }
    var selected by remember { mutableIntStateOf(0) }
    var port by remember { mutableIntStateOf(0) }
    var baud by remember { mutableStateOf(rig.profile.serial.baud.toString()) }

    ScreenPage("Rig setup", onBack) {
        SectionLabel("Radio")
        Text(rig.profile.name, style = MaterialTheme.typography.titleMedium)
        Hint("Modes, bandwidths and commands are taken from flrig's ${rig.profile.name} definition. More radios can be added as profiles.")
        Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Text(rig.status, Modifier.padding(12.dp))
        }
        HorizontalDivider()
        SectionLabel("USB serial device")
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
            OutlinedTextField(baud, { baud = it.filter(Char::isDigit) }, label = { Text("Baud rate") }, singleLine = true,
                supportingText = { Text("Must match the radio's CAT RATE menu setting") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rig.connected) Button({ rig.disconnect() }) { Text("Disconnect") }
            else Button(
                { drivers.getOrNull(selected)?.let { rig.connect(it, port, baud.toIntOrNull() ?: rig.profile.serial.baud) } },
                enabled = drivers.isNotEmpty(),
            ) { Text("Connect") }
            OutlinedButton({ drivers = rig.drivers() }) { Text("Rescan") }
        }
        Hint("The last radio you connect is remembered and reopened automatically when plugged in (Settings > Startup and logging).")
    }
}
