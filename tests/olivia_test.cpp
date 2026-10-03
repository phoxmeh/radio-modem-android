// Host-side Olivia test: a transmitter driven like fldigi olivia::tx_process feeds OliviaDemod.
// Build/run all host tests with tests/run_host_tests.sh
#include "jalocha/pj_mfsk.h"
#include "olivia_demod.h"
#include <cstdio>
#include <random>

// Same value as ModemInfo.defaultSquelch for Olivia in Modems.kt.
static const float kOliviaSquelch = 0.12f;

static std::vector<float> transmit(const std::string &text, int toneExp, int bwExp, double center, int rate, bool reverse = false) {
    MFSK_Transmitter<double> tx;
    const int bandwidth = 125 * (1 << bwExp);
    tx.Tones = 2 * (1 << toneExp);
    tx.Bandwidth = bandwidth;
    tx.SampleRate = 8000;
    tx.OutputSampleRate = 8000;  // fldigi always runs the transmitter at 8 kHz
    double fc = tx.Bandwidth * (1.0 - 0.5 / tx.Tones) / 2.0;
    tx.FirstCarrierMultiplier = (reverse ? center + fc : center - fc) / 500.0;  // as fldigi olivia::tx_init
    tx.Reverse = reverse ? 1 : 0;
    tx.Preset();
    tx.Start();
    std::vector<double> buf(tx.MaxOutputLen);
    std::vector<float> out;
    size_t next = 0; bool stopped = false;
    while (tx.Running()) {
        if (!stopped && tx.GetReadReady() < tx.BitsPerSymbol) {
            if (next < text.size()) tx.PutChar(text[next++]);
            else { tx.Stop(); stopped = true; }
        }
        int len = tx.Output(buf.data());
        for (int i = 0; i < len; ++i) out.push_back((float)buf[i]);
    }
    // Linear-interpolation upsample to the requested rate (images land above 4 kHz and are filtered by the receiver).
    if (rate == 8000) return out;
    const int k = rate / 8000;
    std::vector<float> up;
    up.reserve(out.size() * k);
    for (size_t i = 0; i + 1 < out.size(); ++i)
        for (int j = 0; j < k; ++j) up.push_back(out[i] + (out[i + 1] - out[i]) * j / k);
    return up;
}

static bool run(int toneExp, int bwExp, float noise, const char *label, float squelch = 0.f,
                bool txRev = false, bool rxRev = false, bool expectOk = true) {
    const int rate = 48000;
    const std::string msg = "CQ CQ de W1AW pse k";
    std::string payload = "  "; for (int i = 0; i < 6 * std::max(1, 500 / (125 << bwExp)); ++i) payload += msg + " ";  // slow modes need more airtime to sync
    auto sig = transmit(payload + "   ", toneExp, bwExp, 1500, rate, txRev);
    sig.insert(sig.begin(), 48000, 0.f);  // lead-in silence
    for (int i = 0; i < 48000 * 3; ++i) sig.push_back(0.f);
    std::mt19937 rng(11);
    std::normal_distribution<float> nd(0.f, noise);
    for (auto &s : sig) s += nd(rng);
    OliviaDemod d(rate, toneExp, bwExp, 1500);
    d.setReverse(rxRev);
    d.setSquelch(squelch);
    std::string out;
    for (size_t i = 0; i < sig.size(); i += 480) d.process(&sig[i], (int)std::min<size_t>(480, sig.size() - i), out);
    bool ok = (out.find(msg) != std::string::npos) == expectOk;
    std::string shown; for (char c : out) shown += (c >= 32 && c < 127) ? c : '.';
    printf("Olivia %2d/%-4d %-12s %s  got: \"%s\"\n", 2 << toneExp, 125 << bwExp, label, ok ? "PASS" : "FAIL", shown.c_str());
    return ok;
}

int main() {
    bool ok = true;
    // Every tone/bandwidth combination offered in the UI (4..64 tones x 125..2000 Hz).
    for (int t = 1; t <= 5; ++t)
        for (int b = 0; b <= 4; ++b) {
            // Lower rates need the carrier block to fit in the 8 kHz band; all combos here do at 1500 Hz center.
            ok &= run(t, b, 0.0f, "clean", kOliviaSquelch);
        }
    ok &= run(3, 2, 0.35f, "noise 0.35", kOliviaSquelch);
    ok &= run(3, 2, 0.0f, "rev tx+rx", kOliviaSquelch, true, true, true);
    ok &= run(3, 2, 0.0f, "rev tx only", kOliviaSquelch, true, false, false);
    ok &= run(3, 2, 0.0f, "rev rx only", kOliviaSquelch, false, true, false);
    ok &= run(2, 3, 0.35f, "noise 0.35", kOliviaSquelch);
    // Noise alone must not produce text at the default squelch.
    {
        std::mt19937 rng(9); std::normal_distribution<float> nd(0.f, 0.3f);
        std::vector<float> n(48000 * 30); for (auto &x : n) x = nd(rng);
        OliviaDemod d(48000, 3, 2, 1500); d.setSquelch(kOliviaSquelch); std::string out;
        for (size_t i = 0; i < n.size(); i += 480) d.process(&n[i], 480, out);
        printf("Olivia 16/500   noise only   %s  (%zu chars)\n", out.empty() ? "PASS" : "FAIL", out.size());
        ok &= out.empty();
    }
    return ok ? 0 : 1;
}
