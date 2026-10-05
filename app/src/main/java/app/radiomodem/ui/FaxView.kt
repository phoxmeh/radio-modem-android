package app.radiomodem.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.radiomodem.AudioViewModel
import kotlin.math.max
import kotlin.math.min

/** Live weather-fax picture: pinch to zoom, drag to pan; follows the newest line until you scroll away. */
@Composable
fun FaxView(audio: AudioViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var zoom by remember { mutableFloatStateOf(1f) }
    var offX by remember { mutableFloatStateOf(0f) }
    var offY by remember { mutableFloatStateOf(0f) }

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val status = when (audio.faxState) {
                1 -> "Phasing…"
                2 -> "Receiving · line ${audio.faxRows}"
                else -> if (audio.faxRows > 0) "Complete · ${audio.faxRows} lines" else "Waiting for APT start tone"
            }
            Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            // Scrolling back through the picture stops following new lines; this jumps to the newest line again.
            TextButton({ audio.faxFollow = true }, enabled = !audio.faxFollow) { Text("Latest", fontSize = 12.sp) }
            TextButton({
                val uri = audio.saveFax()
                Toast.makeText(ctx, if (uri != null) "Saved to Pictures/RadioModem" else "Nothing to save", Toast.LENGTH_SHORT).show()
            }) { Text("Save", fontSize = 12.sp) }
            TextButton({ audio.shareFax() }) { Text("Share", fontSize = 12.sp) }
        }
        Canvas(
            Modifier.fillMaxWidth().weight(1f).pointerInput(Unit) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(1f, 8f)
                    offX += pan.x; offY += pan.y
                    if (pan.y > 2f) audio.faxFollow = false  // scrolled back up
                }
            },
        ) {
            audio.faxFrame  // subscribe to new rows
            val bmp = audio.faxBitmap ?: return@Canvas
            val rows = audio.faxRows
            if (rows < 1 || audio.faxWidth < 1) return@Canvas
            val scale = size.width / audio.faxWidth * zoom
            val contentW = audio.faxWidth * scale
            val contentH = rows * scale
            offX = if (contentW <= size.width) 0f else offX.coerceIn(size.width - contentW, 0f)
            val minY = min(0f, size.height - contentH)
            if (audio.faxFollow) offY = minY
            offY = offY.coerceIn(minY, 0f)
            if (offY == minY && contentH > size.height) { /* at the bottom: follow stays valid */ }
            drawImage(
                bmp.asImageBitmap(),
                srcOffset = IntOffset(0, 0), srcSize = IntSize(audio.faxWidth, rows),
                dstOffset = IntOffset(offX.toInt(), offY.toInt()), dstSize = IntSize(contentW.toInt(), max(1, contentH.toInt())),
            )
        }
        FaxBottomBar(audio)
    }
}

/** Picture adjustments that are needed while watching a transmission, so they live under the picture. */
@Composable
private fun FaxBottomBar(audio: AudioViewModel) {
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AdjustRow(
            "Slant", "%+.2f%%".format(audio.faxSlant),
            if (audio.faxAutoSlantNow != 0f) "auto %+.3f%%".format(audio.faxAutoSlantNow) else null,
            fine = { audio.updateFaxSlant(((audio.faxSlant + it * 0.01f) * 100).roundToInt().coerceIn(-100, 100) / 100f) },
            coarse = { audio.updateFaxSlant(((audio.faxSlant + it * 0.1f) * 100).roundToInt().coerceIn(-100, 100) / 100f) },
            reset = { audio.updateFaxSlant(0f) },
        )
        AdjustRow(
            "Shift", "%+.0f%%".format(audio.faxShift * 100), null,
            fine = { audio.updateFaxShift(((audio.faxShift + it * 0.01f) * 100).roundToInt().coerceIn(-50, 50) / 100f) },
            coarse = { audio.updateFaxShift(((audio.faxShift + it * 0.05f) * 100).roundToInt().coerceIn(-50, 50) / 100f) },
            reset = { audio.updateFaxShift(0f) },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Switch(audio.afc, { audio.updateAfc(it) }, Modifier.scale(0.8f))
            Text("AFC", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Switch(audio.faxManual, { audio.updateFaxManual(it) }, Modifier.scale(0.8f))
            Text("Skip APT", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            OutlinedButton({ confirmClear = true }, contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Clear") }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear the picture?") },
        text = { Text(if (audio.faxRows > 0) "The ${audio.faxRows} lines received so far are discarded and the decoder waits for the next APT start tone. Use Save first to keep them."
                    else "The decoder goes back to waiting for the next APT start tone.") },
        confirmButton = { TextButton({ confirmClear = false; audio.faxRestart() }) { Text("Clear") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
    )
}

/** Label, then coarse/fine steps either side of the value. Tap the value to reset it. Hold a step to repeat. */
@Composable
private fun AdjustRow(
    label: String, value: String, note: String?, fine: (Int) -> Unit, coarse: (Int) -> Unit, reset: () -> Unit,
) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    Text(label, Modifier.width(46.dp), style = MaterialTheme.typography.labelLarge)
    StepButton("−−", Modifier.weight(1f)) { coarse(-1) }
    StepButton("−", Modifier.weight(1f)) { fine(-1) }
    Surface(onClick = reset, Modifier.weight(2.2f).height(40.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (note != null) Text(note, fontSize = 9.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    StepButton("+", Modifier.weight(1f)) { fine(1) }
    StepButton("++", Modifier.weight(1f)) { coarse(1) }
}
