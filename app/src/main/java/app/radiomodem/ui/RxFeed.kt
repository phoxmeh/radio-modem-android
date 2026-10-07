package app.radiomodem.ui

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.radiomodem.AudioViewModel
import app.radiomodem.RxItem
import app.radiomodem.settings.AppSettings

/** Received text and pictures in arrival order. Sticks to the bottom until you scroll up. */
@Composable
fun RxFeed(audio: AudioViewModel, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf<RxItem.Image?>(null) }

    // Scrolling away from the end pauses auto-follow; returning to the end resumes it.
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward to listState.isScrollInProgress }.collect { (more, scrolling) ->
            if (scrolling && more) follow = false
            if (!more) follow = true
        }
    }
    LaunchedEffect(audio.rxVersion) {
        if (follow && audio.rxItems.isNotEmpty()) listState.scrollToItem(audio.rxItems.lastIndex, Int.MAX_VALUE)
    }

    LazyColumn(modifier.background(MaterialTheme.colorScheme.surfaceContainer), state = listState,
        contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(audio.rxItems.size) { i ->
            when (val item = audio.rxItems.getOrNull(i)) {
                null -> {}
                is RxItem.Text -> Text(item.text, fontFamily = FontFamily.Monospace, fontSize = AppSettings.get(LocalContext.current).feedTextSp.sp)
                is RxItem.Image -> PictureCard(audio, item) { zoomed = item }
            }
        }
    }
    zoomed?.let { ZoomDialog(it) { zoomed = null } }
}

@Composable
private fun PictureCard(audio: AudioViewModel, item: RxItem.Image, onZoom: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(item.width.toFloat() / item.height).background(Color(0xFF202020))
            .clickable(onClick = onZoom)) {
            val bmp = item.bitmap
            item.frame  // subscribe to updates of the mutated bitmap
            if (bmp != null) {
                val img = remember(item.frame) { bmp.asImageBitmap() }
                Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (item.complete) "${item.width}×${item.height} · complete" else "${item.width}×${item.height} · ${item.rows}/${item.height} lines",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            )
            TextButton({
                val uri = audio.saveImage(item)
                Toast.makeText(ctx, if (uri != null) "Saved to Pictures/RadioModem" else "Nothing to save yet", Toast.LENGTH_SHORT).show()
            }) { Text("Save", fontSize = 12.sp) }
            TextButton({ audio.shareImage(item) }) { Text("Share", fontSize = 12.sp) }
        }
    }
}

/** Full-screen pinch-to-zoom view of a received picture. */
@Composable
private fun ZoomDialog(item: RxItem.Image, onDismiss: () -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box {
                Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _, pan, z, _ -> zoom = (zoom * z).coerceIn(1f, 12f); off += pan }
                }) {
                    item.frame
                    val bmp = item.bitmap ?: return@Canvas
                    val fit = minOf(size.width / item.width, size.height / item.height)
                    val w = item.width * fit * zoom; val h = item.height * fit * zoom
                    val x = (size.width - w) / 2 + off.x; val y = (size.height - h) / 2 + off.y
                    drawImage(bmp.asImageBitmap(), IntOffset.Zero, IntSize(item.width, item.height),
                        IntOffset(x.toInt(), y.toInt()), IntSize(w.toInt(), h.toInt()), filterQuality = FilterQuality.None)
                }
                TextButton(onDismiss, Modifier.align(Alignment.TopEnd).safeDrawingPadding()) { Text("Close") }
            }
        }
    }
}
