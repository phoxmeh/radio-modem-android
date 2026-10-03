package app.radiomodem.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
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
    }
}
