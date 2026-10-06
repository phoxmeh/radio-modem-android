package app.radiomodem.radiogram

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Reads the schedule picture with Tesseract (English model, bundled). The table is black text on a light green grid;
 * the picture is enlarged, turned to black on white, and the grid lines are erased first, because stray lines make
 * Tesseract invent characters.
 */
class RadiogramOcr(private val ctx: Context) {
    fun read(image: ByteArray): String {
        val src = BitmapFactory.decodeByteArray(image, 0, image.size) ?: return ""
        val prepared = prepare(src)
        val dataDir = File(ctx.filesDir, "tesseract")
        val tessdata = File(dataDir, "tessdata").apply { mkdirs() }
        val model = File(tessdata, "eng.traineddata")
        if (!model.exists() || model.length() < 1_000_000) ctx.assets.open("tessdata/eng.traineddata").use { i -> model.outputStream().use { o -> i.copyTo(o) } }
        val tess = TessBaseAPI()
        try {
            if (!tess.init(dataDir.absolutePath, "eng")) return ""
            tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            tess.setImage(prepared)
            return tess.getUTF8Text().orEmpty()
        } finally { tess.recycle() }
    }

    internal fun prepare(src: Bitmap): Bitmap {
        val scale = 3
        val big = Bitmap.createScaledBitmap(src, src.width * scale, src.height * scale, true)
        val w = big.width; val h = big.height
        val px = IntArray(w * h); big.getPixels(px, 0, w, 0, 0, w, h)
        val dark = BooleanArray(w * h) { i -> val c = px[i]; (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100 < 140 }
        // Grid lines are dark runs across most of the width or height; text never is.
        for (y in 0 until h) {
            var n = 0; for (x in 0 until w) if (dark[y * w + x]) n++
            if (n > w * 0.6) for (x in 0 until w) dark[y * w + x] = false
        }
        for (x in 0 until w) {
            var n = 0; for (y in 0 until h) if (dark[y * w + x]) n++
            if (n > h * 0.6) for (y in 0 until h) dark[y * w + x] = false
        }
        val out = IntArray(w * h) { if (dark[it]) Color.BLACK else Color.WHITE }
        // A white margin helps Tesseract find the block edges.
        val pad = 24
        val padded = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        padded.setPixels(out, 0, w, pad, pad, w, h)
        return padded
    }
}
