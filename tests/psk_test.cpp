// Host-side test: synthesizes BPSK31/63/125 audio and checks the demodulator recovers the text.
// Build: g++ -std=c++17 -O2 -I../app/src/main/cpp psk_test.cpp ../app/src/main/cpp/psk_demod.cpp ../app/src/main/cpp/viterbi.cpp -o psk_test
#include "psk_demod.h"
#include "psk_varicode.h"
#include "viterbi.h"
#include <cmath>
#include <cstdio>
#include <random>
#include <complex>

static std::vector<float> modulate(const std::string &text, double baud, double carrier, int rate, double drift = 0) {
    std::string bits(32, '0');  // idle preamble
    for (unsigned char c : text) { bits += kPskVaricode[c]; bits += "00"; }
    bits += std::string(32, '0');
    int symLen = (int)std::lround(rate / baud);
    std::vector<float> out;
    double phase = 0;
    int sign = 1, prevSign = 1;
    for (char b : bits) {
        prevSign = sign;
        if (b == '0') sign = -sign;
        // Raised-cosine envelope: reversal => prev*cos(pi t/T) crossing zero mid-symbol.
        for (int n = 0; n < symLen; ++n) {
            double t = (double)n / symLen;
            double amp = (sign == prevSign) ? sign : prevSign * std::cos(M_PI * t);
            // Transition is centred on the symbol *boundary*: shift by half a symbol.
            out.push_back((float)(0.5 * amp * std::cos(phase)));
            phase += 2 * M_PI * (carrier + drift * out.size() / rate) / rate;
        }
    }
    return out;
}

// QPSK transmitter mirroring fldigi psk::tx_bit/tx_carriers: encoder symbol -> (4-sym)&3 -> phase step of
// 180deg + 90deg*sym', complex baseband cross-faded with a raised-cosine shape, output = I*cos + Q*sin.
static std::vector<float> modulateQpsk(const std::string &text, double baud, double carrier, int rate, bool reverse = false, double drift = 0) {
    std::string bits(64, '0');
    for (unsigned char c : text) { bits += kPskVaricode[c]; bits += "00"; }
    bits += std::string(120, '0');  // flush the Viterbi traceback
    ConvEncoder enc(5, 0x17, 0x19);
    int symLen = (int)std::lround(rate / baud);
    std::vector<float> out;
    std::complex<double> prev(1, 0);
    double theta = 0;
    for (char b : bits) {
        int s = enc.encode(b == '1') & 3;
        int sp = reverse ? s : (4 - s) & 3;  // fldigi skips the inversion when REV is set
        std::complex<double> next = prev * std::polar(1.0, M_PI + sp * M_PI / 2);
        for (int i = 0; i < symLen; ++i) {
            double a = 0.5 * std::cos(i * M_PI / symLen) + 0.5;
            std::complex<double> v = a * prev + (1 - a) * next;
            out.push_back((float)(0.5 * (v.real() * std::cos(theta) + v.imag() * std::sin(theta))));
            theta += 2 * M_PI * (carrier + drift * out.size() / rate) / rate;
        }
        prev = next;
    }
    return out;
}

static bool runQpsk(PskDemod::Baud b, double baud, double carrier, float noise, double offset, const char *name,
                    bool txReverse = false, bool rxReverse = false, bool expectOk = true) {
    const int rate = 48000;
    std::string msg = "CQ CQ de W1AW pse k 0123456789";
    auto sig = modulateQpsk(msg, baud, carrier + offset, rate, txReverse);
    std::mt19937 rng(1);
    std::normal_distribution<float> nd(0.f, noise);
    for (auto &s : sig) s += nd(rng);
    PskDemod d(rate, b, (float)carrier, true);
    d.setReverse(rxReverse);
    std::string out;
    for (size_t i = 0; i < sig.size(); i += 480) d.process(&sig[i], (int)std::min<size_t>(480, sig.size() - i), out);
    bool ok = (out.find(msg) != std::string::npos) == expectOk;
    printf("%-22s %s  got: \"%s\"\n", name, ok ? "PASS" : "FAIL", out.c_str());
    return ok;
}

// A carrier that drifts across the message (e.g. a warming-up transmitter). Without AFC the receiver ends up
// outside its tolerance; with AFC it follows. `driftHzPerS` ramps the transmit frequency linearly.
static bool runAfc(bool qpsk, double driftHzPerS, bool afc, const char *name, bool expectFull) {
    const int rate = 48000;
    std::string msg = "CQ CQ de W1AW pse k 0123456789 ";
    std::string text; for (int i = 0; i < 6; ++i) text += msg;
    auto sig = qpsk ? modulateQpsk(text, 31.25, 1000, rate, false, driftHzPerS) : modulate(text, 31.25, 1000, rate, driftHzPerS);
    std::mt19937 rng(2);
    std::normal_distribution<float> nd(0.f, 0.05f);
    for (auto &x : sig) x += nd(rng);
    const double seconds = (double)sig.size() / rate, finalHz = 1000 + driftHzPerS * seconds;
    PskDemod d(rate, PskDemod::PSK31, 1000, qpsk);
    d.setAfc(afc);
    std::string out;
    for (size_t i = 0; i < sig.size(); i += 480) d.process(&sig[i], (int)std::min<size_t>(480, sig.size() - i), out);
    // Count how many of the six repeats arrived intact.
    int intact = 0; for (size_t pos = 0; (pos = out.find(msg, pos)) != std::string::npos; pos += msg.size()) ++intact;
    bool ok = expectFull ? (intact >= 5 && std::fabs(d.carrier() - finalHz) < 2.0) : (intact < 5);
    printf("%-30s %s  repeats intact %d/6, carrier %.1f -> true %.1f\n", name, ok ? "PASS" : "FAIL", intact, d.carrier(), finalHz);
    return ok;
}

static bool run(PskDemod::Baud b, double baud, double carrier, float noise, double offset, const char *name) {
    const int rate = 48000;
    std::string msg = "CQ CQ de W1AW pse k 0123456789";
    auto sig = modulate(msg, baud, carrier + offset, rate);
    std::mt19937 rng(1);
    std::normal_distribution<float> nd(0.f, noise);
    for (auto &s : sig) s += nd(rng);
    PskDemod d(rate, b, (float)carrier);
    std::string out;
    for (size_t i = 0; i < sig.size(); i += 480) d.process(&sig[i], (int)std::min<size_t>(480, sig.size() - i), out);
    bool ok = out.find(msg) != std::string::npos;
    printf("%-22s %s  got: \"%s\"\n", name, ok ? "PASS" : "FAIL", out.c_str());
    return ok;
}

static bool noiseOnly() {
    std::mt19937 rng(7);
    std::normal_distribution<float> nd(0.f, 0.1f);
    std::vector<float> sig(48000 * 20);
    for (auto &x : sig) x = nd(rng);
    PskDemod d(48000, PskDemod::PSK31, 1000);
    std::string out;
    for (size_t i = 0; i < sig.size(); i += 480) d.process(&sig[i], 480, out);
    printf("%-22s %s  got %zu chars\n", "noise only (squelch)", out.empty() ? "PASS" : "FAIL", out.size());
    return out.empty();
}

static void writeWav(const char *path, const std::vector<float> &x, int rate) {
    FILE *f = fopen(path, "wb");
    int dataLen = (int)x.size() * 2, v;
    short s16;
    fwrite("RIFF", 1, 4, f); v = 36 + dataLen; fwrite(&v, 4, 1, f);
    fwrite("WAVEfmt ", 1, 8, f); v = 16; fwrite(&v, 4, 1, f);
    s16 = 1; fwrite(&s16, 2, 1, f); fwrite(&s16, 2, 1, f);
    fwrite(&rate, 4, 1, f); v = rate * 2; fwrite(&v, 4, 1, f);
    s16 = 2; fwrite(&s16, 2, 1, f); s16 = 16; fwrite(&s16, 2, 1, f);
    fwrite("data", 1, 4, f); fwrite(&dataLen, 4, 1, f);
    for (float v2 : x) { s16 = (short)(std::max(-1.f, std::min(1.f, v2)) * 32000); fwrite(&s16, 2, 1, f); }
    fclose(f);
}

int main(int argc, char **argv) {
    if (argc > 2 && std::string(argv[1]) == "--wav") {  // psk_test --wav out.wav : 30 s of PSK31 @1000 Hz
        std::mt19937 rng(3);
        std::normal_distribution<float> nd(0.f, 0.03f);
        std::vector<float> all(48000 * 3);
        for (auto &x : all) x = nd(rng);
        for (int i = 0; i < 3; ++i) {
            auto m = modulate("CQ CQ CQ de W1AW W1AW pse k\n", 31.25, 1000, 48000);
            all.insert(all.end(), m.begin(), m.end());
        }
        for (auto &x : all) x += nd(rng);
        writeWav(argv[2], all, 48000);
        return 0;
    }
    bool ok = true;
    ok &= noiseOnly();
    ok &= runQpsk(PskDemod::PSK31, 31.25, 1000, 0.0f, 0, "qpsk31 clean");
    ok &= runQpsk(PskDemod::PSK31, 31.25, 1500, 0.05f, 0, "qpsk31 noise 0.05");
    ok &= runQpsk(PskDemod::PSK63, 62.5, 1000, 0.05f, 0, "qpsk63 noise");
    ok &= runQpsk(PskDemod::PSK125, 125.0, 1000, 0.05f, 0, "qpsk125 noise");
    // REV: a signal sent with fldigi's reverse flag only decodes with reverse on, and vice versa.
    ok &= runQpsk(PskDemod::PSK31, 31.25, 1000, 0.05f, 0, "qpsk31 rev tx+rx", true, true, true);
    ok &= runQpsk(PskDemod::PSK31, 31.25, 1000, 0.05f, 0, "qpsk31 rev tx only", true, false, false);
    ok &= runQpsk(PskDemod::PSK31, 31.25, 1000, 0.05f, 0, "qpsk31 rev rx only", false, true, false);
    // AFC on a drifting carrier (about 20 Hz over the message for BPSK, 8 Hz for QPSK's tighter pull-in range).
    ok &= runAfc(false, 0.5, false, "bpsk31 drift AFC off", false);
    ok &= runAfc(false, 0.5, true, "bpsk31 drift AFC on", true);
    ok &= runAfc(false, -0.5, true, "bpsk31 drift down AFC on", true);
    ok &= runAfc(true, 0.2, false, "qpsk31 drift AFC off", false);
    ok &= runAfc(true, 0.2, true, "qpsk31 drift AFC on", true);
    return ok ? 0 : 1;
}
