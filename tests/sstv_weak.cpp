// Weak-signal benchmark for the SSTV decoder: short transmissions through white noise, fading and impulse noise.
// Prints, per mode and condition, how many of the rows sent were delivered and their mean error. Not part of the
// pass/fail suite; it is for comparing decoder changes.   sstv_weak [mode]
#include "sstv_demod.h"
#include "sstv_tx.h"
#include <algorithm>

struct Out { int rows = 0; double err = 0; bool header = false; };

static Out run(int mode, Img &im, int sent, std::vector<float> sig) {
    SstvDemod d(kRate, -1);
    std::string text; std::vector<uint8_t> buf; std::vector<uint8_t> pic((size_t)im.w * im.h * 3, 0);
    int rows = 0;
    for (size_t i = 0; i < sig.size(); i += 480) {
        d.process(&sig[i], (int)std::min<size_t>(480, sig.size() - i), text);
        buf.clear();
        if (d.pollPicture(buf)) { int32_t h[7]; memcpy(h, buf.data(), sizeof h); memcpy(&pic[(size_t)h[4] * im.w * 3], buf.data() + sizeof h, (size_t)h[5] * im.w * 3); rows = h[4] + h[5]; }
    }
    Out o; o.rows = std::min(rows, sent); o.header = text.find("VIS") != std::string::npos;
    double e = 0; long n = 0;
    for (int y = 0; y < o.rows; ++y) for (int x = 4; x < im.w - 4; ++x) for (int c = 0; c < 3; ++c) { e += std::abs((int)pic[((size_t)y * im.w + x) * 3 + c] - (int)im.at(x, y)[c]); ++n; }
    o.err = n ? e / n : 999;
    (void)mode;
    return o;
}

int main(int argc, char **argv) {
    struct M { int mode; int lines; };
    const M modes[] = {{M2, 100}, {S2, 80}, {R36, 160}, {PD50, 100}, {PD120, 80}};
    const double sigmas[] = {0.15, 0.3, 0.5, 0.8, 1.2};
    if (!getenv("SSTV_COMPACT")) printf("signal amplitude 0.5; white noise sigma over 24 kHz (in-band SNR about 16 / 10 / 6 / 2 / -1.5 dB)\n");
    printf("%-9s %-14s", "mode", "");
    for (double s : sigmas) printf("  sigma %.2f  ", s);
    printf("   fading     impulses\n");
    double total = 0; int count = 0;
    for (const M &m : modes) {
        if (argc > 1 && std::string(argv[1]) != kNames[m.mode]) continue;
        Img im = makeImage(kWidth[m.mode], kLines[m.mode]);
        Tx t = transmit(m.mode, im, 0, 1.0, m.lines);
        if (getenv("SSTV_COMPACT")) printf("%-11s", kNames[m.mode]); else printf("%-9s %-14s", kNames[m.mode], "rows/err");
        auto report = [&](std::vector<float> sig) {
            Out o = run(m.mode, im, m.lines, sig);
            if (getenv("SSTV_COMPACT")) printf(" %6.1f", (o.rows * o.err + (m.lines - o.rows) * 100.0) / m.lines);
            else printf("  %c%3d/%-3d %5.1f", o.header ? (char)118 : (char)45, o.rows, m.lines, o.err);
            // an undelivered row counts as fully wrong (mean error 100) so one number tracks both loss and noise
            total += (o.rows * o.err + (m.lines - o.rows) * 100.0) / m.lines; ++count;
        };
        for (double sg : sigmas) {
            std::mt19937 rng(11); std::normal_distribution<float> nd(0.f, (float)sg);
            std::vector<float> sig = t.out; for (auto &x : sig) x += nd(rng);
            report(sig);
        }
        {   // slow deep fading plus moderate noise
            std::mt19937 rng(12); std::normal_distribution<float> nd(0.f, 0.3f);
            std::vector<float> sig = t.out;
            for (size_t i = 0; i < sig.size(); ++i) sig[i] = sig[i] * (float)(0.55 + 0.45 * std::sin(2 * M_PI * 0.25 * i / kRate)) + nd(rng);
            report(sig);
        }
        {   // impulse noise: 3 ms bursts, about four a second, plus light noise
            std::mt19937 rng(13); std::normal_distribution<float> nd(0.f, 0.15f); std::uniform_real_distribution<float> u(0.f, 1.f);
            std::vector<float> sig = t.out; for (auto &x : sig) x += nd(rng);
            for (size_t i = 0; i < sig.size(); i += kRate / 4) { const size_t at = i + (size_t)(u(rng) * kRate / 4); for (size_t k = 0; k < (size_t)(0.003 * kRate) && at + k < sig.size(); ++k) sig[at + k] += (u(rng) - 0.5f) * 6.f; }
            report(sig);
        }
        printf("\n");
    }
    if (!getenv("SSTV_COMPACT")) printf("overall score (mean of per-run error with lost rows counted as 100): %.1f   (lower is better)\n", total / count);
    return 0;
}
