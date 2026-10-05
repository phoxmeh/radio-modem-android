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
import androidx.compose.runtime.mutableStateListOf
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
    private val store = RigStore(app)
    private val settings = AppSettings.get(app)

    /** Saved rigs; the active one supplies the profile, wiring and tuning defaults. */
    val rigs = mutableStateListOf<RigConfig>().apply {
        addAll(store.load {
            // Upgrade from the single-rig version: keep the USB adapter and rate that were remembered.
            RigConfig(name = "FT-891", profileId = "FT-891", usbVendor = settings.usbVendor.toInt(), usbProduct = settings.usbProduct.toInt(),
                usbPort = settings.usbPort.toInt(), baud = settings.usbBaud.toInt())
        })
    }
    var activeId by mutableStateOf(rigs.firstOrNull { it.id == store.activeId }?.id ?: rigs.first().id); private set
    val config: RigConfig get() = rigs.first { it.id == activeId }

    var profile: RigProfile by mutableStateOf(RigProfiles.load(app, config.profileId)); private set
    private var prefs = rigPrefs(activeId)
    private fun rigPrefs(id: String) = getApplication<Application>().getSharedPreferences("rig_$id", Context.MODE_PRIVATE)
    private fun usbIndex(p: RigProfile) = p.modes.indexOfFirst { it.name == "USB" }.coerceAtLeast(0)

    var freqHz by mutableLongStateOf(prefs.getLong("freq", profile.defaultFreq)); private set
    var modeIdx by mutableIntStateOf(prefs.getInt("mode", usbIndex(profile)).coerceIn(0, profile.modes.lastIndex)); private set
    var bwIdx by mutableIntStateOf(prefs.getInt("bw", 0)); private set
    var powerW by mutableIntStateOf(prefs.getInt("power", profile.powerMin)); private set
    var smeter by mutableFloatStateOf(0f); private set
    var connected by mutableStateOf(false); private set
    private var rawStatus by mutableStateOf("Not connected (audio-only)")
    /** Connection status for the active rig. */
    var status: String
        get() = if (profile.audioOnly) "Audio only: no CAT link. Tune the radio by hand." else rawStatus
        private set(v) { rawStatus = v }

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

    // ---- saved rigs ---------------------------------------------------------------------------

    /** Switches to another saved rig: its model, last frequency and mode, and its USB adapter (reconnected if present). */
    fun selectRig(id: String) {
        if (id == activeId || rigs.none { it.id == id }) return
        disconnect()
        activeId = id; store.activeId = id
        loadActive()
        autoTried.clear()
        autoConnect()
    }

    private fun loadActive() {
        profile = RigProfiles.load(getApplication(), config.profileId)
        prefs = rigPrefs(activeId)
        freqHz = prefs.getLong("freq", profile.defaultFreq)
        modeIdx = prefs.getInt("mode", usbIndex(profile)).coerceIn(0, profile.modes.lastIndex)
        bwIdx = prefs.getInt("bw", 0)
        powerW = prefs.getInt("power", profile.powerMin)
    }

    fun updateConfig(c: RigConfig) {
        val i = rigs.indexOfFirst { it.id == c.id }.takeIf { it >= 0 } ?: return
        val modelChanged = rigs[i].profileId != c.profileId
        rigs[i] = c; store.save(rigs.toList())
        if (c.id == activeId && modelChanged) { disconnect(); loadActive() }
    }

    /** Adds a rig of the given model without switching to it; returns its id so its settings can be opened. */
    fun addRig(profileId: String): String {
        val model = RigProfiles.load(getApplication(), profileId)
        val c = RigConfig(name = model.name, profileId = model.id, defaultMode = if (model.audioOnly) "" else "USB")
        rigs.add(c); store.save(rigs.toList())
        return c.id
    }

    fun deleteRig(id: String) {
        if (rigs.size <= 1) return
        val wasActive = id == activeId
        if (wasActive) selectRig(rigs.first { it.id != id }.id)
        rigs.removeAll { it.id == id }; store.save(rigs.toList())
        getApplication<Application>().deleteSharedPreferences("rig_$id")
    }

    /**
     * Sets mode, frequency and bandwidth for a tap on a saved frequency or schedule entry. The rig's own defaults
     * (Rig setup) apply wherever the caller gives none.
     */
    fun applyTuningDefaults(mode: String = "", bandwidth: String? = null, freqHz: Long? = null) {
        val wanted = mode.ifBlank { config.defaultMode }
        // Rigs without a DATA mode fall back to the plain sideband.
        val idx = listOf(wanted, when (wanted) { "DATA-U" -> "USB"; "DATA-L" -> "LSB"; else -> wanted })
            .firstNotNullOfOrNull { n -> profile.modes.indexOfFirst { it.name == n }.takeIf { it >= 0 } }
        if (idx != null && idx != modeIdx) setMode(idx)
        freqHz?.let { setFrequency(it) }
        val label = bandwidth ?: config.defaultBw.ifEmpty { null }
        label?.let { l -> bwTable?.labels?.indexOf(l)?.takeIf { it >= 0 && it != bwIdx }?.let { setBandwidth(it) } }
    }

    // ---- connection ---------------------------------------------------------------------------

    /** Connects the active rig through [driver]; the adapter and port are remembered in the rig for next time. */
    fun connect(driver: UsbSerialDriver, portIndex: Int, baud: Int) {
        val c = config
        if (c.usbVendor != driver.device.vendorId || c.usbProduct != driver.device.productId || c.usbPort != portIndex || c.baud != baud)
            updateConfig(c.copy(usbVendor = driver.device.vendorId, usbProduct = driver.device.productId, usbPort = portIndex, baud = baud))
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
        val cfg = config
        val prof = profile
        // DTR/RTS are left alone unless the rig's settings say otherwise: on many radios they key PTT.
        try {
            port.open(conn)
            val stop = when (cfg.stopBits) { 2 -> UsbSerialPort.STOPBITS_2; else -> UsbSerialPort.STOPBITS_1 }
            port.setParameters(baud, cfg.dataBits, stop, cfg.parity)
            // Not every adapter chip supports hardware flow control; a refusal is not fatal.
            when (cfg.flow) {
                "rtscts" -> runCatching { port.setFlowControl(UsbSerialPort.FlowControl.RTS_CTS) }
                "xonxoff" -> runCatching { port.setFlowControl(UsbSerialPort.FlowControl.XON_XOFF) }
            }
            when (cfg.dtr) { "low" -> runCatching { port.dtr = false }; "high" -> runCatching { port.dtr = true } }
            when (cfg.rts) { "low" -> runCatching { port.rts = false }; "high" -> runCatching { port.rts = true } }
        } catch (e: Exception) { status = "Open failed: ${e.message}"; return }
        status = "Connecting…"
        worker = viewModelScope.launch(Dispatchers.IO) {
            val r = CatRig(prof, UsbSerialLink(port))
            if (!r.identify()) {
                r.close(); status = "No answer from ${prof.name} (check CAT rate ${baud} and port)"; return@launch
            }
            rig = r; connected = true; status = "Connected: ${cfg.name} (${prof.name})"
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
        val c = config
        if (!settings.autoReconnect || connected || c.usbVendor < 0) return
        val driver = drivers().firstOrNull { it.device.vendorId == c.usbVendor && it.device.productId == c.usbProduct }
        if (driver == null) { autoTried.clear(); return }  // unplugged: allow another attempt when it returns
        if (!autoTried.add(driver.device.deviceId)) return
        connect(driver, c.usbPort, c.baud.takeIf { it > 0 } ?: profile.serial.baud)
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
