package app.radiomodem

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One entry in the receive feed. */
sealed class RxItem {
    class Text(initial: String) : RxItem() {
        var text by mutableStateOf(initial)
    }

    /** An MFSK picture. [bitmap] is allocated when the first rows arrive and filled top to bottom. */
    class Image(val id: Int, val width: Int, val height: Int) : RxItem() {
        var bitmap: Bitmap? = null
        var rows by mutableIntStateOf(0)
        var complete by mutableStateOf(false)
        var frame by mutableIntStateOf(0)  // bumped per update so Compose redraws the mutated bitmap
        var saved = false
    }
}
