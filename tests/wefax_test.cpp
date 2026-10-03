// Host-side WEFAX test: an FM fax transmitter (APT start, phasing, image, APT stop) feeds WefaxDemod.
// The APT tones are black/white square waves, as on real broadcasts. Run via tests/run_host_tests.sh.
#include "wefax_demod.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <cstdio>
#include <random>
#include <string>

static const int kRate = 48000;

static uint8_t testPixel(int r, int c) {
    if (c < 200) return (uint8_t)(c * 255 / 200);                 // gradient bar
    if (c >= 900 && c < 906) return 255;                          // vertical white line
    return (((c / 90) + (r / 5)) % 2) ? 30 : 230;                 // checker blocks
}

struct FaxTx {
    int ioc; double lpm, fc = 1900, dev = 400;
    double phase = 0;
    std::vector<float> out;
    void emit(double pix01, int n) {  // n samples at constant pixel value (0 black .. 1 white)
        for (int i = 0; i < n; ++i) {
            phase += 2 * M_PI * (fc + (pix01 - 0.5) * 2 * dev) / kRate;
            out.push_back((float)(0.5 * std::cos(phase)));
        }
    }
    void aptTone(double hz, double seconds) {  // square wave modulation of the pixel value
        const int total = (int)(seconds * kRate);
        for (int i = 0; i < total; ++i) emit(std::fmod(i * hz * 2.0 / kRate, 2.0) < 1.0 ? 1.0 : 0.0, 1);
    }
    void run(int rows, int width) {
        const int spl = (int)std::lround(60.0 * kRate / lpm);
        emit(0.5, kRate / 2);                                       // brief unmodulated carrier
        aptTone(ioc == 288 ? 675 : 300, 5);                         // APT start
        for (int s = 0; s < 20 * spl; ++s) {                        // 20 phasing lines
            double ph = (double)(s % spl) / spl;
            emit((ph < 0.025 || ph >= 0.975) ? 1.0 : 0.0, 1);
        }
        emit(1.0, spl);                                             // end-of-phasing white line
        for (int s = 0; s < rows * spl; ++s) {                      // image
            int r = s / spl, c = (int)((double)(s % spl) * width / spl);
            emit(testPixel(r, c) / 255.0, 1);
        }
        aptTone(450, 5);                                            // APT stop
        emit(0.0, kRate);
    }
};

// Decodes `sig` and returns the image rows (each width bytes) plus the final state.
struct Result { std::vector<std::vector<uint8_t>> rows; int width = 0, id = 0, state = -1, generation = 0; };

static float gLastCenter = 0;
static Result decode(const std::vector<float> &sig, int ioc, double slant = 0, bool afc = false) {
    WefaxDemod d(kRate, ioc, 1900);
    d.setAfc(afc);
    if (slant != 0) d.setSlantPercent(slant);
    Result res;
    std::vector<uint8_t> buf;
    auto drain = [&] {
        buf.clear();
        d.poll(buf);
        int32_t h[6]; memcpy(h, buf.data(), sizeof h);
        if (h[0] != res.generation) { res.rows.clear(); res.generation = h[0]; }
        res.width = h[1]; res.id = h[4]; res.state = h[5];
        for (int i = 0; i < h[3]; ++i)
            res.rows.emplace_back(buf.begin() + sizeof h + (size_t)i * h[1], buf.begin() + sizeof h + (size_t)(i + 1) * h[1]);
    };
    for (size_t i = 0; i < sig.size(); i += 480) { d.process(&sig[i], (int)std::min<size_t>(480, sig.size() - i)); drain(); }
    gLastCenter = d.center();
    return res;
}

// Mean absolute error between received rows (starting at `offset`) and the test pattern, over columns
// away from the left edge where the phasing/alignment may differ by a few pixels.
static double compare(const Result &r, int offset, int rows, int colShift) {
    double err = 0; long n = 0;
    for (int k = 2; k < rows - 2; ++k) {
        if (offset + k >= (int)r.rows.size()) return 1e9;
        for (int c = 10; c < r.width - 10; ++c) {
            int exp = testPixel(k, c);
            err += std::abs((int)r.rows[offset + k][std::clamp(c + colShift, 0, r.width - 1)] - exp);
            ++n;
        }
    }
    return err / n;
}

static bool check(bool cond, const char *what) { printf("  %-62s %s\n", what, cond ? "PASS" : "FAIL"); return cond; }

int main() {
    bool ok = true;
    const int rows = 40;
    printf("WEFAX IOC576 120 LPM, clean:\n");
    FaxTx tx{576, 120};
    tx.run(rows, 1809);
    Result r = decode(tx.out, 576);
    ok &= check(r.width == 1809, "width = 576 * pi = 1809");
    ok &= check(r.state == WefaxDemod::AptStart, "back to waiting after APT stop");
    // Phasing lines start the picture, so find where the test pattern begins.
    double best = 1e9; int bestOff = -1, bestShift = 0;
    for (int off = 0; off < 40; ++off)
        for (int sh = -30; sh <= 30; sh += 1) {
            double e = compare(r, off, rows, sh);
            if (e < best) { best = e; bestOff = off; bestShift = sh; }
        }
    printf("  rows=%zu, pattern starts at row %d, column shift %d, mean abs error %.1f/255\n", r.rows.size(), bestOff, bestShift, best);
    ok &= check(r.rows.size() >= (size_t)(rows + 15), "phasing + image rows were produced");
    ok &= check(best < 12.0, "image matches the transmitted pattern (error < 12/255)");
    ok &= check(std::abs(bestShift) <= 6, "phasing alignment puts columns within 6 px");

    printf("WEFAX noise (sigma 0.15):\n");
    {
        std::mt19937 rng(4); std::normal_distribution<float> nd(0.f, 0.15f);
        auto noisy = tx.out; for (auto &s : noisy) s += nd(rng);
        Result rn = decode(noisy, 576);
        double b = 1e9;
        for (int off = 0; off < 40; ++off) for (int sh = -30; sh <= 30; ++sh) b = std::min(b, compare(rn, off, rows, sh));
        printf("  mean abs error %.1f/255\n", b);
        ok &= check(b < 30.0, "noisy image still recognisable (error < 30/255)");
    }

    printf("WEFAX slant: transmitter 0.5%% slow vs receiver nominal:\n");
    {
        FaxTx slow{576, 120.0 / 1.005};  // line time 0.5% longer than nominal
        slow.run(rows, 1809);
        Result a = decode(slow.out, 576), c = decode(slow.out, 576, 0.5);
        double ea = 1e9, ec = 1e9;
        for (int off = 0; off < 40; ++off) for (int sh = -40; sh <= 40; ++sh) {
            ea = std::min(ea, compare(a, off, rows, sh)); ec = std::min(ec, compare(c, off, rows, sh));
        }
        printf("  error uncorrected %.1f, with +0.5%% slant correction %.1f\n", ea, ec);
        ok &= check(ec < ea, "slant correction improves the image");
        ok &= check(ec < 15.0, "corrected image matches (error < 15/255)");
    }

    printf("WEFAX AFC: transmitter subcarrier 45 Hz above the receiver's 1900 Hz:\n");
    {
        FaxTx off{576, 120}; off.fc = 1945;
        off.run(rows, 1809);
        Result a = decode(off.out, 576, 0, false);
        Result c = decode(off.out, 576, 0, true);
        float centerOn = gLastCenter;
        double ea = 1e9, ec = 1e9;
        for (int o = 0; o < 40; ++o) for (int sh = -30; sh <= 30; ++sh) {
            ea = std::min(ea, compare(a, o, rows, sh)); ec = std::min(ec, compare(c, o, rows, sh));
        }
        printf("  error AFC off %.1f, AFC on %.1f, receiver centre ended at %.1f Hz (true 1945)\n", ea, ec, centerOn);
        ok &= check(ec < ea, "AFC improves the picture");
        ok &= check(std::fabs(centerOn - 1945.f) < 8.f, "AFC centre within 8 Hz of the true subcarrier");
    }

    printf("WEFAX IOC288 60 LPM:\n");
    {
        FaxTx t2{288, 60};
        t2.run(20, 904);
        Result r2 = decode(t2.out, 288);
        ok &= check(r2.width == 904, "width = 288 * pi = 904");
        ok &= check(r2.rows.size() >= 30, "rows produced at 60 LPM");
    }

    printf("WEFAX noise only produces no image:\n");
    {
        std::mt19937 rng(8); std::normal_distribution<float> nd(0.f, 0.3f);
        std::vector<float> n(kRate * 30); for (auto &x : n) x = nd(rng);
        Result rn = decode(n, 576);
        ok &= check(rn.rows.empty(), "no rows from noise");
    }
    return ok ? 0 : 1;
}
