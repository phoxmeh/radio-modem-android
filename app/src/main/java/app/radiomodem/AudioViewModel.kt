package app.radiomodem

import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import app.radiomodem.settings.AppSettings
import app.radiomodem.settings.LineStamper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Owns audio capture and the waterfall bitmap. Newest spectrum row is at the top. */
class AudioViewModel(app: Application) : AndroidViewModel(app) {
    val settings = AppSettings.get(app)

    companion object {
        private val USABLE_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
        const val MAX_HZ = 4000f
        const val FAX_CENTER_HZ = 1900f
        const val HISTORY = 1024
        const val MIN_SPAN_HZ = 250f
    }

    var running by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set
    /** Bumped on every new row so Compose redraws. */
    var frame by mutableIntStateOf(0); private set

    // Display state (audio Hz). rxHz is the modem carrier.
    var rxHz by mutableFloatStateOf(1000f); private set
    fun tuneTo(hz: Float) { val h = hz.coerceIn(0f, MAX_HZ); rxHz = h; settings.lastCarrierHz = h; Dsp.setCarrier(h) }
    var squelch by mutableFloatStateOf(0.3f); private set
    fun updateSquelch(v: Float) { squelch = v; settings.lastSquelch = v; Dsp.setSquelch(v) }
    /** Opposite sideband: needed for QPSK, MFSK and Olivia when the receiver is on the other sideband from the sender. */
    var reverse by mutableStateOf(false); private set
    fun updateReverse(on: Boolean) { reverse = on; settings.lastReverse = on; Dsp.setReverse(on) }
    var afc by mutableStateOf(true); private set
    fun updateAfc(on: Boolean) { afc = on; settings.lastAfc = on; Dsp.setAfc(on) }

    /** Index into [Modems.all]. */
    var modemId by mutableIntStateOf(0); private set

    init {
        MonitorService.stopHandler = { stopMonitoring() }
        if (settings.restoreState) {
            modemId = settings.lastModem.toInt().coerceIn(0, Modems.all.lastIndex)
            rxHz = settings.lastCarrierHz
            squelch = if (settings.lastSquelch >= 0f) settings.lastSquelch else settings.squelchFor(Modems.all[modemId].family)
            afc = settings.lastAfc
            reverse = settings.lastReverse
        } else {
            squelch = settings.squelchFor(Modems.all[0].family)
            afc = settings.defaultAfc
            reverse = settings.defaultReverse
            settings.viewStartHz = 0f
            settings.viewSpanHz = settings.waterfallDefaultSpanHz
        }
    }

    private val lastByFamily = HashMap<String, Int>()
    /** Switching family returns to the variant you last used in it (or a sensible default). */
    fun selectFamily(family: String) = selectModem(lastByFamily[family] ?: Modems.familyDefault(family))

    fun selectModem(id: Int) {
        lastByFamily[Modems.all[id].family] = id
        // Squelch has a different useful range per family, so switching family resets it to that family's default.
        if (Modems.all[id].family != Modems.all[modemId].family) { squelch = settings.squelchFor(Modems.all[id].family); settings.lastSquelch = squelch }
        settings.lastModem = id.toFloat()
        logger.note("mode ${Modems.all[id].name}")
        stamper.reset()
        val enteringFax = Modems.all[id].family == "WEFAX" && Modems.all[modemId].family != "WEFAX"
        modemId = id; Dsp.setModem(id, rxHz); Dsp.setSquelch(squelch)
        if (Modems.all[id].family == "WEFAX") {
            if (enteringFax) tuneTo(FAX_CENTER_HZ)  // the fax subcarrier sits at 1900 Hz when tuned USB at assigned - 1.9 kHz
            resetFaxState()
            applyFax(); applyFaxAuto()
        }
    }

    // ---- weather fax -----------------------------------------------------------------------------------

    val isFax get() = Modems.all[modemId].family == "WEFAX"
    /** 0 waiting/finished, 1 phasing, 2 receiving. */
    var faxState by mutableIntStateOf(0); private set
    var faxRows by mutableIntStateOf(0); private set
    var faxFrame by mutableIntStateOf(0); private set
    var faxMeasuredLpm by mutableFloatStateOf(0f); private set
    var faxLpm by mutableIntStateOf(0); private set       // 0 = default for the IOC (120 / 60)
    var faxSlant by mutableFloatStateOf(0f); private set  // percent
    var faxShift by mutableFloatStateOf(0f); private set  // fraction of the line width
    var faxFilter by mutableIntStateOf(-1); private set   // -1 = default for the IOC
    var faxManual by mutableStateOf(false); private set
    /** Slant currently applied by the automatic correction (percent), on top of [faxSlant]. */
    var faxAutoSlantNow by mutableFloatStateOf(0f); private set
    var faxFollow by mutableStateOf(true)
    /** Auto-save of finished pictures (fax and MFSK), from Settings > Pictures. */
    var faxAutoSave: Boolean get() = settings.pictureAutoSave; set(v) { settings.pictureAutoSave = v }
    var faxBitmap: Bitmap? = null; private set
    var faxWidth = 0; private set

    private var faxPixels = IntArray(0)
    private var faxCapacityRows = 0
    private var faxGeneration = -1
    private var faxImageId = -1
    private var faxSaved = true
    private var lastFaxState = 0

    private fun resetFaxState() {
        faxRows = 0; faxBitmap = null; faxWidth = 0; faxCapacityRows = 0; faxGeneration = -1; faxImageId = -1
        faxLpm = 0; faxSlant = 0f; faxShift = 0f; faxFilter = -1; faxManual = false; faxSaved = true; lastFaxState = 0
    }

    private fun applyFax() = Dsp.faxSet(faxLpm.toDouble(), faxSlant.toDouble(), faxShift.toDouble(), faxFilter)

    fun updateFaxLpm(v: Int) { faxLpm = v; applyFax() }
    fun updateFaxSlant(v: Float) { faxSlant = v; applyFax() }
    fun updateFaxShift(v: Float) { faxShift = v; applyFax() }
    fun updateFaxFilter(v: Int) { faxFilter = v; applyFax() }
    fun applyFaxAuto() = Dsp.faxAutoSlant(settings.faxAutoSlant, settings.faxAutoStopLines.toInt())
    fun updateFaxAutoSlant(on: Boolean) { settings.faxAutoSlant = on; applyFaxAuto() }
    fun updateFaxAutoStop(lines: Int) { settings.faxAutoStopLines = lines.toFloat(); applyFaxAuto() }
    fun updateFaxManual(on: Boolean) { faxManual = on; Dsp.faxManual(on) }
    /** Discards the current picture and waits for the next APT start tone. */
    fun faxRestart() { faxManual = false; Dsp.faxManual(false); Dsp.faxRestart(); faxRows = 0; faxFrame++ }

    private fun pollFax() {
        val info = Dsp.faxInfo()
        faxMeasuredLpm = info[3].toFloat()
        faxAutoSlantNow = info[5].toFloat()
        val data = Dsp.faxPoll()
        if (data.size < 24) return
        val h = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val gen = h.getInt(0); val width = h.getInt(4); val first = h.getInt(8); val count = h.getInt(12)
        val id = h.getInt(16); val state = h.getInt(20)

        if (id != faxImageId) {
            if (faxImageId != -1 && !faxSaved) autoSaveIfWanted()
            faxImageId = id; faxSaved = false; faxRows = 0
        }
        if (gen != faxGeneration) { faxGeneration = gen; faxRows = 0 }
        if (width != faxWidth || faxBitmap == null) {
            faxWidth = width; faxCapacityRows = 0; faxBitmap = null; faxRows = 0
        }
        val rowsNeeded = first + count
        if (rowsNeeded > faxCapacityRows) growFaxBitmap(((rowsNeeded / 128) + 1) * 128)
        for (r in 0 until count) {
            val base = 6 * 4 + r * width
            for (c in 0 until width) {
                val v = data[base + c].toInt() and 0xFF
                faxPixels[c] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            faxBitmap!!.setPixels(faxPixels, 0, width, 0, first + r, width, 1)
        }
        faxRows = rowsNeeded
        if (lastFaxState == 2 && state != 2) autoSaveIfWanted()
        lastFaxState = state
        faxState = state
        faxFrame++
    }

    private fun growFaxBitmap(rows: Int) {
        val old = faxBitmap
        val bmp = Bitmap.createBitmap(faxWidth, rows, Bitmap.Config.ARGB_8888)
        if (old != null) android.graphics.Canvas(bmp).drawBitmap(old, 0f, 0f, null)
        faxBitmap = bmp; faxCapacityRows = rows
        if (faxPixels.size < faxWidth) faxPixels = IntArray(faxWidth)
    }

    private fun autoSaveIfWanted() {
        if (faxAutoSave && faxRows >= 20 && !faxSaved) saveFax()
    }

    /** Writes the current picture to Pictures/RadioModem; returns its Uri or null if there is nothing to save. */
    fun saveFax(): android.net.Uri? {
        val bmp = faxBitmap ?: return null
        if (faxRows < 1) return null
        val uri = FaxSaver.savePng(getApplication(), bmp, faxRows)
        if (uri != null) { faxSaved = true; logger.note("fax picture saved") }
        return uri
    }

    fun shareFax() {
        val uri = saveFax() ?: return
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        getApplication<Application>().startActivity(
            android.content.Intent.createChooser(send, "Share fax image").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    var quality by mutableFloatStateOf(0f); private set
    /** Null = live audio input, otherwise the name of the file being played. */
    var playingFile by mutableStateOf<String?>(null); private set
    /** Everything received, in order: decoded text runs and (MFSK) pictures. */
    val rxItems = androidx.compose.runtime.mutableStateListOf<RxItem>()
    /** Bumped whenever the feed changes, for auto-scroll. */
    var rxVersion by mutableIntStateOf(0); private set
    fun clearText() { rxItems.clear(); picId = -1; rxVersion++ }

    private val stamper = LineStamper { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) }
    val logger = SessionLogger(app, settings) { Modems.all[modemId].name + " @ " + "%.1f".format(rxHz) + " Hz" }

    private fun appendText(raw: String) {
        logger.text(raw)
        val t = if (settings.feedTimestamps) stamper.stamp(raw) else raw
        val last = rxItems.lastOrNull()
        if (last is RxItem.Text) last.text = (last.text + t).takeLast(20000)
        else rxItems.add(RxItem.Text(t))
        rxVersion++
    }

    private var picId = -1
    
    private fun pollPicture() {
        val data = Dsp.picPoll()
        if (data.size < 28) return
        val h = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val id = h.getInt(0); val w = h.getInt(4); val ht = h.getInt(8); val first = h.getInt(16); val count = h.getInt(20)
        val state = h.getInt(24)
        if (w <= 0 || ht <= 0) return
        // One feed item per picture. Ids are unique across decoder restarts, but the size must match as well, so a stale
        // item can never be written into with rows of a different shape.
        val existing = rxItems.lastOrNull { it is RxItem.Image && it.id == id } as? RxItem.Image
        val item = if (existing != null && existing.width == w && existing.height == ht) existing
                   else RxItem.Image(id, w, ht).also { rxItems.add(it) }
        picId = id
        if (count > 0 && data.size >= 28 + count * w * 3) {
            val bmp = item.bitmap ?: Bitmap.createBitmap(item.width, item.height, Bitmap.Config.ARGB_8888).also { item.bitmap = it }
            val px = IntArray(w)
            for (r in 0 until count) {
                val y = first + r
                if (y < 0 || y >= bmp.height || w != bmp.width) break
                val base = 28 + r * w * 3
                for (c in 0 until w) {
                    val i = base + c * 3
                    px[c] = (0xFF shl 24) or ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
                }
                bmp.setPixels(px, 0, w, 0, y, w, 1)
            }
            item.rows = maxOf(item.rows, minOf(first + count, item.height))
        }
        val finished = state == 2
        if (finished != item.complete) item.complete = finished
        item.frame++
        rxVersion++
        if (finished && !item.saved && faxAutoSave) saveImage(item)
    }

    fun saveImage(item: RxItem.Image): android.net.Uri? {
        val bmp = item.bitmap ?: return null
        val uri = FaxSaver.savePng(getApplication(), bmp, item.rows, "mfsk")
        if (uri != null) { item.saved = true; logger.note("picture saved (${item.width}x${item.height})") }
        return uri
    }

    fun shareImage(item: RxItem.Image) {
        val uri = saveImage(item) ?: return
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        getApplication<Application>().startActivity(
            android.content.Intent.createChooser(send, "Share picture").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    var viewStartHz: Float get() = settings.viewStartHz; set(v) { settings.viewStartHz = v }
    var viewSpanHz: Float get() = settings.viewSpanHz; set(v) { settings.viewSpanHz = v }
    var floorDb: Float get() = settings.waterfallFloorDb; set(v) { settings.waterfallFloorDb = v }
    var rangeDb: Float get() = settings.waterfallRangeDb; set(v) { settings.waterfallRangeDb = v }
    private val frameMs get() = when (settings.waterfallSpeed) { "slow" -> 100L; "fast" -> 25L; else -> 50L }

    /** Moves the carrier by [deltaHz] and scrolls the view if the marker would leave it. */
    fun nudge(deltaHz: Float) {
        tuneTo(Math.round(rxHz + deltaHz).toFloat())
        val margin = viewSpanHz * 0.08f
        if (rxHz < viewStartHz + margin || rxHz > viewStartHz + viewSpanHz - margin) centerViewOnCarrier()
    }
    fun centerViewOnCarrier() { viewStartHz = (rxHz - viewSpanHz / 2).coerceIn(0f, MAX_HZ - viewSpanHz) }
    /** Zooms the audio span about [focusHz]; the picture scales uniformly (see Waterfall). */
    fun zoomBy(factor: Float, focusHz: Float = viewStartHz + viewSpanHz / 2) {
        val span = (viewSpanHz / factor).coerceIn(MIN_SPAN_HZ, MAX_HZ)
        val frac = (focusHz - viewStartHz) / viewSpanHz
        viewSpanHz = span
        viewStartHz = (focusHz - frac * span).coerceIn(0f, MAX_HZ - span)
    }
    fun zoomFull() { viewSpanHz = MAX_HZ; viewStartHz = 0f }
    /** Centre of the usual SSB audio passband (1900 Hz for weather fax); the waterfall marks it. */
    val referenceHz get() = if (isFax) FAX_CENTER_HZ else 1500f

    var binHz = 48000f / Dsp.FFT_SIZE; private set
    private var width = (MAX_HZ / binHz).toInt()
    private var row = IntArray(width)
    /** Ring of spectrum rows; [head] is the newest, older rows follow it (wrapping). */
    var bitmap: Bitmap = Bitmap.createBitmap(width, HISTORY, Bitmap.Config.ARGB_8888); private set
    var head = 0; private set
    private var bins = FloatArray(width)
    private var job: Job? = null
    private var autoTick = 0

    fun hzToPx(hz: Float) = hz / binHz

    /** Starts listening and the foreground service that keeps it alive in the background. */
    /** What the user last asked for; resuming the app only restarts audio when this is true. */
    var monitorWanted by mutableStateOf(settings.monitorAtLaunch); private set

    fun startMonitoring(deviceId: Int = -1) {
        monitorWanted = true
        start(deviceId)
        if (running) MonitorService.start(getApplication())
    }

    /** Stops listening and removes the notification. Audio-level problems are fixed by stopping and starting again. */
    fun stopMonitoring() {
        monitorWanted = false
        stop()
        MonitorService.stop(getApplication())
    }

    fun restartMonitoring() { stopMonitoring(); startMonitoring() }

    fun start(deviceId: Int = -1) {
        if (running) return
        failed = !Dsp.startAudio(if (deviceId >= 0) deviceId else preferredDeviceId(), 48000)
        if (failed) { inputDeviceName = ""; return }
        playingFile = null
        inputDeviceName = inputDevices().firstOrNull { it.id == Dsp.inputDeviceId() }?.let { describe(it) } ?: "System default"
        beginLoop()
    }

    // ---- input device selection --------------------------------------------------------------------

    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    /** Bumped when audio devices are plugged or unplugged, so the Audio setup list refreshes. */
    var deviceListVersion by mutableIntStateOf(0); private set
    var inputPeak by mutableFloatStateOf(0f); private set
    var inputRms by mutableFloatStateOf(0f); private set
    var inputDeviceName by mutableStateOf(""); private set
    var inputGain: Float get() = settings.audioGain; set(v) { settings.audioGain = v; Dsp.setInputGain(v) }

    /** Real capture sources only: Android also lists telephony, FM-tuner and loopback inputs that are useless here. */
    fun inputDevices(): List<AudioDeviceInfo> = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource && it.type in USABLE_TYPES }

    /** Device ids change when a USB device is replugged, so the preference is remembered by type and name. */
    private fun matchesPreferred(d: AudioDeviceInfo) =
        settings.audioDeviceType >= 0 && d.type == settings.audioDeviceType.toInt() && d.productName.toString() == settings.audioDeviceName

    fun preferredDeviceId(): Int = inputDevices().firstOrNull { matchesPreferred(it) }?.id ?: 0
    val preferredDevice: AudioDeviceInfo? get() = inputDevices().firstOrNull { matchesPreferred(it) }

    /** null = let Android choose. */
    fun selectInputDevice(d: AudioDeviceInfo?) {
        settings.audioDeviceType = d?.type?.toFloat() ?: -1f
        settings.audioDeviceName = d?.productName?.toString() ?: ""
        restartLive()
    }

    fun restartLive() { if (playingFile != null) return; stop(); start() }

    fun describe(d: AudioDeviceInfo): String {
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG -> "Wired input"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
            else -> "Audio input"
        }
        val name = d.productName?.toString().orEmpty()
        // Phones have several microphones with the same name; the address ("bottom", "back") tells them apart.
        val where = d.address.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        return (if (name.isBlank() || name == android.os.Build.MODEL) kind else "$kind: $name") + where
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            deviceListVersion++
            // The preferred input was just plugged in: switch to it (if the user wants that).
            if (settings.autoReconnect && playingFile == null && added.any { matchesPreferred(it) && it.id != Dsp.inputDeviceId() }) restartLive()
        }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            deviceListVersion++
            if (running && playingFile == null && removed.any { it.id == Dsp.inputDeviceId() }) restartLive()  // fall back to the default
        }
    }

    init { audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper())) }

    /**
     * Plays a WAV file through the decoder in real time, reading it as a stream so a recording of any length plays.
     * [open] supplies the bytes; [onError] gets a message if the file cannot be played. Live audio is left running
     * until the file is known to be playable.
     */
    fun playWav(name: String, open: () -> java.io.InputStream, onError: (String) -> Unit) {
        feeder?.cancel()
        feeder = viewModelScope.launch(Dispatchers.Default) {
            val wav = try { open().let { WavStream.open(it) } } catch (e: Exception) { onError("Could not open the file: ${e.message}"); return@launch }
            if (wav == null) { onError("Unsupported WAV file (needs 8, 16, 24 or 32-bit PCM, or 32-bit float)"); return@launch }
            try {
                job?.cancel(); job = null
                Dsp.stopAudio()
                Dsp.startVirtual(48000)
                playingFile = name
                beginLoop()
                val chunk = 2400   // 50 ms of audio at 48 kHz, fed at the pace of the clock so playback runs at real speed
                val t0 = System.nanoTime()
                var fed = 0L
                while (isActive) {
                    val block = wav.read(chunk)
                    if (block.isEmpty()) break
                    Dsp.inject(block)
                    fed += block.size
                    val due = t0 + fed * 1_000_000_000L / 48000
                    val wait = (due - System.nanoTime()) / 1_000_000
                    if (wait > 0) delay(wait)
                }
            } finally { wav.close() }
        }
    }

    private var feeder: Job? = null

    private fun beginLoop() {
        running = true
        Dsp.setModem(modemId, rxHz)
        Dsp.setSquelch(squelch)
        Dsp.setReverse(reverse)
        Dsp.setAfc(afc)
        Dsp.setInputGain(settings.audioGain)
        applyFaxAuto()
        val rate = Dsp.sampleRate()
        binHz = rate.toFloat() / Dsp.FFT_SIZE
        val w = (MAX_HZ / binHz).toInt()
        if (w != width) {
            width = w
            row = IntArray(width)
            bins = FloatArray(width)
            bitmap = Bitmap.createBitmap(width, HISTORY, Bitmap.Config.ARGB_8888)
        }
        job = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                // A bad frame (an odd picture header, odd audio) must never take the whole app down: log it and carry on.
                try {
                    if (Dsp.readSpectrum(bins) > 0) pushRow()
                    val t = Dsp.decode()
                    if (t.isNotEmpty()) appendText(t)
                    if (Modems.all[modemId].family == "MFSK") pollPicture()
                    if (isFax) pollFax()
                    quality = Dsp.quality()
                    Dsp.level().let { inputPeak = it[0]; inputRms = it[1] }
                    // AFC retunes the decoder; follow it so the waterfall marker tracks the signal.
                    if (afc && Modems.supportsAfc(Modems.all[modemId].family)) {
                        val c = Dsp.carrier()
                        if (kotlin.math.abs(c - rxHz) > 0.4f) rxHz = c
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    android.util.Log.e("RadioModem", "audio loop", e)
                }
                delay(frameMs)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        feeder?.cancel(); feeder = null
        Dsp.stopAudio()
        running = false
    }

    private fun pushRow() {
        if (settings.waterfallAuto && ++autoTick >= 8) { autoTick = 0; autoLevel() }
        head = (head + HISTORY - 1) % HISTORY
        val floor = floorDb; val range = rangeDb
        for (x in 0 until width) row[x] = colorFor((bins[x] - floor) / range)
        bitmap.setPixels(row, 0, width, 0, head, width, 1)
        frame++
    }

    /** Fits floor/range to the current band: floor just under the noise, top just over the strongest signal. */
    private fun autoLevel() {
        val lo = (150f / binHz).toInt(); val hi = minOf(width, (3600f / binHz).toInt())
        if (hi - lo < 20) return
        val sorted = bins.copyOfRange(lo, hi).also { it.sort() }
        val noise = sorted[sorted.size / 4]
        val peak = sorted[sorted.size - 1 - sorted.size / 300]
        val targetFloor = (noise - 4f).coerceIn(-130f, -30f)
        val targetRange = (maxOf(peak, noise + 22f) + 3f - targetFloor).coerceIn(25f, 90f)
        floorDb += (targetFloor - floorDb) * 0.2f
        rangeDb += (targetRange - rangeDb) * 0.2f
    }

    private fun colorFor(t0: Float): Int = Palettes.color(settings.waterfallPalette, t0)

    override fun onCleared() { audioManager.unregisterAudioDeviceCallback(deviceCallback); stopMonitoring() }
}
