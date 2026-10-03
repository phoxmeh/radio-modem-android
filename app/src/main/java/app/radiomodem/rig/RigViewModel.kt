package app.radiomodem.rig

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.radiomodem.settings.AppSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Radio state shared with the UI. Works offline (values just live here) and, once connected, mirrors the
 * rig: a single worker coroutine serializes queued commands and a round-robin poll of the rig's state.
 */
class RigViewModel(app: Application) : AndroidViewModel(app) {
    val profile: RigProfile = app.assets.open("rigs/FT-891.json").bufferedReader().use { RigProfile.parse(it.readText()) }
    private val prefs = app.getSharedPreferences("rig", Context.MODE_PRIVATE)
    private val settings = AppSettings.get(app)

    var freqHz by mutableLongStateOf(prefs.getLong("freq", profile.defaultFreq)); private set
    var modeIdx by mutableIntStateOf(prefs.getInt("mode", profile.modes.indexOfFirst { it.name == "USB" }.coerceAtLeast(0))); private set
    var bwIdx by mutableIntStateOf(prefs.getInt("bw", 0)); private set
    var powerW by mutableIntStateOf(prefs.getInt("power", profile.powerMin)); private set
    var smeter by mutableFloatStateOf(0f); private set
    var connected by mutableStateOf(false); private set
    var status by mutableStateOf("Not connected (audio-only)"); private set

    val mode get() = profile.modes[modeIdx]
    val bwTable get() = profile.bwTableFor(modeIdx)

    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val tasks = Channel<CatRig.() -> Unit>(Channel.UNLIMITED)
    private var rig: CatRig? = null
    private var worker: Job? = null
    private val heldUntil = HashMap<String, Long>()  // user edits suppress polled values briefly

    fun drivers(): List<UsbSerialDriver> = UsbSerialProber.getDefaultProber().findAllDrivers(usb)

    // ---- user actions -------------------------------------------------------------------------

    fun setFrequency(hz: Long) {
        val v = hz.coerceIn(profile.minHz, profile.maxHz)
        freqHz = v; hold("freq"); persist()
        Bands.of(v)?.let { prefs.edit().putString("band_${it.name}", "$v,${mode.name}").apply() }
        run { setFrequency(v) }
    }

    fun stepFrequency(deltaHz: Long) = setFrequency(freqHz + deltaHz)

    fun setMode(idx: Int) {
        modeIdx = idx; hold("mode"); persist()
        bwIdx = bwIdx.coerceIn(0, ((bwTable?.labels?.size ?: 1) - 1).coerceAtLeast(0))
        run { setMode(idx) }
    }

    fun setBandwidth(idx: Int) {
        bwIdx = idx; hold("bw"); persist()
        val m = modeIdx
        run { setBandwidth(m, idx) }
    }

    fun setPower(w: Int) {
        powerW = w.coerceIn(profile.powerMin, profile.powerMax); hold("power"); persist()
        val v = powerW
        run { setPower(v) }
    }

    /** Jump to a band, restoring the last frequency and mode used there. */
    fun selectBand(b: Band) {
        val saved = prefs.getString("band_${b.name}", null)?.split(",")
        val hz = saved?.getOrNull(0)?.toLongOrNull() ?: b.defaultHz
        val modeName = saved?.getOrNull(1) ?: "DATA-U"
        profile.modes.indexOfFirst { it.name == modeName }.takeIf { it >= 0 }?.let { if (it != modeIdx) setMode(it) }
        setFrequency(hz)
    }

    // ---- connection ---------------------------------------------------------------------------

    fun connect(driver: UsbSerialDriver, portIndex: Int, baud: Int) {
        if (usb.hasPermission(driver.device)) { open(driver, portIndex, baud); return }
        val action = "app.radiomodem.USB_PERMISSION"
        val ctx = getApplication<Application>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                c.unregisterReceiver(this)
                if (usb.hasPermission(driver.device)) open(driver, portIndex, baud) else status = "USB permission denied"
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Must be mutable: the system adds the permission result extras.
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(action).setPackage(ctx.packageName), PendingIntent.FLAG_MUTABLE)
        usb.requestPermission(driver.device, pi)
        status = "Waiting for USB permission…"
    }

    private fun open(driver: UsbSerialDriver, portIndex: Int, baud: Int) {
        disconnect()
        val conn = usb.openDevice(driver.device)
        if (conn == null) { status = "Could not open USB device"; return }
        val port: UsbSerialPort? = driver.ports.getOrNull(portIndex)
        if (port == null) { status = "No such port"; return }
        // DTR/RTS are deliberately left alone: on many rigs they key PTT.
        try {
            port.open(conn)
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Exception) { status = "Open failed: ${e.message}"; return }
        status = "Connecting…"
        worker = viewModelScope.launch(Dispatchers.IO) {
            val r = CatRig(profile, UsbSerialLink(port))
            if (!r.identify()) {
                r.close(); status = "No answer from ${profile.name} (check CAT rate ${baud} and port)"; return@launch
            }
            rig = r; connected = true; status = "Connected: ${profile.name}"
            // Remember this adapter so it can be reopened automatically next time.
            settings.usbVendor = driver.device.vendorId.toFloat(); settings.usbProduct = driver.device.productId.toFloat()
            settings.usbPort = portIndex.toFloat(); settings.usbBaud = baud.toFloat()
            // Push our remembered power; read everything else from the radio.
            var step = 0
            try {
                while (isActive) {
                    while (true) tasks.tryReceive().getOrNull()?.invoke(r) ?: break
                    poll(r, step++)
                    delay(120)
                }
            } catch (e: Exception) {
                status = "Link lost: ${e.message}"
            } finally {
                connected = false; rig = null; r.close()
            }
        }
    }

    private val autoTried = HashSet<Int>()

    /**
     * Reopens the last USB radio if it is plugged in. Tries each physical attach once, so a refused
     * permission prompt does not come back on every resume.
     */
    fun autoConnect() {
        if (!settings.autoReconnect || connected || settings.usbVendor < 0) return
        val driver = drivers().firstOrNull { it.device.vendorId == settings.usbVendor.toInt() && it.device.productId == settings.usbProduct.toInt() }
        if (driver == null) { autoTried.clear(); return }  // unplugged: allow another attempt when it returns
        if (!autoTried.add(driver.device.deviceId)) return
        connect(driver, settings.usbPort.toInt(), settings.usbBaud.toInt().takeIf { it > 0 } ?: profile.serial.baud)
    }

    fun disconnect() {
        worker?.cancel(); worker = null
        connected = false
        status = "Not connected (audio-only)"
    }

    private fun poll(r: CatRig, step: Int) {
        if (!held("freq")) r.getFrequency()?.let { freqHz = it }
        when (step % 4) {
            0 -> if (!held("mode")) r.getMode()?.let { modeIdx = it }
            1 -> if (!held("bw")) r.getBandwidth(modeIdx)?.let { bwIdx = it }
            2 -> if (!held("power")) r.getPower()?.let { powerW = it }
        }
        r.getSmeter()?.let { smeter = it / 100f }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun run(block: CatRig.() -> Unit) { if (connected) tasks.trySend(block) }
    private fun hold(field: String) { heldUntil[field] = System.currentTimeMillis() + 1500 }
    private fun held(field: String) = (heldUntil[field] ?: 0) > System.currentTimeMillis()
    private fun persist() = prefs.edit().putLong("freq", freqHz).putInt("mode", modeIdx).putInt("bw", bwIdx).putInt("power", powerW).apply()

    override fun onCleared() { disconnect() }
}
