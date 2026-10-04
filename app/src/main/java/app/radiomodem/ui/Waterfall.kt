package app.radiomodem.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.radiomodem.AudioViewModel
import app.radiomodem.Modems
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val RulerBg = Color(0xFF12171B)
private val RulerText = Color(0xFFCFD8DC)
private val Amber = Color(0xFFFFC107)
private val RefBlue = Color(0xFF4FC3F7)

/** Major tick spacing (Hz) and the minor spacing inside it. */
private val TICKS = listOf(10 to 2, 20 to 5, 50 to 10, 100 to 20, 200 to 50, 250 to 50, 500 to 100, 1000 to 200)

/**
 * Scrolling waterfall with a frequency ruler.
 *
 * Zoom is uniform: one spectrum bin is as wide as the rows are tall (a fixed ratio), so zooming magnifies the picture
 * like a camera instead of stretching it sideways. Tap places the carrier, dragging the marker on the ruler moves it
 * precisely, dragging elsewhere pans and two fingers zoom.
 */
@Composable
fun Waterfall(vm: AudioViewModel, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val image = remember(vm.bitmap) { vm.bitmap.asImageBitmap() }
    Canvas(
        modifier.fillMaxSize().clipToBounds().pointerInput(Unit) {
            val slop = viewConfiguration.touchSlop
            val grab = 30.dp.toPx()
            val rulerH = RULER_DP.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                fun hzAt(x: Float) = vm.viewStartHz + x / size.width * vm.viewSpanHz
                val markerX = (vm.rxHz - vm.viewStartHz) / vm.viewSpanHz * size.width
                // 0 = undecided, 1 = dragging the carrier, 2 = panning, 3 = pinching
                var mode = 0
                var multi = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        multi = true; mode = 3
                        val z = event.calculateZoom()
                        val c = event.calculateCentroid()
                        if (z != 1f) vm.zoomBy(z, hzAt(c.x))
                        event.changes.forEach { it.consume() }
                    } else if (pressed.size == 1 && !multi) {
                        val c = pressed[0]
                        if (mode == 0 && (c.position - down.position).getDistance() > slop)
                            mode = if (down.position.y < rulerH && abs(down.position.x - markerX) < grab) 1 else 2
                        if (mode == 1) { vm.tuneTo(hzAt(c.position.x).roundToInt().toFloat()); c.consume() }
                        else if (mode == 2) {
                            val dx = c.positionChange().x
                            vm.viewStartHz = (vm.viewStartHz - dx / size.width * vm.viewSpanHz).coerceIn(0f, AudioViewModel.MAX_HZ - vm.viewSpanHz)
                            c.consume()
                        }
                    }
                } while (event.changes.any { it.pressed })
                if (mode == 0 && !multi) vm.tuneTo(hzAt(down.position.x).roundToInt().toFloat())
            }
        },
    ) {
        vm.frame  // read to subscribe to new rows
        val w = size.width
        val rulerH = RULER_DP.dp.toPx()
        val span = vm.viewSpanHz
        val start = vm.viewStartHz
        val pxPerHz = w / span
        fun xOf(hz: Float) = (hz - start) * pxPerHz

        // ---- picture: uniform scale, rows are half a bin tall, never thinner than 1 dp ----
        val binPx = pxPerHz * vm.binHz
        val rowPx = maxOf(1.dp.toPx(), binPx * 0.5f)
        val top = rulerH
        val imgH = size.height - top
        val first = floor(start / vm.binHz).toInt().coerceAtLeast(0)
        val last = minOf(ceil((start + span) / vm.binHz).toInt() + 1, vm.bitmap.width)
        val srcW = (last - first).coerceAtLeast(1)
        val dstX = ((first * vm.binHz - start) * pxPerHz).roundToInt()
        val dstW = (srcW * binPx).roundToInt().coerceAtLeast(1)
        val quality = if (binPx >= 1.6f) FilterQuality.None else FilterQuality.Low
        val rows = minOf(AudioViewModel.HISTORY, ceil(imgH / rowPx).toInt() + 1)
        val head = vm.head
        val a = minOf(rows, AudioViewModel.HISTORY - head)
        fun segment(srcY: Int, n: Int, dstY: Float) {
            if (n <= 0) return
            drawImage(image, IntOffset(first, srcY), IntSize(srcW, n), IntOffset(dstX, dstY.roundToInt()),
                IntSize(dstW, (n * rowPx).roundToInt().coerceAtLeast(1)), filterQuality = quality)
        }
        segment(head, a, top)
        segment(0, rows - a, top + a * rowPx)

        // ---- grid lines + ruler ----
        val (major, minor) = TICKS.firstOrNull { it.first * pxPerHz >= 58.dp.toPx() } ?: TICKS.last()
        var hz = ceil(start / minor).toInt() * minor
        while (hz <= start + span) {
            val x = xOf(hz.toFloat())
            if (hz % major == 0) drawLine(Color.White.copy(alpha = 0.16f), Offset(x, top), Offset(x, size.height), 1f)
            hz += minor
        }

        // ---- modem passband: dim everything outside it, bright edges, centre line ----
        val bw = Modems.all[vm.modemId].bandwidthHz
        val cx = xOf(vm.rxHz)
        val left = xOf(vm.rxHz - bw / 2)
        val right = xOf(vm.rxHz + bw / 2)
        val dim = Color.Black.copy(alpha = 0.42f)
        if (left > 0f) drawRect(dim, Offset(0f, top), Size(minOf(left, w), imgH))
        if (right < w) drawRect(dim, Offset(maxOf(right, 0f), top), Size(w - maxOf(right, 0f), imgH))
        val edge = 2.dp.toPx()
        drawLine(Color.Black.copy(alpha = 0.6f), Offset(left, top), Offset(left, size.height), edge + 2f)
        drawLine(Color.Black.copy(alpha = 0.6f), Offset(right, top), Offset(right, size.height), edge + 2f)
        drawLine(Amber, Offset(left, top), Offset(left, size.height), edge)
        drawLine(Amber, Offset(right, top), Offset(right, size.height), edge)
        drawLine(Color.Black.copy(alpha = 0.6f), Offset(cx, top), Offset(cx, size.height), 5f)
        drawLine(Color.White, Offset(cx, top), Offset(cx, size.height), 2.5f)

        // ruler band
        drawRect(RulerBg, Offset.Zero, Size(w, rulerH))
        val tickTop = rulerH * 0.5f
        val label = TextStyle(color = RulerText, fontSize = 10.sp)
        hz = ceil(start / minor).toInt() * minor
        while (hz <= start + span) {
            val x = xOf(hz.toFloat())
            val isMajor = hz % major == 0
            drawLine(RulerText, Offset(x, if (isMajor) tickTop + 3.dp.toPx() else rulerH - 5.dp.toPx()), Offset(x, rulerH), 1f)
            if (isMajor) {
                val t = measurer.measure(hz.toString(), label)
                val tx = (x - t.size.width / 2f).coerceIn(0f, w - t.size.width)
                drawText(t, topLeft = Offset(tx, tickTop - 3.dp.toPx() + 1f))
            }
            hz += minor
        }
        // reference centre (1500 Hz, 1900 Hz for fax): the middle of a typical SSB passband
        val rx = xOf(vm.referenceHz)
        if (rx in 0f..w) {
            val tri = Path().apply { moveTo(rx - 5.dp.toPx(), rulerH); lineTo(rx + 5.dp.toPx(), rulerH); lineTo(rx, rulerH - 7.dp.toPx()); close() }
            drawPath(tri, RefBlue)
        }
        // passband bracket on the ruler
        drawRect(Amber.copy(alpha = 0.95f), Offset(left, rulerH - 3.dp.toPx()), Size(maxOf(right - left, 2f), 3.dp.toPx()))
        // carrier handle: readout chip with a pointer, grab it to fine-tune
        val chip = measurer.measure(vm.rxHz.roundToInt().toString() + " Hz", TextStyle(color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold))
        val cw = chip.size.width + 12.dp.toPx(); val ch = chip.size.height + 2.dp.toPx()
        val chipX = (cx - cw / 2).coerceIn(0f, maxOf(0f, w - cw))
        drawRoundRect(Color.White, Offset(chipX, 0f), Size(cw, ch), CornerRadius(4.dp.toPx()))
        drawText(chip, topLeft = Offset(chipX + 6.dp.toPx(), 1.dp.toPx()))
        val ptr = Path().apply { moveTo(cx - 4.dp.toPx(), ch); lineTo(cx + 4.dp.toPx(), ch); lineTo(cx, ch + 4.dp.toPx()); close() }
        drawPath(ptr, Color.White)

        // overview strip when zoomed: where the view sits inside 0..4 kHz, and the carrier
        if (span < AudioViewModel.MAX_HZ - 1f) overview(vm, w, size.height)
    }
}

private fun DrawScope.overview(vm: AudioViewModel, w: Float, h: Float) {
    val bar = 5.dp.toPx(); val y = h - bar
    drawRect(Color.Black.copy(alpha = 0.6f), Offset(0f, y), Size(w, bar))
    val f = w / AudioViewModel.MAX_HZ
    drawRect(Color.White.copy(alpha = 0.55f), Offset(vm.viewStartHz * f, y), Size(vm.viewSpanHz * f, bar))
    drawRect(Amber, Offset(vm.rxHz * f - 1.5f, y), Size(3f, bar))
}

const val RULER_DP = 40
