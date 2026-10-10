// SSTV receiver tests with a transmit generator written straight from the mode specifications (N7CXI), independent of
// the decoder's tables: VIS header, line layouts, FM (1500 Hz black .. 2300 Hz white, 1200 Hz sync).
#include "sstv_demod.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <string>
#include <vector>

#include "sstv_tx.h"

struct Result { std::vector<std::vector<uint8_t>> pics; std::vector<int> ids, w, h, states; std::string text; };

static Result decode(const std::vector<float> &sig, int selection = -1) {
    SstvDemod d(kRate, selection);
    Result r; std::vector<uint8_t> buf;
    for (size_t i = 0; i < sig.size(); i += 480) {
        d.process(&sig[i], (int)std::min<size_t>(480, sig.size() - i), r.text);
        buf.clear();
        if (d.pollPicture(buf)) {
            int32_t h[7]; memcpy(h, buf.data(), sizeof h);
            if (r.ids.empty() || r.ids.back() != h[0]) { r.ids.push_back(h[0]); r.w.push_back(h[1]); r.h.push_back(h[2]); r.states.push_back(0); r.pics.emplace_back((size_t)h[1] * h[2] * 3, 0); }
            memcpy(&r.pics.back()[(size_t)h[4] * h[1] * 3], buf.data() + sizeof h, (size_t)h[5] * h[1] * 3);
            r.states.back() = h[6];
        }
    }
    return r;
}

static double meanError(Img &ref, const std::vector<uint8_t> &got, int fromRow = 0, int toRow = -1) {
    if (toRow < 0) toRow = ref.h;
    double e = 0; long n = 0;
    for (int y = fromRow; y < toRow; ++y) for (int x = 4; x < ref.w - 4; ++x) for (int c = 0; c < 3; ++c) { e += std::abs((int)got[((size_t)y * ref.w + x) * 3 + c] - (int)ref.at(x, y)[c]); ++n; }
    return e / n;
}

static bool check(bool c, const char *what) { printf("  %-64s %s\n", what, c ? "PASS" : "FAIL"); return c; }

int main(int argc, char **argv) {
    bool ok = true;
    const char *only = argc > 1 ? argv[1] : nullptr;   // run one mode's cases only (for investigating)
    for (int m = M1; m <= SDX; ++m) {
        if (only && std::string(only) != kNames[m]) continue;
        const int w = kWidth[m];
        Img im = makeImage(w, kLines[m]);
        const bool full = m <= R36 || m == PD90 || m == PD120 || m == R72;   // the long modes get the clean case only, to keep the run short
        {
            Tx t = transmit(m, im);
            Result r = decode(t.out);
            double e = r.pics.empty() ? 999 : meanError(im, r.pics[0]);
            printf("%-10s clean: %zu picture(s), mean error %.1f/255, text:%s", kNames[m], r.pics.size(), e, r.text.c_str());
            ok &= check(r.pics.size() == 1 && r.states[0] == 2 && r.w[0] == w && r.h[0] == kLines[m], "one finished picture of the right size");
            ok &= check(e < (m == R36 || m == R72 ? 14.0 : (m >= PD50 && m <= PD290) ? 9.0 : 6.0), "image matches (mean error)");
            ok &= check(r.text.find(kNames[m]) != std::string::npos, "mode named from the VIS header");
        }
        if (full) {
            Tx t = transmit(m, im, 70);   // receiver 70 Hz off tune
            for (auto &x : t.out) (void)x;
            Result r = decode(t.out);
            double e = r.pics.empty() ? 999 : meanError(im, r.pics[0]);
            printf("%-10s tuned +70 Hz: error %.1f\n", kNames[m], e);
            ok &= check(!r.pics.empty() && e < (m == R36 || m == R72 ? 16.0 : (m >= PD50 && m <= PD290) ? 12.0 : 8.0), "tuning error corrected from the header leader");
        }
        if (full) {
            Tx t = transmit(m, im, 0, 1.004);   // sender's clock 0.4% slow: lines are 0.4% longer than nominal
            Result r = decode(t.out);
            double e = r.pics.empty() ? 999 : meanError(im, r.pics[0]);
            printf("%-10s clock 0.4%% slow: error %.1f\n", kNames[m], e);
            ok &= check(!r.pics.empty() && e < (m == R36 || m == R72 ? 10.0 : 8.0), "line timing follows each sync pulse");
        }
        if (full) {
            Tx t = transmit(m, im);
            std::mt19937 rng(7); std::normal_distribution<float> nd(0.f, 0.15f);
            for (auto &x : t.out) x += nd(rng);
            Result r = decode(t.out);
            double e = r.pics.empty() ? 999 : meanError(im, r.pics[0]);
            printf("%-10s noise 0.15: error %.1f\n", kNames[m], e);
            if (getenv("SSTV_ROWS") && !r.pics.empty()) { printf("  rows with error > 45:"); for (int y = 0; y < im.h; ++y) { const double ry = meanError(im, r.pics[0], y, y + 1); if (ry > 45) printf(" %d(%.0f)", y, ry); } printf("\n"); }
            ok &= check(!r.pics.empty() && e < (m == R36 || m == R72 ? 30.0 : (m >= PD50 && m <= PD290) ? 34.0 : 20.0), "decodes through noise");
        }
    }

    printf("Header-less starts (the header and the first lines are cut off):\n");
    {
        static const double kLine[] = {446.446, 226.798, 428.22, 277.69, 150, 388.16, 703.04, 508.48, 804.416, 754.24, 1000, 937.28, 300, 1050.3};
        for (int m = M1; m <= SDX; ++m) {
            if (only && std::string(only) != kNames[m]) continue;
            Img im = makeImage(kWidth[m], kLines[m]);
            Tx t = transmit(m, im);
            // The header ends 1.11 s in; start 5.3 lines later, in the middle of a line.
            const size_t cut = (size_t)((1.11 + 5.3 * kLine[m] * 1e-3) * kRate);
            std::vector<float> sig(t.out.begin() + cut, t.out.end());
            Result r = decode(sig);
            bool named = r.text.find(std::string("SSTV ") + kNames[m] + " (no header") != std::string::npos;
            double best = 999; int bestOff = -1;
            if (!r.pics.empty() && r.w[0] == kWidth[m])
                for (int off = 0; off <= 20; ++off) {
                    // decoded row k corresponds to source row off + k; compare the first 40 decoded rows
                    double e = 0; long n = 0;
                    for (int y = 2; y < 40 && off + y < kLines[m]; ++y) for (int x = 4; x < kWidth[m] - 4; ++x) for (int c = 0; c < 3; ++c) {
                        e += std::abs((int)r.pics[0][((size_t)y * kWidth[m] + x) * 3 + c] - (int)im.at(x, off + y)[c]); ++n; }
                    if (n && e / n < best) { best = e / n; bestOff = off; }
                }
            printf("%-10s joined=%d, top %d rows missing, mean error %.1f\n", kNames[m], (int)named, bestOff, best);
            ok &= check(named && r.pics.size() == 1, "mode recognised from the sync pulses alone");
            ok &= check(best < (m == R36 || m == R72 ? 16.0 : (m >= PD50 && m <= PD290) ? 11.0 : 9.0), "the picture that follows is correct");
        }
        // A selected mode joins only its own pulses.
        Img im = makeImage(320, 256);
        Tx t = transmit(M1, im);
        std::vector<float> sig(t.out.begin() + (size_t)(3.0 * kRate), t.out.end());
        Result r = decode(sig, SstvDemod::M1);
        ok &= check(r.pics.size() == 1, "a selected mode starts without a header");
        r = decode(sig, SstvDemod::S1);
        ok &= check(r.pics.empty(), "a different selected mode does not");
        // Three sync pulses are not enough; neither are pulses at the wrong interval.
        Tx p3; for (int i = 0; i < 3; ++i) { p3.tone(1200, 4.862); p3.tone(1900, 446.446 - 4.862); }
        ok &= check(decode(p3.out).pics.empty(), "three pulses do not start a picture");
        Tx odd; for (int i = 0; i < 8; ++i) { odd.tone(1200, 9); odd.tone(1900, 333 - 9); }
        ok &= check(decode(odd.out).pics.empty(), "pulses at no mode's line interval do not start one");
    }
    printf("Selection and rejection:\n");
    {
        Img im = makeImage(320, 256);
        Tx t = transmit(M1, im);
        Result r = decode(t.out, SstvDemod::S1);
        ok &= check(r.pics.empty() && r.text.find("Martin M1 detected, but Scottie S1 is selected") != std::string::npos, "a different mode than the selected one is ignored and reported");
        r = decode(t.out, SstvDemod::M1);
        ok &= check(r.pics.size() == 1, "the selected mode decodes");
        // Robot 24 (VIS 4) is recognised but not supported.
        Tx p; p.tone(1900, 200); p.vis(4); p.tone(1500, 2000);
        r = decode(p.out);
        ok &= check(r.pics.empty() && r.text.find("Robot 24: not supported") != std::string::npos, "an unsupported mode (Robot 24) is named, not decoded");
        std::mt19937 rng(3); std::normal_distribution<float> nd(0.f, 0.2f);
        std::vector<float> n(kRate * 20); for (auto &x : n) x = nd(rng);
        r = decode(n);
        ok &= check(r.pics.empty() && r.text.empty(), "noise alone produces nothing");
        // Header with a wrong parity bit must not start a picture.
        Tx b; b.tone(1900, 200); b.tone(1900, 300); b.tone(1200, 10); b.tone(1900, 300); b.tone(1200, 30);
        for (int bit : {0, 0, 1, 1, 0, 1, 0}) b.tone(bit ? 1100 : 1300, 30);
        b.tone(1300, 30); /* parity should be 1100 */ b.tone(1200, 30); b.tone(1500, 3000);
        r = decode(b.out);
        ok &= check(r.pics.empty(), "a header with bad parity is rejected");
    }
    printf("Sequences and dropouts:\n");
    {
        Img a = makeImage(320, 256), c = makeImage(320, 240);
        Tx t1 = transmit(M2, a), t2 = transmit(R36, c);
        std::vector<float> both = t1.out; both.insert(both.end(), t2.out.begin(), t2.out.end());
        Result r = decode(both);
        ok &= check(r.pics.size() == 2 && r.ids[0] != r.ids[1], "two pictures in a row are separate");
        ok &= check(r.pics.size() == 2 && meanError(a, r.pics[0]) < 6.0 && meanError(c, r.pics[1]) < 14.0, "both decode correctly");

        Img im = makeImage(320, 256);
        Tx t = transmit(M1, im);
        const size_t from = (size_t)(30.0 * kRate), to = from + (size_t)(1.5 * kRate);
        for (size_t i = from; i < to && i < t.out.size(); ++i) t.out[i] = 0.f;   // a 1.5 s fade, about 3 lines
        r = decode(t.out);
        ok &= check(r.pics.size() == 1 && r.states[0] == 2, "a short fade does not end the picture");
        const int lostFrom = 30 * 1000 / 446, lostTo = lostFrom + 5;
        ok &= check(!r.pics.empty() && meanError(im, r.pics[0], lostTo + 2, 256) < 6.0, "lines after the fade are aligned again");
        ok &= check(!r.pics.empty() && meanError(im, r.pics[0], 0, lostFrom - 2) < 6.0, "lines before the fade are fine");
    }
    printf(ok ? "SSTV: all passed\n" : "SSTV: FAILURES\n");
    return ok ? 0 : 1;
}
