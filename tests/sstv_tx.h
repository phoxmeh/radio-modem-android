// Transmit generator for the SSTV tests, written straight from the mode specifications (N7CXI), independent of the
// decoder's tables: VIS header, line layouts, FM (1500 Hz black .. 2300 Hz white, 1200 Hz sync).
#pragma once
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <string>
#include <vector>

static const int kRate = 48000;

struct Tx {
    std::vector<float> out;
    double phase = 0, tAccum = 0, offset = 0, scale = 1.0;  // offset: receiver tuning error in Hz, scale: clock error
    void tone(double hz, double ms) {
        tAccum += ms * 1e-3 * scale * kRate;
        const long target = (long)std::llround(tAccum), n = target - (long)out.size();
        for (long i = 0; i < n; ++i) { phase += 2 * M_PI * (hz + offset) / kRate; out.push_back((float)(0.5 * std::cos(phase))); }
    }
    static double px(int v) { return 1500.0 + 800.0 * v / 255.0; }
    void vis(int code) {
        tone(1900, 300); tone(1200, 10); tone(1900, 300); tone(1200, 30);
        int ones = 0;
        for (int b = 0; b < 7; ++b) { const int bit = (code >> b) & 1; ones += bit; tone(bit ? 1100 : 1300, 30); }
        tone(ones & 1 ? 1100 : 1300, 30);   // even parity
        tone(1200, 30);
    }
};

struct Img { int w, h; std::vector<uint8_t> rgb; uint8_t *at(int x, int y) { return &rgb[((size_t)y * w + x) * 3]; } };

// Blocks of 8x8 so Robot's shared chroma does not matter; a gradient bar gives smooth values.
static Img makeImage(int w, int h) {
    Img im{w, h, std::vector<uint8_t>((size_t)w * h * 3)};
    for (int y = 0; y < h; ++y) for (int x = 0; x < w; ++x) {
        uint8_t *p = im.at(x, y);
        if (y < h / 4) { p[0] = (uint8_t)(x * 255 / (w - 1)); p[1] = (uint8_t)(255 - x * 255 / (w - 1)); p[2] = (uint8_t)(y * 4 * 200 / h + x * 55 / (w - 1)); }   // varies with y too, so a vertical offset can be found
        else { const int bx = x / 16, by = y / 16; p[0] = (uint8_t)(40 + 170 * ((bx + by) % 2)); p[1] = (uint8_t)(30 + 40 * (bx % 5)); p[2] = (uint8_t)(220 - 35 * (by % 6)); }
    }
    return im;
}

static void martin(Tx &t, Img &im, double pixelMs) {
    for (int y = 0; y < im.h; ++y) {
        t.tone(1200, 4.862); t.tone(1500, 0.572);
        for (int c : {1, 2, 0}) {   // green, blue, red
            for (int x = 0; x < im.w; ++x) t.tone(Tx::px(im.at(x, y)[c]), pixelMs);
            t.tone(1500, 0.572);
        }
    }
}

static void scottie(Tx &t, Img &im, double pixelMs) {
    for (int y = 0; y < im.h; ++y) {
        t.tone(1500, 1.5); for (int x = 0; x < im.w; ++x) t.tone(Tx::px(im.at(x, y)[1]), pixelMs);   // separator, green
        t.tone(1500, 1.5); for (int x = 0; x < im.w; ++x) t.tone(Tx::px(im.at(x, y)[2]), pixelMs);   // separator, blue
        t.tone(1200, 9); t.tone(1500, 1.5);                                                          // sync, porch
        for (int x = 0; x < im.w; ++x) t.tone(Tx::px(im.at(x, y)[0]), pixelMs);                      // red
    }
}

static void robot36(Tx &t, Img &im) {
    auto clip = [](double v) { return (int)std::min(255.0, std::max(0.0, v)); };
    for (int y = 0; y < im.h; ++y) {
        t.tone(1200, 9); t.tone(1500, 3);
        auto Y = [&](int x) { const uint8_t *p = im.at(x, y); return 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]; };
        for (int x = 0; x < im.w; ++x) t.tone(Tx::px(clip(Y(x))), 88.0 / im.w);
        const bool ry = y % 2 == 0;
        t.tone(ry ? 1500 : 2300, 4.5); t.tone(1900, 1.5);
        for (int x = 0; x < im.w; x += 2) {   // 160 chroma samples
            const uint8_t *p = im.at(x, y); const double yy = Y(x);
            const double v = ry ? (p[0] - yy) / 1.4 + 127.5 : (p[2] - yy) / 1.78 + 127.5;
            t.tone(Tx::px(clip(v)), 44.0 / 160);
        }
    }
}

// PD: sync 20 ms, porch 2.08 ms, then the even row's luminance, R-Y and B-Y (both averaged over the two rows), the odd row's luminance.
static void pd(Tx &t, Img &im, double pixelMs) {
    auto clip = [](double v) { return (int)std::min(255.0, std::max(0.0, v)); };
    auto Y = [&](int x, int y) { const uint8_t *p = im.at(x, y); return 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]; };
    for (int y = 0; y < im.h; y += 2) {
        t.tone(1200, 20); t.tone(1500, 2.08);
        for (int x = 0; x < im.w; ++x) t.tone(Tx::px(clip(Y(x, y))), pixelMs);
        for (int x = 0; x < im.w; ++x) { double v = 0; for (int r : {y, y + 1}) v += (im.at(x, r)[0] - Y(x, r)) / 1.4 + 127.5; t.tone(Tx::px(clip(v / 2)), pixelMs); }
        for (int x = 0; x < im.w; ++x) { double v = 0; for (int r : {y, y + 1}) v += (im.at(x, r)[2] - Y(x, r)) / 1.78 + 127.5; t.tone(Tx::px(clip(v / 2)), pixelMs); }
        for (int x = 0; x < im.w; ++x) t.tone(Tx::px(clip(Y(x, y + 1))), pixelMs);
    }
}

// Robot 72: sync 9, porch 3 (1500), Y 138 ms, separator 4.5 (1500) + porch 1.5 (1900), R-Y 69 ms, separator 4.5 (2300) + porch 1.5, B-Y 69 ms.
static void robot72(Tx &t, Img &im) {
    auto clip = [](double v) { return (int)std::min(255.0, std::max(0.0, v)); };
    for (int y = 0; y < im.h; ++y) {
        auto Y = [&](int x) { const uint8_t *p = im.at(x, y); return 0.299 * p[0] + 0.587 * p[1] + 0.114 * p[2]; };
        t.tone(1200, 9); t.tone(1500, 3);
        for (int x = 0; x < im.w; ++x) t.tone(Tx::px(clip(Y(x))), 138.0 / im.w);
        t.tone(1500, 4.5); t.tone(1900, 1.5);
        for (int x = 0; x < im.w; x += 2) t.tone(Tx::px(clip((im.at(x, y)[0] - Y(x)) / 1.4 + 127.5)), 69.0 / 160);
        t.tone(2300, 4.5); t.tone(1900, 1.5);
        for (int x = 0; x < im.w; x += 2) t.tone(Tx::px(clip((im.at(x, y)[2] - Y(x)) / 1.78 + 127.5)), 69.0 / 160);
    }
}

enum { M1, M2, S1, S2, R36, PD50, PD90, PD120, PD160, PD180, PD240, PD290, R72, SDX };
static const char *kNames[] = {"Martin M1", "Martin M2", "Scottie S1", "Scottie S2", "Robot 36", "PD50", "PD90", "PD120", "PD160", "PD180", "PD240", "PD290", "Robot 72", "Scottie DX"};
static const int kVis[] = {44, 40, 60, 56, 8, 93, 99, 95, 98, 96, 97, 94, 12, 76};
static const int kLines[] = {256, 256, 256, 256, 240, 256, 256, 496, 400, 496, 496, 616, 240, 256};
static const int kWidth[] = {320, 320, 320, 320, 320, 320, 320, 640, 512, 640, 640, 800, 320, 320};
static const double kPd[] = {0.286, 0.532, 0.19, 0.382, 0.286, 0.382, 0.286};

// maxLines: send only the first rows of the picture (0 = all), for quick runs.
static Tx transmit(int mode, Img &im, double offset = 0, double scale = 1.0, int maxLines = 0) {
    Tx t; t.offset = offset; t.scale = scale;
    Img part{im.w, maxLines > 0 ? std::min(maxLines, im.h) : im.h, {}};
    part.rgb.assign(im.rgb.begin(), im.rgb.begin() + (size_t)part.w * part.h * 3);
    Img &im_ = part;
    t.tone(1900, 200);
    t.vis(kVis[mode]);
    switch (mode) {
        case M1: martin(t, im_, 0.4576); break;
        case M2: martin(t, im_, 0.2288); break;
        case S1: scottie(t, im_, 0.4320); break;
        case S2: scottie(t, im_, 0.2752); break;
        case R36: robot36(t, im_); break;
        case R72: robot72(t, im_); break;
        case SDX: scottie(t, im_, 1.08); break;
        default: pd(t, im_, kPd[mode - PD50]);
    }
    t.tone(1900, 300);
    for (int i = 0; i < kRate / 2; ++i) t.out.push_back(0.f);
    return t;
}

