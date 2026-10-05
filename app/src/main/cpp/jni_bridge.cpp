#include <jni.h>
#include "audio_engine.h"
#include "spectrum.h"
#include "psk_demod.h"
#include "mfsk_demod.h"
#include "olivia_demod.h"
#include "wefax_demod.h"
#include <memory>
#include <mutex>
#include <string>

namespace {
AudioEngine gAudio;
Spectrum gSpectrum(8192, 16384);
std::vector<float> gFrame(8192);
std::vector<float> gBins(8192);
std::mutex gModemMutex;
std::unique_ptr<PskDemod> gPsk;
std::unique_ptr<MfskDemod> gMfsk;
std::unique_ptr<OliviaDemod> gOlivia;
std::unique_ptr<WefaxDemod> gFax;
// Fax settings survive decoder re-creation (e.g. when the audio source restarts).
double gFaxLpm = 0, gFaxSlant = 0, gFaxShift = 0;
int gFaxFilter = -1;
bool gFaxManual = false;
bool gFaxAuto = true;     // automatic slant (survives decoder re-creation)
int gFaxAutoStop = 400;   // lines after which auto slant stops refining
bool gReverse = false;  // opposite sideband (QPSK, MFSK, Olivia)
bool gAfc = true;       // automatic frequency control (PSK, MFSK, WEFAX)
// 0..2 = BPSK31/63/125, 3..5 = QPSK31/63/125, 6..12 = MFSK4/8/16/31/32/64/128,
// 13..37 = Olivia: 13 + (toneExp - 1) * 5 + bwExp, toneExp 1..5 (4..64 tones), bwExp 0..4 (125..2000 Hz),
// 38 = WEFAX IOC576, 39 = WEFAX IOC288
int gModemId = 0;
float gCarrier = 1000.f;
float gSquelch = 0.3f;
uint64_t gCursor = 0;
}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_app_radiomodem_Dsp_version(JNIEnv *env, jobject) {
    return env->NewStringUTF("radiodsp 0.1");
}

JNIEXPORT jboolean JNICALL Java_app_radiomodem_Dsp_startAudio(JNIEnv *, jobject, jint deviceId, jint rate) {
    std::lock_guard<std::mutex> l(gModemMutex);
    gPsk.reset();
    return gAudio.start(deviceId, rate);
}

JNIEXPORT void JNICALL Java_app_radiomodem_Dsp_startVirtual(JNIEnv *, jobject, jint rate) {
    std::lock_guard<std::mutex> l(gModemMutex);
    gPsk.reset();
    gAudio.startVirtual(rate);
}

JNIEXPORT void JNICALL Java_app_radiomodem_Dsp_inject(JNIEnv *env, jobject, jfloatArray samples) {
    jsize n = env->GetArrayLength(samples);
    jfloat *p = env->GetFloatArrayElements(samples, nullptr);
    gAudio.inject(p, n);
    env->ReleaseFloatArrayElements(samples, p, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_app_radiomodem_Dsp_stopAudio(JNIEnv *, jobject) { gAudio.stop(); }

JNIEXPORT void JNICALL Java_app_radiomodem_Dsp_setSquelch(JNIEnv *, jobject, jfloat t) {
    std::lock_guard<std::mutex> l(gModemMutex);
    gSquelch = t;
    if (gPsk) gPsk->setSquelch(t);
    if (gMfsk) gMfsk->setSquelch(t);
    if (gOlivia) gOlivia->setSquelch(t);
}

JNIEXPORT jfloat JNICALL Java_app_radiomodem_Dsp_quality(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> l(gModemMutex);
    return gPsk ? gPsk->quality() : gMfsk ? gMfsk->quality() : gOlivia ? gOlivia->quality() : 0.f;
}

JNIEXPORT jint JNICALL Java_app_radiomodem_Dsp_sampleRate(JNIEnv *, jobject) { return gAudio.sampleRate(); }

// Fills `out` with up to out.size dB bins (bin width = sampleRate / 16384). Returns bins written, 0 if no data yet.
JNIEXPORT jint JNICALL Java_app_radiomodem_Dsp_readSpectrum(JNIEnv *env, jobject, jfloatArray out) {
    if (!gAudio.running() || !gAudio.latest(gFrame.data(), gSpectrum.windowSize())) return 0;
    jsize n = std::min<jsize>(env->GetArrayLength(out), (jsize)gBins.size());
    gSpectrum.compute(gFrame.data(), gBins.data(), n);
    env->SetFloatArrayRegion(out, 0, n, gBins.data());
    return n;
}


// id: see gModemId. Recreates the demodulator (resets sync/varicode state).
JNIEXPORT void JNICALL Java_app_radiomodem_Dsp_setModem(JNIEnv *, jobject, jint id, jfloat carrierHz) {
    std::lock_guard<std::mutex> l(gModemMutex);
    gModemId = id;
    gCarrier = carrierHz;
    gPsk.reset();
    gMfsk.reset();
    gOlivia.reset();
    gFax.reset();
    gFaxLpm = gFaxSlant = gFaxShift = 0; gFaxFilter = -1; gFaxManual = false;  // gFaxAuto is a preference: kept
}

JNIEXPORT void JNICALL Java_app_radiomodem_Dsp_setCarrier(JNIEnv *, jobject, jfloat carrierHz) {
    std::lock_guard<std::mutex> l(gModemMutex);
    gCarrier = carrierHz;
    if (gPsk) gPsk->setCarrier(carrierHz);
    if (gMfsk) gMfsk->setCarrier(carrierHz);
    if (gOlivia) gOlivia->setCarrier(carrierHz);
    if (gFax) gFax->setCenter(carrierHz);
}

// Runs the PSK demodulator over audio captured since the last call; returns decoded text.
JNIEXPORT jstring JNICALL Java_app_radiomodem_Dsp_decode(JNIEnv *env, jobject) {
    static float chunk[4800];
    std::lock_guard<std::mutex> l(gModemMutex);
    if (!gAudio.running()) return env->NewStringUTF("");
    if (!gPsk && !gMfsk && !gOlivia && !gFax) {
        if (gModemId < 6) {
            gPsk = std::make_unique<PskDemod>(gAudio.sampleRate(), (PskDemod::Baud)(gModemId % 3), gCarrier, gModemId >= 3);
            gPsk->setSquelch(gSquelch);
            gPsk->setReverse(gReverse); gPsk->setAfc(gAfc);
        } else if (gModemId < 13) {
            gMfsk = std::make_unique<MfskDemod>(gAudio.sampleRate(), (MfskDemod::Mode)(gModemId - 6), gCarrier);
            gMfsk->setSquelch(gSquelch);
            gMfsk->setReverse(gReverse); gMfsk->setAfc(gAfc);
        } else if (gModemId >= 38) {
            gFax = std::make_unique<WefaxDemod>(gAudio.sampleRate(), gModemId == 38 ? 576 : 288, gCarrier);
            if (gFaxFilter >= 0) gFax->setFilter(gFaxFilter);
            if (gFaxLpm > 0) gFax->setLpm(gFaxLpm);
            gFax->setSlantPercent(gFaxSlant);
            gFax->setShift(gFaxShift);
            gFax->setAfc(gAfc);
            gFax->setAutoSlant(gFaxAuto, gFaxAutoStop);
            if (gFaxManual) gFax->setManual(true);
        } else {
            int o = gModemId - 13;
            gOlivia = std::make_unique<OliviaDemod>(gAudio.sampleRate(), o / 5 + 1, o % 5, gCarrier);
            gOlivia->setSquelch(gSquelch);
            gOlivia->setReverse(gReverse);
        }
        gCursor = gAudio.totalFrames();
    }
    std::string text;
    int n;
    while ((n = gAudio.readNew(chunk, 4800, gCursor)) > 0) {
        if (gPsk) gPsk->process(chunk, n, text);
        else if (gMfsk) gMfsk->process(chunk, n, text);
        else if (gOlivia) gOlivia->process(chunk, n, text);
        else gFax->process(chunk, n);
    }
    // AFC moves the carrier inside the decoder; remember it so a re-created decoder starts where it was.
    if (gPsk) gCarrier = gPsk->carrier(); else if (gMfsk) gCarrier = gMfsk->carrier(); else if (gFax) gCarrier = gFax->center();
    // Control bytes other than CR/LF are dropped; bytes are Latin-1, so map via UTF-16.
    std::u16string u;
    for (unsigned char c : text) if (c >= 32 || c == '\n' || c == '\r') u.push_back(c);
    return env->NewString(reinterpret_cast<const jchar *>(u.data()), (jsize)u.size());
}


// ---- weather fax -------------------------------------------------------------------------------

// Returns new image rows (see WefaxDemod::poll for the header), or an empty array when not in fax mode.
JNIEXPORT jbyteArray JNICALL Java_app_radiomodem_Dsp_faxPoll(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> l(gModemMutex);
    if (!gFax) return env->NewByteArray(0);
    static std::vector<uint8_t> buf;
    buf.clear();
    gFax->poll(buf);
    jbyteArray out = env->NewByteArray((jsize)buf.size());
    env->SetByteArrayRegion(out, 0, (jsize)buf.size(), reinterpret_cast<const jbyte *>(buf.data()));
    return out;
}

// [state, imageId, lpm, measuredLpm, manual, autoSlantPercent]
JNIEXPORT jdoubleArray JNICALL Java_app_radiomodem_Dsp_faxInfo(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> l(gModemMutex);
    double v[6] = {0, 0, gFaxLpm, 0, gFaxManual ? 1.0 : 0.0, 0};
    if (gFax) { v[0] = gFax->state(); v[1] = gFax->imageId(); v[2] = gFax->lpm(); v[3] = gFax->measuredLpm(); v[4] = gFax->manual(); v[5] = gFax->autoSlantPercent(); }
    jdoubleArray out = env->NewDoubleArray(6);
    env->SetDoubleArrayRegion(out, 0, 6, v);
    return out;
}

JNIEXPORT void JNICALL Java_app_radiomodem_Dsp_faxSet(JNIEnv *, jobject, jdouble lpm, jdouble slantPct, jdouble shift, jint filter) {
    std::lock_guard<std::mutex> l(gModemMutex);
    gFaxLpm = lpm; gFaxSlant = slantPct; gFaxShift = shift; gFaxFilter = filter;
    if (!gFax) return;
    if (filter >= 0) gFax->setFilter(filter);
    if (lpm > 0) gFax->setLpm(lpm);
    gFax->setSlantPercent(slantPct);
    gFax->setShift(shift);
}

JNIEXPORT void JNICALL Java_app_radiomodem_Dsp_faxAutoSlant(JNIEnv *, jobject, jboolean on, jint stopLines) {
    std::lock_guard<std::mutex> l(gModemMutex);
    gFaxAuto = on; gFaxAutoStop = stopLines;
    if (gFax) gFax->setAutoSlant(on, stopLines);
}

JNIEXPORT void JNICALL Java_app_radiomodem_Dsp_faxManual(JNIEnv *, jobject, jboolean manual) {
    std::lock_guard<std::mutex> l(gModemMutex);
    gFaxManual = manual;
    if (gFax) gFax->setManual(manual);
}

JNIEXPORT void JNICALL Java_app_radiomodem_Dsp_faxRestart(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> l(gModemMutex);
    if (gFax) gFax->restart();
}


JNIEXPORT void JNICALL Java_app_radiomodem_Dsp_setReverse(JNIEnv *, jobject, jboolean on) {
    std::lock_guard<std::mutex> l(gModemMutex);
    gReverse = on;
    if (gPsk) gPsk->setReverse(on);
    if (gMfsk) gMfsk->setReverse(on);
    if (gOlivia) gOlivia->setReverse(on);
}

JNIEXPORT void JNICALL Java_app_radiomodem_Dsp_setAfc(JNIEnv *, jobject, jboolean on) {
    std::lock_guard<std::mutex> l(gModemMutex);
    gAfc = on;
    if (gPsk) gPsk->setAfc(on);
    if (gMfsk) gMfsk->setAfc(on);
    if (gFax) gFax->setAfc(on);
}

// Current audio carrier of the active decoder (AFC moves it away from what the user tapped).
JNIEXPORT jfloat JNICALL Java_app_radiomodem_Dsp_carrier(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> l(gModemMutex);
    return gPsk ? gPsk->carrier() : gMfsk ? gMfsk->carrier() : gFax ? gFax->center() : gOlivia ? gOlivia->carrier() : gCarrier;
}


// MFSK picture rows received since the last call (see MfskDemod::pollPicture), or an empty array.
JNIEXPORT jbyteArray JNICALL Java_app_radiomodem_Dsp_picPoll(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> l(gModemMutex);
    static std::vector<uint8_t> buf;
    buf.clear();
    if (gMfsk) gMfsk->pollPicture(buf);
    jbyteArray out = env->NewByteArray((jsize)buf.size());
    if (!buf.empty()) env->SetByteArrayRegion(out, 0, (jsize)buf.size(), reinterpret_cast<const jbyte *>(buf.data()));
    return out;
}


JNIEXPORT void JNICALL Java_app_radiomodem_Dsp_setInputGain(JNIEnv *, jobject, jfloat g) { gAudio.setGain(g); }

// [peak, rms] linear 0..1 since the last call.
JNIEXPORT jfloatArray JNICALL Java_app_radiomodem_Dsp_level(JNIEnv *env, jobject) {
    float v[2];
    gAudio.takeLevel(v[0], v[1]);
    jfloatArray out = env->NewFloatArray(2);
    env->SetFloatArrayRegion(out, 0, 2, v);
    return out;
}

JNIEXPORT jint JNICALL Java_app_radiomodem_Dsp_inputDeviceId(JNIEnv *, jobject) { return gAudio.deviceId(); }

}
