package app.radiomodem

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import app.radiomodem.settings.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves received pictures to Pictures/<folder> (no storage permission needed on API 29+); format and folder come from Settings. */
object FaxSaver {
    fun savePng(ctx: Context, source: Bitmap, rows: Int, prefix: String = "wefax"): Uri? {
        val settings = AppSettings.get(ctx)
        val jpeg = settings.pictureFormat == "jpeg"
        val folder = settings.pictureFolder.trim().ifEmpty { "RadioModem" }.replace(Regex("[^A-Za-z0-9 _.-]"), "_")
        val name = prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + if (jpeg) ".jpg" else ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, if (jpeg) "image/jpeg" else "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$folder")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val cropped = Bitmap.createBitmap(source, 0, 0, source.width, rows.coerceAtMost(source.height))
            resolver.openOutputStream(uri)?.use {
                if (jpeg) cropped.compress(Bitmap.CompressFormat.JPEG, settings.pictureJpegQuality.toInt().coerceIn(30, 100), it)
                else cropped.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
