package app.radiomodem.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.radiomodem.AudioViewModel
import app.radiomodem.Modems

/** Scrolling waterfall: tap sets RX carrier, drag pans, pinch zooms the audio span. */
@Composable
fun Waterfall(vm: AudioViewModel, modifier: Modifier = Modifier) {
    Canvas(
        modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    vm.tuneTo(vm.viewStartHz + p.x / size.width * vm.viewSpanHz)
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val focusHz = vm.viewStartHz + centroid.x / size.width * vm.viewSpanHz
                    val span = (vm.viewSpanHz / zoom).coerceIn(200f, AudioViewModel.MAX_HZ)
                    var start = focusHz - centroid.x / size.width * span - pan.x / size.width * span
                    start = start.coerceIn(0f, AudioViewModel.MAX_HZ - span)
                    vm.viewSpanHz = span
                    vm.viewStartHz = start
                }
            },
    ) {
        vm.frame  // read to subscribe to new rows
        val bmp = vm.bitmap
        val srcX = vm.hzToPx(vm.viewStartHz).toInt()
        val srcW = vm.hzToPx(vm.viewSpanHz).toInt().coerceAtLeast(1).coerceAtMost(bmp.width - srcX)
        drawImage(
            bmp.asImageBitmap(),
            srcOffset = IntOffset(srcX, 0),
            srcSize = IntSize(srcW, bmp.height),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.Low,
        )
        val x = (vm.rxHz - vm.viewStartHz) / vm.viewSpanHz * size.width
        // Occupied bandwidth of the selected modem, centered on the carrier.
        val halfPx = Modems.all[vm.modemId].bandwidthHz / 2 / vm.viewSpanHz * size.width
        drawRect(Color.Red.copy(alpha = 0.18f), Offset(x - halfPx, 0f), androidx.compose.ui.geometry.Size(halfPx * 2, size.height))
        drawLine(Color.Red, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
    }
}
