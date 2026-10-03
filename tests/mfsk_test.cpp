// Host-side MFSK test: a transmitter written to mirror fldigi mfsk::sendbit/sendsymbol/tx_process feeds the receiver.
// Build: g++ -std=c++17 -O2 -I../app/src/main/cpp mfsk_test.cpp ../app/src/main/cpp/{mfsk_demod,viterbi,interleave}.cpp -o mfsk_test
#include "interleave.h"
#include "mfsk_demod.h"
#include "mfsk_varicode.h"
#include "viterbi.h"
#include <cmath>
#include <cstring>
#include <cstdio>
#include <random>

struct Params { const char *name; MfskDemod::Mode mode; int symlen, symbits, depth, numtones, preamble; };
static const Params kModes[] = {
    {"MFSK4", MfskDemod::MFSK4, 2048, 5, 5, 32, 107},   {"MFSK8", MfskDemod::MFSK8, 1024, 5, 5, 32, 107},
    {"MFSK16", MfskDemod::MFSK16, 512, 4, 10, 16, 107}, {"MFSK31", MfskDemod::MFSK31, 256, 3, 10, 8, 107},
    {"MFSK32", MfskDemod::MFSK32, 256, 4, 10, 16, 107}, {"MFSK64", MfskDemod::MFSK64, 128, 4, 10, 16, 180},
    {"MFSK128", MfskDemod::MFSK128, 64, 4, 20, 16, 214},
};

// fldigi's misnamed grayencode: prefix XOR.
static unsigned grayencode(unsigned d) { unsigned b = d; for (int i = 1; i < 8; ++i) b ^= d >> i; return b; }

struct Tx {
    Params p; double center; int rate; bool reverse = false; double drift = 0;  // drift in Hz per second
    ConvEncoder enc{7, 0x6d, 0x4f};
    Interleave inlv;
    unsigned bitshreg = 0; int bitstate = 0; double phase = 0;
    std::vector<float> out;
    Tx(const Params &p_, double c, int r) : p(p_), center(c), rate(r), inlv(p_.symbits, p_.depth, Interleave::Fwd) {}

    void sendsymbol(int sym) {
        double spacing = 8000.0 / p.symlen, bw = (p.numtones - 1) * spacing;
        sym = grayencode(sym & (p.numtones - 1));
        if (reverse) sym = (p.numtones - 1) - sym;  // fldigi mfsk::sendsymbol with REV
        int n = p.symlen * (rate / 8000);
        for (int i = 0; i < n; ++i) {
            double f = center + drift * out.size() / rate - bw / 2 + sym * spacing;
            out.push_back((float)(0.5 * std::cos(phase))); phase += 2 * M_PI * f / rate;
        }
    }
    void sendbit(int bit) {
        int data = enc.encode(bit);
        for (int i = 0; i < 2; ++i) {
            bitshreg = (bitshreg << 1) | ((data >> i) & 1);
            if (++bitstate == p.symbits) { inlv.bits(&bitshreg); sendsymbol(bitshreg); bitstate = 0; bitshreg = 0; }
        }
    }
    void sendchar(unsigned char c) { for (const char *s = kMfskVaricode[c]; *s; ++s) sendbit(*s - '0'); }
    // --- pictures, mirroring fldigi mfsk::flushtx / send_prologue / sendpic ---
    void flushtx() { sendbit(1); for (int i = 0; i < p.preamble; ++i) sendbit(0); bitstate = 0; }
    void tone(double f, int samples8k) {  // constant tone, phase continuous
        for (int i = 0; i < samples8k * (rate / 8000); ++i) { out.push_back((float)(0.5 * std::cos(phase))); phase += 2 * M_PI * f / rate; }
    }
    void prologue() {  // 352 samples at the low edge of the tone block (high edge when reversed)
        double bw = (p.numtones - 1) * 8000.0 / p.symlen;
        tone(reverse ? center + bw / 2 : center - bw / 2, 352);
    }
    void pixels(const std::vector<uint8_t> &data, int spp = 8) {
        double bw = (p.numtones - 1) * 8000.0 / p.symlen;
        for (uint8_t b : data) tone(center + (reverse ? -1 : 1) * bw * (b - 128) / 256.0, spp);
    }
    void startText() {
        for (int i = 0; i < p.preamble / 3; ++i) sendbit(0);
        sendchar('\r'); sendchar(2); sendchar('\r');
    }
    void endText() { sendchar('\r'); sendchar(4); sendchar('\r'); flushtx(); }
    void message(const std::string &text) {
        for (int i = 0; i < p.preamble / 3; ++i) sendbit(0);
        sendchar('\r'); sendchar(2); sendchar('\r');
        for (unsigned char c : text) sendchar(c);
        sendchar('\r'); sendchar(4); sendchar('\r');
        sendbit(1);
        for (int i = 0; i < p.preamble; ++i) sendbit(0);
    }
};

static bool run(const Params &p, double center, float noise, double offsetHz, const char *label,
                bool txRev = false, bool rxRev = false, bool expectOk = true, bool afc = false, double drift = 0, int repeat = 1) {
    const int rate = 48000;
    const std::string msg = "CQ CQ de W1AW 0123456789";
    Tx tx(p, center + offsetHz, rate);
    tx.reverse = txRev; tx.drift = drift;
    std::string payload; for (int i = 0; i < repeat; ++i) payload += msg + (i + 1 < repeat ? " " : "");
    tx.message(payload);
    std::mt19937 rng(5);
    std::normal_distribution<float> nd(0.f, noise);
    for (auto &s : tx.out) s += nd(rng);
    MfskDemod d(rate, p.mode, (float)center);
    d.setReverse(rxRev); d.setAfc(afc);
    std::string out;
    for (size_t i = 0; i < tx.out.size(); i += 480)
        d.process(&tx.out[i], (int)std::min<size_t>(480, tx.out.size() - i), out);
    const double finalHz = center + offsetHz + drift * (double)tx.out.size() / rate;
    bool ok = (out.find(msg) != std::string::npos) == expectOk;
    if (afc && expectOk) ok = ok && std::fabs(d.carrier() - finalHz) < 1.5;
    std::string shown;
    for (char c : out) shown += (c >= 32 && c < 127) ? c : '.';
    printf("%-8s %-18s %s  carrier %.1f (true %.1f) got: \"%s\"\n", p.name, label, ok ? "PASS" : "FAIL", d.carrier(), finalHz, shown.c_str());
    return ok;
}

// ---- picture reception -----------------------------------------------------------------------------
static uint8_t picPixel(int plane, int r, int c) {  // plane 0..2 = R,G,B (gray uses plane 0)
    if (plane == 0) return (uint8_t)(c * 255 / 63);
    if (plane == 1) return (uint8_t)(r * 255 / 47);
    return (((c / 8) + (r / 8)) % 2) ? 40 : 220;
}

static bool runPicture(const Params &p, bool color, int spp, bool reverse, const char *label, double maxErr = 14.0) {
    const int rate = 48000, W = 64, H = 48;
    Tx tx(p, 1500, rate);
    tx.reverse = reverse;
    tx.startText();
    for (unsigned char c : std::string("Hello \n")) tx.sendchar(c);
    char hdr[64];
    if (spp == 8) snprintf(hdr, sizeof hdr, "Sending Pic:%dx%d%s;", W, H, color ? "C" : "");
    else snprintf(hdr, sizeof hdr, "Sending Pic:%dx%d%sp%d;", W, H, color ? "C" : "", spp);
    for (unsigned char c : std::string("\n") + hdr) tx.sendchar(c);
    tx.flushtx();
    tx.prologue();
    std::vector<uint8_t> data;
    for (int r = 0; r < H; ++r)
        for (int plane = 0; plane < (color ? 3 : 1); ++plane)
            for (int c = 0; c < W; ++c) data.push_back(picPixel(plane, r, c));
    tx.pixels(data, spp);
    tx.flushtx();
    for (unsigned char c : std::string("73 de W1AW")) tx.sendchar(c);
    tx.endText();
    for (int i = 0; i < 48000; ++i) tx.out.push_back(0.f);

    std::mt19937 rng(21);
    std::normal_distribution<float> nd(0.f, 0.05f);
    for (auto &x : tx.out) x += nd(rng);
    MfskDemod d(rate, p.mode, 1500);
    d.setReverse(reverse);
    std::string text;
    std::vector<uint8_t> rgb(W * H * 3, 0), buf;
    int rowsGot = 0, state = 0, pw = 0, ph = 0, pc = 0, pid = 0;
    for (size_t i = 0; i < tx.out.size(); i += 480) {
        d.process(&tx.out[i], (int)std::min<size_t>(480, tx.out.size() - i), text);
        buf.clear();
        if (d.pollPicture(buf)) {
            int32_t h[7]; memcpy(h, buf.data(), sizeof h);
            pid = h[0]; pw = h[1]; ph = h[2]; pc = h[3]; state = h[6];
            memcpy(&rgb[(size_t)h[4] * W * 3], buf.data() + sizeof h, (size_t)h[5] * W * 3);
            rowsGot = h[4] + h[5];
        }
    }
    double err = 0; long n = 0;
    for (int r = 0; r < H; ++r) for (int c = 0; c < W; ++c) for (int ch = 0; ch < 3; ++ch) {
        int expect = picPixel(color ? ch : 0, r, c);
        err += std::abs((int)rgb[((size_t)r * W + c) * 3 + ch] - expect); ++n;
    }
    err /= n;
    std::string shown; for (char ch : text) shown += (ch >= 32 && ch < 127) ? ch : '.';
    bool ok = pid == 1 && pw == W && ph == H && pc == (color ? 1 : 0) && state == 2 && rowsGot == H && err < maxErr &&
              text.find("ello") != std::string::npos && text.find("73 de W1AW") != std::string::npos;
    printf("%-8s %-22s %s  %dx%d rows %d err %.1f/255  text: \"%s\"\n", p.name, label, ok ? "PASS" : "FAIL", pw, ph, rowsGot, err, shown.c_str());
    return ok;
}

int main(int argc, char **argv) {
    if (argc > 1 && std::string(argv[1]) == "--pictures") {
        // fldigi offers picture mode on MFSK16, 32, 64 and 128 only. The test pattern has hard edges, so the
        // error grows with pixel rate (X2 = 2000 px/s, X4 = 4000 px/s through a 15..1900 Hz wide tone block).
        bool ok = true;
        for (const Params &p : kModes) {
            if (p.mode == MfskDemod::MFSK4 || p.mode == MfskDemod::MFSK8 || p.mode == MfskDemod::MFSK31) continue;
            ok &= runPicture(p, true, 8, false, "colour 64x48 (X1)");
            ok &= runPicture(p, false, 8, false, "grey 64x48 (X1)");
        }
        ok &= runPicture(kModes[4], true, 4, false, "MFSK32 colour X2 (p4)", 20.0);
        ok &= runPicture(kModes[4], true, 8, true, "MFSK32 colour reverse");
        ok &= runPicture(kModes[5], true, 8, true, "MFSK64 colour reverse");
        return ok ? 0 : 1;
    }
    bool ok = true;
    for (const Params &p : kModes) {
        if (argc > 1 && std::string(argv[1]) != p.name) continue;
        ok &= run(p, 1500, 0.0f, 0, "clean");
        ok &= run(p, 1500, 0.15f, 0, "noise 0.15");
    }
    // REV: matched reverse decodes, mismatched does not.
    ok &= run(kModes[2], 1500, 0.1f, 0, "rev tx+rx", true, true, true);
    ok &= run(kModes[2], 1500, 0.1f, 0, "rev tx only", true, false, false);
    ok &= run(kModes[2], 1500, 0.1f, 0, "rev rx only", false, true, false);
    // AFC (fldigi's is slow by design: ~32-symbol averaging, capture range a quarter of the tone spacing).
    // A static 3.5 Hz error over a ~100 s transmission should be pulled to within 1.5 Hz.
    ok &= run(kModes[2], 1500, 0.1f, 3.5, "static +3.5Hz AFC off", false, false, true, false, 0, 8);
    ok &= run(kModes[2], 1500, 0.1f, 3.5, "static +3.5Hz AFC on", false, false, true, true, 0, 8);
    ok &= run(kModes[2], 1500, 0.1f, -3.5, "static -3.5Hz AFC on", false, false, true, true, 0, 8);
    // Noise-only must stay silent.
    {
        std::mt19937 rng(9); std::normal_distribution<float> nd(0.f, 0.2f);
        std::vector<float> n(48000 * 20); for (auto &x : n) x = nd(rng);
        MfskDemod d(48000, MfskDemod::MFSK16, 1500); std::string out;
        for (size_t i = 0; i < n.size(); i += 480) d.process(&n[i], 480, out);
        bool quiet = out.empty();
        printf("%-8s %-14s %s  (%zu chars)\n", "MFSK16", "noise only", quiet ? "PASS" : "FAIL", out.size());
        ok &= quiet;
    }
    return ok ? 0 : 1;
}
