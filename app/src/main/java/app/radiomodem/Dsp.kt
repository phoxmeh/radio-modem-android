package app.radiomodem

/** JNI bridge to the native DSP library. Single global engine instance. */
object Dsp {
    init { System.loadLibrary("radiodsp") }
    const val FFT_SIZE = 4096

    external fun version(): String
    /** deviceId 0 = system default input. Returns false if the stream could not be opened. */
    external fun startAudio(deviceId: Int, requestedRate: Int): Boolean
    external fun stopAudio()
    /** Actual rate of the opened stream (may differ from requested). */
    external fun sampleRate(): Int
    /** Fills [out] with dB bins of width sampleRate/FFT_SIZE; returns bins written (0 = no data). */
    external fun readSpectrum(out: FloatArray): Int

    /** id: 0..2 BPSK31/63/125, 3..5 QPSK31/63/125, 6..12 MFSK4/8/16/31/32/64/128, 13..37 Olivia, 38/39 WEFAX 576/288. Resets decoder state. */
    external fun setModem(id: Int, carrierHz: Float)
    external fun setCarrier(carrierHz: Float)
    /** Decodes audio captured since the previous call and returns the new text. */
    external fun decode(): String
    /** Virtual input for WAV playback: call [inject] with samples at [rate] Hz in real time. */
    external fun startVirtual(rate: Int)
    external fun inject(samples: FloatArray)
    /** Characters are only decoded while signal quality is at least [threshold] (0..1). */
    external fun setSquelch(threshold: Float)
    external fun quality(): Float

    // ---- weather fax ----
    /** New image rows since the last call: 6 little-endian int32 (generation, width, firstRow, rowCount, imageId, state) + rows. */
    external fun faxPoll(): ByteArray
    /** [state (0 waiting, 1 phasing, 2 image), imageId, lpm, measuredLpm, manual]. */
    external fun faxInfo(): DoubleArray
    /** lpm <= 0 keeps the default; filter < 0 keeps the default (0 narrow, 1 medium, 2 wide). */
    external fun faxSet(lpm: Double, slantPct: Double, shift: Double, filter: Int)
    external fun faxManual(manual: Boolean)
    external fun faxRestart()

    /** Opposite sideband for QPSK, MFSK and Olivia. No effect on BPSK or WEFAX. */
    external fun setReverse(on: Boolean)
    /** Frequency tracking for PSK, MFSK and WEFAX. Olivia tracks internally. */
    external fun setAfc(on: Boolean)
    /** Current audio carrier of the active decoder, which AFC may have moved. */
    external fun carrier(): Float

    // ---- MFSK pictures ----
    /** New picture rows: 7 little-endian int32 (pictureId, width, height, color, firstRow, rowCount, state 0/1/2) then RGB rows. */
    external fun picPoll(): ByteArray

    // ---- input ----
    /** Linear gain applied to captured audio. */
    external fun setInputGain(gain: Float)
    /** [peak, rms] of the captured audio since the previous call, linear 0..1. */
    external fun level(): FloatArray
    /** Android device id the stream actually opened on (0 if none). */
    external fun inputDeviceId(): Int
}
