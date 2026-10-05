package app.radiomodem.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Full-screen page with a back arrow, used for every menu destination. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenPage(
    title: String, onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) = Scaffold(
    topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = actions,
        )
    },
) { pad ->
    Column(
        Modifier.padding(pad).fillMaxSize().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun SettingSwitch(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Hint(subtitle)
        }
        Switch(checked, onChange, enabled = enabled)
    }

/** A row of choice chips; `options` are (value, label) pairs. */
@Composable
fun SettingChoice(title: String, options: List<Pair<String, String>>, selected: String, subtitle: String? = null, onSelect: (String) -> Unit) =
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Hint(subtitle)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) -> FilterChip(selected == value, { onSelect(value) }, { Text(label) }) }
        }
    }

@Composable
fun SettingSlider(
    title: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0,
    subtitle: String? = null, enabled: Boolean = true, onChange: (Float) -> Unit,
) = Column {
    Row(Modifier.fillMaxWidth()) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(valueText, style = MaterialTheme.typography.bodyMedium)
    }
    if (subtitle != null) Hint(subtitle)
    Slider(value, onChange, valueRange = range, steps = steps, enabled = enabled)
}

/** A tappable row that opens another screen. */
@Composable
fun NavRow(title: String, subtitle: String, onClick: () -> Unit) =
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Hint(subtitle)
    }

/** A labelled field that opens a menu of choices: for longer lists than chips handle well. */
@Composable
fun DropdownField(
    label: String, options: List<Pair<String, String>>, selected: String, modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true, onSelect: (String) -> Unit,
) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val current = options.firstOrNull { it.first == selected }?.second ?: selected
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton({ open = true }, Modifier.fillMaxWidth(), enabled = enabled, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Text(current, Modifier.weight(1f), maxLines = 1)
                Text("▾")
            }
            DropdownMenu(open, { open = false }) {
                options.forEach { (id, text) ->
                    DropdownMenuItem({ Text(text) }, { open = false; onSelect(id) },
                        trailingIcon = { if (id == selected) Text("✓") })
                }
            }
        }
    }
}
