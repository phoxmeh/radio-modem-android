package app.radiomodem.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Every user preference and the "last state" restored at startup, in one place. Each property is Compose
 * state backed by SharedPreferences, so screens update live and values survive restarts.
 */
class AppSettings private constructor(ctx: Context) {
    private val prefs: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ---- Display -------------------------------------------------------------------------------
    var theme by str("theme", "system")                 // system | dark | light
    var feedTextSp by num("feed_text_sp", 14f)
    var feedTimestamps by flag("feed_timestamps", false)
    var keepScreenOn by flag("keep_screen_on", true)

    // ---- Waterfall -----------------------------------------------------------------------------
    var waterfallPalette by str("wf_palette", "classic") // classic | heat | grey | ice
    var waterfallSpeed by str("wf_speed", "normal")      // slow | normal | fast
    var waterfallFloorDb by num("wf_floor", -90f)
    var waterfallRangeDb by num("wf_range", 50f)
    var waterfallDefaultSpanHz by num("wf_span", 4000f)  // initial visible span, starting at 0 Hz

    // ---- Decoding defaults -----------------------------------------------------------------------
    var squelchPsk by num("sq_psk", 0.30f)               // BPSK and QPSK
    var squelchMfsk by num("sq_mfsk", 0.30f)
    var squelchOlivia by num("sq_olivia", 0.12f)         // slow modes need a lower threshold to lock
    var defaultAfc by flag("def_afc", true)
    var defaultReverse by flag("def_reverse", false)

    // ---- Pictures ------------------------------------------------------------------------------
    var pictureAutoSave by flag("pic_autosave", true)
    var pictureFormat by str("pic_format", "png")         // png | jpeg
    var pictureJpegQuality by num("pic_jpeg_q", 90f)
    var pictureFolder by str("pic_folder", "RadioModem")  // under Pictures/

    // ---- Station -------------------------------------------------------------------------------
    var callsign by str("call", "")
    var operatorName by str("name", "")
    var locator by str("locator", "")

    // ---- Logging -------------------------------------------------------------------------------
    var logEnabled by flag("log_enabled", true)
    var logKeep by num("log_keep", 50f)                   // number of session logs kept

    // ---- Fax schedule ----------------------------------------------------------------------------
    var scheduleStations by str("sched_stations", "boston,new_orleans,pt_reyes,northwood,hamburg")  // station ids
    var scheduleTypes by str("sched_types", "Analysis,Forecast,Satellite,Waves,Ice,Ocean,Other")    // chart types shown
    var scheduleUtc by flag("sched_utc", true)
    var scheduleDpPerHour by num("sched_zoom", 120f)
    var scheduleLastCheck by str("sched_checked", "")  // epoch millis of the last Update

    // ---- Startup ---------------------------------------------------------------------------------
    var restoreState by flag("restore_state", true)
    var autoReconnect by flag("auto_reconnect", true)
    var onboardingDone by flag("onboarding_done", false)

    // ---- Last state (written as you operate, read at startup when restoreState is on) -------------
    var lastModem by num("last_modem", 0f)
    var lastCarrierHz by num("last_carrier", 1000f)
    var lastSquelch by num("last_squelch", -1f)           // -1 = use the family default
    var lastAfc by flag("last_afc", true)
    var lastReverse by flag("last_reverse", false)
    var showVfo by flag("show_vfo", true)
    var showWaterfall by flag("show_waterfall", true)
    var waterfallDp by num("wf_dp", 160f)
    var viewStartHz by num("view_start", 0f)
    var viewSpanHz by num("view_span", 4000f)
    // Preferred audio input: type + product name survive unplugging (device ids do not).
    var audioDeviceType by num("audio_type", -1f)
    var audioDeviceName by str("audio_name", "")
    var audioGain by num("audio_gain", 1f)
    // Last USB serial rig, for auto-reconnect.
    var usbVendor by num("usb_vid", -1f)
    var usbProduct by num("usb_pid", -1f)
    var usbPort by num("usb_port", 0f)
    var usbBaud by num("usb_baud", 0f)

    /** Squelch default for a decoder family, as configured under Decoding defaults. */
    fun squelchFor(family: String): Float = when (family) {
        "Olivia" -> squelchOlivia
        "MFSK" -> squelchMfsk
        "WEFAX" -> 0f
        else -> squelchPsk
    }

    // ---- property delegates -------------------------------------------------------------------
    private fun flag(key: String, default: Boolean) = Pref(
        mutableStateOf(prefs.getBoolean(key, default))) { prefs.edit().putBoolean(key, it).apply() }
    private fun num(key: String, default: Float) = Pref(
        mutableStateOf(prefs.getFloat(key, default))) { prefs.edit().putFloat(key, it).apply() }
    private fun str(key: String, default: String) = Pref(
        mutableStateOf(prefs.getString(key, default) ?: default)) { prefs.edit().putString(key, it).apply() }

    private class Pref<T>(private val state: androidx.compose.runtime.MutableState<T>, private val save: (T) -> Unit) :
        ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { state.value = value; save(value) }
    }

    companion object {
        @Volatile private var instance: AppSettings? = null
        fun get(ctx: Context): AppSettings =
            instance ?: synchronized(this) { instance ?: AppSettings(ctx.applicationContext).also { instance = it } }
    }
}
