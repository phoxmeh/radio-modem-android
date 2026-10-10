#include "sstv_demod.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

namespace {
constexpr double kCenter = 1900.0, kBlack = 1500.0, kWhite = 2300.0, kSyncHz = 1200.0;
constexpr double kTwoPi = 6.283185307179586;

std::vector<float> lowpass(int len, double fc, double fs) {
    std::vector<float> h(len);
    double sum = 0;
    for (int i = 0; i < len; ++i) {
        double m = i - (len - 1) / 2.0;
        double s = (m == 0) ? 2 * fc / fs : std::sin(2 * M_PI * fc * m / fs) / (M_PI * m);
        h[i] = (float)(s * (0.5 - 0.5 * std::cos(2 * M_PI * i / (len - 1))));
        sum += h[i];
    }
    for (auto &v : h) v = (float)(v / sum);
    return h;
}

uint8_t clip8(double v) { return (uint8_t)std::min(255.0, std::max(0.0, v)); }
}  // namespace

// ---- mode tables ----------------------------------------------------------------------------------------------
// Channel starts are seconds relative to the END of the line's sync pulse (negative = before it, as in Scottie).
static SstvDemod::Spec makeMartin(const char *name, int vis, double pixel) {
    const double scan = 320 * pixel, porch = 0.572e-3, sep = 0.572e-3, sync = 4.862e-3;
    SstvDemod::Spec s{};
    s.name = name; s.vis = vis; s.width = 320; s.lines = 256; s.syncLen = sync; s.robot = false; s.yuv = false; s.rowsPerLine = 1;
    // Channel order on air: green, blue, red.
    s.ch[0] = {1, porch, scan, 320};
    s.ch[1] = {2, porch + scan + sep, scan, 320};
    s.ch[2] = {0, porch + 2 * (scan + sep), scan, 320};
    s.nch = 3;
    s.rowEnd = s.ch[2].start + scan;
    s.lineTime = sync + porch + 3 * scan + 3 * sep;
    s.firstSyncEndDelay = 0;  // the first sync pulse follows the stop bit directly
    return s;
}

static SstvDemod::Spec makeScottie(const char *name, int vis, double pixel) {
    const double scan = 320 * pixel, porch = 1.5e-3, sep = 1.5e-3, sync = 9e-3;
    SstvDemod::Spec s{};
    s.name = name; s.vis = vis; s.width = 320; s.lines = 256; s.syncLen = sync; s.robot = false; s.yuv = false; s.rowsPerLine = 1;
    // On air: separator, green, separator, blue, SYNC, porch, red. So green and blue come before their line's sync.
    s.ch[0] = {1, -sync - 2 * scan - sep, scan, 320};
    s.ch[1] = {2, -sync - scan, scan, 320};
    s.ch[2] = {0, porch, scan, 320};
    s.nch = 3;
    s.rowEnd = porch + scan;
    s.lineTime = 3 * scan + sync + porch + 2 * sep;
    s.firstSyncEndDelay = sep + scan + sep + scan + sync;  // after the VIS stop bit: sep, G, sep, B, then the first sync
    return s;
}

static SstvDemod::Spec makeRobot36() {
    SstvDemod::Spec s{};
    s.name = "Robot 36"; s.vis = 8; s.width = 320; s.lines = 240; s.syncLen = 9e-3; s.robot = true; s.yuv = false; s.rowsPerLine = 1;
    s.ch[0] = {0, 3e-3, 88e-3, 320};                  // Y
    s.sepStart = 3e-3 + 88e-3; s.sepLen = 4.5e-3;     // 1500 Hz: next is R-Y (even lines); 2300 Hz: B-Y (odd)
    s.ch[1] = {1, 3e-3 + 88e-3 + 4.5e-3 + 1.5e-3, 44e-3, 160};  // chroma, half horizontal resolution
    s.nch = 2;
    s.rowEnd = s.ch[1].start + 44e-3;
    s.lineTime = 150e-3;
    s.firstSyncEndDelay = 0;
    return s;
}

// Robot 72: every line has luminance (138 ms) and both colour differences (69 ms each, half horizontal resolution),
// each preceded by a 4.5 ms separator (1500 Hz before R-Y, 2300 Hz before B-Y) and a 1.5 ms porch.
static SstvDemod::Spec makeRobot72() {
    SstvDemod::Spec s{};
    s.name = "Robot 72"; s.vis = 12; s.width = 320; s.lines = 240; s.syncLen = 9e-3; s.robot = false; s.yuv = true; s.rowsPerLine = 1;
    s.ch[0] = {0, 3e-3, 138e-3, 320};
    s.ch[1] = {1, 3e-3 + 138e-3 + 4.5e-3 + 1.5e-3, 69e-3, 160};
    s.ch[2] = {2, 3e-3 + 138e-3 + 4.5e-3 + 1.5e-3 + 69e-3 + 4.5e-3 + 1.5e-3, 69e-3, 160};
    s.nch = 3;
    s.rowEnd = s.ch[2].start + 69e-3;
    s.lineTime = 300e-3;
    s.firstSyncEndDelay = 0;
    return s;
}

// PD modes (Paul Dunphy, PD-xxx): one sync pulse per two image rows. The frame is sync (20 ms), porch (2.08 ms), then four
// scans back to back: luminance of the even row, R-Y, B-Y (both shared by the two rows), luminance of the odd row.
static SstvDemod::Spec makePD(const char *name, int vis, int width, int lines, double pixel) {
    const double scan = width * pixel, porch = 2.08e-3, sync = 20e-3;
    SstvDemod::Spec s{};
    s.name = name; s.vis = vis; s.width = width; s.lines = lines; s.syncLen = sync; s.robot = false; s.yuv = true; s.rowsPerLine = 2;
    s.ch[0] = {0, porch, scan, width};
    s.ch[1] = {1, porch + scan, scan, width};
    s.ch[2] = {2, porch + 2 * scan, scan, width};
    s.ch[3] = {3, porch + 3 * scan, scan, width};
    s.nch = 4;
    s.rowEnd = porch + 4 * scan;
    s.lineTime = sync + porch + 4 * scan;
    s.firstSyncEndDelay = 0;
    return s;
}

const SstvDemod::Spec &SstvDemod::spec(int mode) {
    static const Spec specs[kModeCount] = {
        makeMartin("Martin M1", 44, 0.4576e-3), makeMartin("Martin M2", 40, 0.2288e-3),
        makeScottie("Scottie S1", 60, 0.4320e-3), makeScottie("Scottie S2", 56, 0.2752e-3), makeRobot36(),
        makePD("PD50", 93, 320, 256, 0.286e-3), makePD("PD90", 99, 320, 256, 0.532e-3), makePD("PD120", 95, 640, 496, 0.19e-3),
        makePD("PD160", 98, 512, 400, 0.382e-3), makePD("PD180", 96, 640, 496, 0.286e-3), makePD("PD240", 97, 640, 496, 0.382e-3),
        makePD("PD290", 94, 800, 616, 0.286e-3),
        makeRobot72(), makeScottie("Scottie DX", 76, 1.08e-3),
    };
    return specs[mode];
}

const char *SstvDemod::modeName(int mode) { return mode >= 0 && mode < kModeCount ? spec(mode).name : "?"; }

// ---- front end ------------------------------------------------------------------------------------------------
SstvDemod::SstvDemod(int inputRate, int selection) : inRate_(inputRate), sel_(selection) {
    if (inRate_ != (int)kRate) {
        const bool half = inRate_ == 2 * (int)kRate;   // the usual 48 kHz: filter and keep every second sample
        preTaps_ = lowpass(half ? 33 : std::max(33, (int)(inRate_ / kRate * 24) | 1), 4500.0, inputRate);
        preBuf_.assign(2 * preTaps_.size(), 0.f);
        resStep_ = inRate_ / kRate;
    }
    lp_ = lowpass(81, 1050.0, kRate);
    bi_.assign(2 * lp_.size(), 0.f);
    bq_.assign(2 * lp_.size(), 0.f);
}

void SstvDemod::process(const float *in, int n, std::string &text) {
    out_ = &text;
    if (inRate_ == (int)kRate) {
        for (int i = 0; i < n; ++i) sample(in[i]);
        return;
    }
    const int len = (int)preTaps_.size();
    const bool decimate = inRate_ == 2 * (int)kRate;
    const float *h = preTaps_.data();
    for (int i = 0; i < n; ++i) {
        preBuf_[(size_t)prePos_] = preBuf_[(size_t)(prePos_ + len)] = in[i];
        prePos_ = (prePos_ + 1) % len;
        if (decimate && (++preCount_ & 1)) continue;   // only every second filtered sample is wanted
        const float *w = &preBuf_[(size_t)prePos_];   // the last `len` inputs, oldest first
        float acc = 0;
        for (int k = 0; k < len; ++k) acc += h[k] * w[k];
        if (decimate) { sample(acc); continue; }
        while (resPos_ <= 0.0) {
            sample((float)(resPrev_ + (acc - resPrev_) * (1.0 + resPos_)));
            resPos_ += resStep_;
        }
        resPos_ -= 1.0;
        resPrev_ = acc;
    }
}

// Mix 1900 Hz to baseband, low-pass, and keep conj(previous) * current: its angle is the phase advance per sample.
void SstvDemod::sample(float x) {
    // x * exp(-j*2*pi*1900*t): a tone above 1900 Hz then advances in phase. The oscillator turns by a fixed rotation.
    static const double dr = std::cos(kTwoPi * kCenter / kRate), di = -std::sin(kTwoPi * kCenter / kRate);
    const double r = nr_ * dr - ni_ * di; ni_ = nr_ * di + ni_ * dr; nr_ = r;
    if ((total_ & 0xFFF) == 0) { const double m = 1.0 / std::hypot(nr_, ni_); nr_ *= m; ni_ *= m; }   // keep it unit length
    const int taps = (int)lp_.size();
    bi_[(size_t)bufPos_] = bi_[(size_t)(bufPos_ + taps)] = (float)(x * nr_);
    bq_[(size_t)bufPos_] = bq_[(size_t)(bufPos_ + taps)] = (float)(x * ni_);
    bufPos_ = (bufPos_ + 1) % taps;
    const float *h = lp_.data(), *wi = &bi_[(size_t)bufPos_], *wq = &bq_[(size_t)bufPos_];
    float fi = 0, fq = 0;
    for (int k = 0; k < taps; ++k) { fi += h[k] * wi[k]; fq += h[k] * wq[k]; }
    const double i = fi, q = fq;
    zr_.push_back(fi); zi_.push_back(fq);
    cr_.push_back((float)(prevI_ * i + prevQ_ * q));
    ci_.push_back((float)(prevI_ * q - prevQ_ * i));
    prevI_ = i; prevQ_ = q;
    ++total_;
    onSample();
}

// Frequency (Hz) around sample `idx`: the angle of the summed phase advances over idx-half..idx+half.
bool SstvDemod::freqAt(long idx, int half, double &f) const { return freqSpan(idx - half, idx + half, f); }

bool SstvDemod::freqSpan(long lo, long hi, double &f) const {
    if (lo < base_ || hi >= total_ || hi < lo) return false;
    double sr = 0, si = 0;
    for (long k = lo; k <= hi; ++k) { sr += cr_[k - base_]; si += ci_[k - base_]; }
    f = kCenter + std::atan2(si, sr) * kRate / kTwoPi;
    return true;
}

// ---- spectral estimates ---------------------------------------------------------------------------------------
namespace {
const std::vector<float> &hann(int n) {
    static std::vector<std::vector<float>> cache(4096);
    auto &w = cache[(size_t)n];
    if (w.empty()) { w.resize((size_t)n); for (int i = 0; i < n; ++i) w[(size_t)i] = 0.5f - 0.5f * (float)std::cos(kTwoPi * i / std::max(1, n - 1)); }
    return w;
}
}  // namespace

// Power of the Hann-windowed span at `hz` (the baseband is centred on kCenter).
double SstvDemod::tonePower(long center, int half, double hz) const {
    const long lo = center - half, hi = center + half;
    if (lo < base_ || hi >= total_ || 2 * half + 1 >= 4096) return 0;
    const int n = 2 * half + 1;
    const auto &w = hann(n);
    const double dw = -kTwoPi * (hz - kCenter) / kRate;
    const double cs = std::cos(dw), sn = std::sin(dw);
    double pr = 1, pi = 0, sr = 0, si = 0;
    for (int k = 0; k < n; ++k) {
        const double zr = zr_[lo - base_ + k] * w[(size_t)k], zi = zi_[lo - base_ + k] * w[(size_t)k];
        sr += zr * pr - zi * pi; si += zr * pi + zi * pr;
        const double nr = pr * cs - pi * sn; pi = pr * sn + pi * cs; pr = nr;
    }
    return sr * sr + si * si;
}

bool SstvDemod::peakFreq(long center, int half, double fLo, double fHi, double step, double &f) const {
    int bins = (int)std::floor((fHi - fLo) / step) + 1;
    if (bins < 3) return false;
    // A wide search first on a grid four times coarser (a tone is far wider than that in the window's spectrum), then the
    // fine grid around the best coarse bin.
    if (bins > 20) {
        const double cs = 4 * step;
        const int cb = (int)std::floor((fHi - fLo) / cs) + 1;
        int best = 0;
        double bp = -1;
        for (int b = 0; b < cb; ++b) { const double p = tonePower(center, half, fLo + b * cs); if (p > bp) { bp = p; best = b; } }
        if (bp <= 0) return false;
        const double lo = std::max(fLo, fLo + best * cs - cs), hi = std::min(fHi, fLo + best * cs + cs);
        fLo = lo; fHi = hi;
        bins = (int)std::floor((fHi - fLo) / step) + 1;
        if (bins < 3) return false;
    }
    std::vector<double> p((size_t)bins);
    int best = 0;
    for (int b = 0; b < bins; ++b) { p[(size_t)b] = tonePower(center, half, fLo + b * step); if (p[(size_t)b] > p[(size_t)best]) best = b; }
    if (p[(size_t)best] <= 0) return false;
    double off = 0;
    if (best > 0 && best < bins - 1) {   // parabola through the log powers around the peak
        const double a = std::log(p[(size_t)best - 1] + 1e-30), b = std::log(p[(size_t)best] + 1e-30), c = std::log(p[(size_t)best + 1] + 1e-30);
        const double d = a - 2 * b + c;
        if (d < 0) off = std::max(-0.5, std::min(0.5, 0.5 * (a - c) / d));
    }
    f = fLo + (best + off) * step;
    return true;
}

void SstvDemod::onSample() {
    switch (state_) {
        case Idle: huntVis(); huntSync(); break;
        case AwaitSync: seekFirstSync(); break;
        case Image: imageStep(); break;
    }
    if ((total_ & 0x3FFF) == 0) trim();
}

void SstvDemod::trim() {
    long keep;
    if (state_ == Image) keep = curSync_ - (long)(0.5 * kRate);
    else if (state_ == AwaitSync) keep = stopCenter_ - (long)(0.1 * kRate);
    else keep = total_ - (long)(1.2 * kRate);
    keep = std::max(keep, base_);
    if (keep - base_ < 65536) return;
    const size_t drop = (size_t)(keep - base_);
    cr_.erase(cr_.begin(), cr_.begin() + drop);
    ci_.erase(ci_.begin(), ci_.begin() + drop);
    zr_.erase(zr_.begin(), zr_.begin() + drop);
    zi_.erase(zi_.begin(), zi_.begin() + drop);
    base_ = keep;
}

// ---- VIS header -----------------------------------------------------------------------------------------------
// Tones are measured every 10 ms over 20 ms. A header is 4 leader readings (1900 Hz), the start bit (1200), seven
// data bits (1100 = 1, 1300 = 0, least significant first), parity, and the stop bit (1200); each bit is 30 ms, so
// three readings apart. Any of the three alignments of the 10 ms grid may line up with the bits.
void SstvDemod::huntVis() {
    if ((double)total_ < nextTone_) return;
    nextTone_ += kRate * 0.01;
    const int toneHalf = (int)(0.01 * kRate);   // 20 ms window
    const long center = total_ - 1 - toneHalf;
    double f;
    if (!peakFreq(center, toneHalf, 900, 2700, 25, f)) return;
    tones_.push_back({center, f});
    if (tones_.size() > 60) tones_.erase(tones_.begin());
    const int n = (int)tones_.size();
    if (n < 40) return;
    for (int a = 0; a < 3; ++a) {
        const int s = n - 1 - a;
        if (s - 39 < 0) continue;
        double L = 0;
        for (int k : {30, 33, 36, 39}) L += tones_[s - k].f;
        L /= 4;
#ifdef SSTV_DEBUG
        if (std::fabs(L - kCenter) < 30 && std::fabs(tones_[s - 27].f - (L - 700)) < 80) {
            fprintf(stderr, "t=%.3f a=%d L=%.0f start=%.0f stop=%.0f bits:", (double)tones_[s].at / kRate, a, L, tones_[s-27].f, tones_[s].f);
            for (int b = 0; b < 8; ++b) fprintf(stderr, " %.0f", tones_[s - 24 + 3 * b].f);
            fprintf(stderr, "\n");
        }
#endif
        if (std::fabs(L - kCenter) > 250) continue;
        bool ok = true;
        for (int k : {30, 33, 36, 39}) if (std::fabs(tones_[s - k].f - L) > 35) ok = false;
        if (!ok) continue;
        if (std::fabs(tones_[s - 27].f - (L - 700)) > 40 || std::fabs(tones_[s].f - (L - 700)) > 40) continue;  // start / stop
        int vis = 0, ones = 0;
        for (int b = 0; b < 8 && ok; ++b) {
            const double f2 = tones_[s - 24 + 3 * b].f;
            if (f2 < L - 900 || f2 > L - 500) ok = false;
            const int bit = f2 < L - 700 ? 1 : 0;
            ones += bit;
            if (b < 7) vis |= bit << b;
        }
        if (!ok) continue;
        if (ones & 1) continue;   // even parity over the 7 data bits plus the parity bit
        int mode = -1;
        for (int m = 0; m < kModeCount; ++m) if (spec(m).vis == vis) mode = m;
        char msg[96];
        if (mode < 0) {
            const char *known = vis == 36 ? "Martin M3" : vis == 32 ? "Martin M4" :
                                vis == 2 ? "Robot 8 b/w" : vis == 4 ? "Robot 24" : vis == 6 ? "Robot 12 b/w" : nullptr;
            std::snprintf(msg, sizeof msg, "\nSSTV VIS %d%s%s: not supported yet\n", vis, known ? " " : "", known ? known : "");
        } else if (sel_ >= 0 && sel_ != mode) {
            std::snprintf(msg, sizeof msg, "\nSSTV %s detected, but %s is selected\n", spec(mode).name, spec(sel_).name);
        } else {
            std::snprintf(msg, sizeof msg, "\nSSTV %s (VIS %d)%s\n", spec(mode).name, vis, std::fabs(L - kCenter) > 12 ? " off tune" : "");
            if (out_) *out_ += msg;
            startPicture(mode, L, tones_[s].at);
            return;
        }
        if (out_) *out_ += msg;
        tones_.clear();   // do not report the same header again
        return;
    }
}

// ---- header-less starts ---------------------------------------------------------------------------------------
// A sync pulse is a stretch of 1200 Hz between 3 and 26 ms (longer is the header's own tones) whose end is the first
// sample back above the sync threshold.
void SstvDemod::huntSync() {
    if (total_ < sNext_) return;
    sNext_ = total_ + 4;   // the exact edge is found again, sample by sample, once a picture starts
    const int half = 40;
    const long c = total_ - 1 - half;
    if (c - half < base_) return;
    if (syncMetric(c, half, true) >= 0.5) { ++sLow_; sGap_ = 0; return; }
    if (sLow_ == 0) return;
    if ((sGap_ += 4) <= 8) return;
    // The pulse ended where the tone first dropped; its length is the run of sync-like samples.
    const int len = sLow_ * 4;   // the run was counted at every fourth sample
    const long end = c - sGap_ + 1;
    sLow_ = 0; sGap_ = 0;
    if (len < (int)(0.003 * kRate) || len > (int)(0.026 * kRate)) return;   // not a sync pulse
    pulses_.push_back({end, len});
    if (pulses_.size() > 8) pulses_.erase(pulses_.begin());
    tryJoin();
}

void SstvDemod::tryJoin() {
    const int need = 4;
    if ((int)pulses_.size() < need) return;
    const Pulse *p = &pulses_[pulses_.size() - need];
    double d[need - 1], D = 0, meanLen = 0;
    for (int i = 0; i < need - 1; ++i) { d[i] = (double)(p[i + 1].end - p[i].end); D += d[i]; }
    D /= need - 1;
    for (int i = 0; i < need; ++i) meanLen += p[i].len;
    meanLen /= need;
    double spread = 0;
    for (int i = 0; i < need - 1; ++i) spread = std::max(spread, std::fabs(d[i] - D));
    int best = -1;
    double bestRel = 1e9;
    for (int m = 0; m < kModeCount; ++m) {
        if (sel_ >= 0 && m != sel_) continue;
        const Spec &sp = spec(m);
        const double L = sp.lineTime * kRate, rel = std::fabs(D - L) / L;
        if (rel > 0.012 || spread > 0.0015 * kRate + 0.001 * L) continue;
        if (std::fabs(meanLen - sp.syncLen * kRate) > 0.4 * sp.syncLen * kRate) continue;
        if (rel < bestRel) { bestRel = rel; best = m; }
    }
    if (best < 0) return;
    // Tuning error from the sync tone itself (it should sit at 1200 Hz).
    double fs = 0; int nf = 0;
    for (int i = 0; i < need; ++i) { double f; if (peakFreq(p[i].end - p[i].len / 2, std::max(8, std::min(60, p[i].len / 2 - 6)), 1000, 1400, 10, f)) { fs += f; ++nf; } }
    double shift = nf ? fs / nf - kSyncHz : 0;
    if (std::fabs(shift) > 150) shift = 0;
    joinPicture(best, p[need - 1].end, D, shift);
}

void SstvDemod::joinPicture(int mode, long lastSyncEnd, double meanInterval, double shift) {
    const Spec &sp = spec(mode);
    mode_ = mode;
    shift_ = shift;
    ++pictureId_;
    picState_ = 1;
    rowsReported_ = 0; rowsRendered_ = 0; exposed_ = 0;
    rgb_.assign((size_t)sp.width * sp.lines * 3, 0);
    chromaR_.assign(sp.width, 128.f); chromaB_.assign(sp.width, 128.f);
    lineIdx_ = 0; misses_ = 0; rowRendered_ = false; noiseHz_ = -1;
    lHat_ = std::min(sp.lineTime * 1.015, std::max(sp.lineTime * 0.985, meanInterval / kRate));
    curSync_ = lastSyncEnd;
    stopCenter_ = lastSyncEnd;
    state_ = Image;
    tones_.clear(); pulses_.clear(); sLow_ = 0;
    char msg[96];
    std::snprintf(msg, sizeof msg, "\nSSTV %s (no header; joined from its sync pulses)\n", sp.name);
    if (out_) *out_ += msg;
}

void SstvDemod::startPicture(int mode, double leaderHz, long stopCenter) {
    const Spec &sp = spec(mode);
    mode_ = mode;
    shift_ = leaderHz - kCenter;
    ++pictureId_;
    picState_ = 1;
    rowsReported_ = 0; rowsRendered_ = 0; exposed_ = 0;
    rgb_.assign((size_t)sp.width * sp.lines * 3, 0);
    chromaR_.assign(sp.width, 128.f); chromaB_.assign(sp.width, 128.f);
    lineIdx_ = 0; misses_ = 0; rowRendered_ = false; noiseHz_ = -1;
    lHat_ = sp.lineTime;
    stopCenter_ = stopCenter;
    // Martin and Robot: the first sync follows the stop bit, so look from its middle. Scottie sends two scans first.
    scanPos_ = sp.firstSyncEndDelay > 0 ? stopCenter + (long)((0.015 + sp.firstSyncEndDelay - 0.09) * kRate) : stopCenter;
    lowRun_ = 0; gap_ = 0;
    state_ = AwaitSync;
    tones_.clear(); pulses_.clear(); sLow_ = 0;
}

// How much of the signal around `c` is the sync tone: its power against the strongest picture tone. The spectral
// measure holds up in noise where the frequency itself (whose estimate is pulled toward the centre) does not.
double SstvDemod::syncMetric(long c, int half, bool fast) const {
    double ps = 0, pv = 0;
    if (fast) {
        ps = tonePower(c, half, kSyncHz + shift_);
        for (double hz : {1500.0, 1900.0, 2300.0}) pv = std::max(pv, tonePower(c, half, hz + shift_));
    } else {
        for (double d : {-40.0, 0.0, 40.0}) ps = std::max(ps, tonePower(c, half, kSyncHz + shift_ + d));
        for (double hz : {1500.0, 1700.0, 1900.0, 2100.0, 2300.0}) pv = std::max(pv, tonePower(c, half, hz + shift_));
    }
    return ps + pv > 0 ? ps / (ps + pv) : 0.0;
}

// The window must be shorter than the pulse (so it can sit inside it) but long enough to tell 1200 Hz from 1500 Hz.
int SstvDemod::syncHalf(int mode) const {
    const double ms = std::min(6.0, 0.65 * spec(mode).syncLen * 1e3);
    return std::max(20, (int)(ms * 1e-3 * kRate / 2));
}

// The end of the first sync pulse: the first low-to-high crossing of the sync threshold after the header.
void SstvDemod::seekFirstSync() {
    const Spec &sp = spec(mode_);
    const int half = syncHalf(mode_), look = 16;
    const int minRun = (int)(0.5 * sp.syncLen * kRate);
    while (scanPos_ + half + look < total_) {
        if (syncMetric(scanPos_, half) >= 0.5) { ++lowRun_; gap_ = 0; }
        else if (lowRun_ >= minRun) {
            if (++gap_ > 6) {   // the pulse is over: it ended where the tone first dropped
                curSync_ = scanPos_ - gap_ + 1;
                state_ = Image;
                return;
            }
        } else lowRun_ = 0;
        ++scanPos_;
    }
    const double limit = (0.015 + sp.firstSyncEndDelay + 0.6) * kRate;
    if (total_ - stopCenter_ > limit) finish("no sync after the header");
}

// The best end of a sync pulse in [lo, hi] for one expected at `expect`: close to it, preceded by a stretch of sync tone
// about as long as a pulse (noise that splits a pulse leaves shorter stretches; gaps of a few samples are bridged).
long SstvDemod::findCrossing(long lo, long hi, int minRun, long expect, int syncSamples) const {
    const int half = syncHalf(mode_), look = 16, maxRun = (int)(1.4 * syncSamples);
    const long from = lo - maxRun - 8, to = hi + look;
    if (from - half < base_ || to + half >= total_) return -1;
    std::vector<char> sync((size_t)(to - from + 1));
    for (long i = from; i <= to; ++i) sync[(size_t)(i - from)] = syncMetric(i, half) >= 0.5;
    auto at = [&](long i) { return sync[(size_t)(i - from)] != 0; };
    long best = -1;
    double bestCost = 1e18;
    for (long i = lo; i <= hi; ++i) {
        if (!at(i - 1) || at(i)) continue;                        // a pulse ends here
        bool clear = true;
        for (long k = 0; k < look && clear; ++k) if (at(i + k)) clear = false;   // and the tone stays gone
        if (!clear) continue;
        long start = i - 1; int gap = 0;
        for (long j = i - 1; j >= from && i - j <= maxRun; --j) { if (at(j)) { start = j; gap = 0; } else if (++gap > 6) break; }
        const int run = (int)(i - start);
        if (run < minRun) continue;
        const double shortfall = std::max(0.0, 0.75 * syncSamples - run);
        const double cost = (double)std::labs(i - expect) + shortfall;
        if (cost < bestCost) { bestCost = cost; best = i; }
    }
    return best;
}

// ---- picture --------------------------------------------------------------------------------------------------
void SstvDemod::imageStep() {
    const Spec &sp = spec(mode_);
    for (;;) {
        if (!rowRendered_) {
            const long horizon = curSync_ + (long)((sp.rowEnd + 0.0005) * kRate) + 4;
            if (total_ <= horizon) return;
            renderLine(lineIdx_);
            rowRendered_ = true;
            if (lineIdx_ == sp.lines / sp.rowsPerLine - 1) { finish(nullptr); return; }
        }
        // Where the next line's sync should end, and room either side. After missed pulses the room grows, so a
        // line start that has drifted can be found again.
        const long expect = curSync_ + (long)std::lround(lHat_ * kRate);
        const long gate = (long)(std::max(0.002, 0.006 * sp.lineTime) * kRate);   // how far a believable pulse can be from the prediction
        const long window = std::max((long)(0.006 * kRate), gate + (long)(0.002 * kRate)) + (long)(0.004 * kRate) * std::min(misses_, 10);
        if (total_ <= expect + window + syncHalf(mode_) + 20) return;   // the sync search looks a little past the window
        const int syncSamples = (int)std::lround(sp.syncLen * kRate);
        const long found = findCrossing(expect - window, expect + window, (int)(0.5 * syncSamples), expect, syncSamples);
        const bool inGate = found >= 0 && std::labs(found - expect) <= gate;
        if (found >= 0 && (inGate || misses_ >= 3)) {
            if (inGate) {   // an edge far from the prediction is more likely noise than a change of line time
                const double measured = (double)(found - curSync_) / kRate;
                lHat_ += 0.3 * (measured - lHat_);
                lHat_ = std::min(sp.lineTime * 1.015, std::max(sp.lineTime * 0.985, lHat_));
            }
            curSync_ = found;
            misses_ = 0;
        } else {
            curSync_ = expect;   // keep going on the predicted position through a fade
            if (++misses_ >= 12) { finish("lost sync"); return; }
        }
#ifdef SSTV_TRACE
        fprintf(stderr, "TRACE line %d found=%d offset=%ld lHat=%.5f misses=%d\n", lineIdx_, found >= 0, found >= 0 ? found - expect : 0L, lHat_, misses_);
#endif
        ++lineIdx_;
        rowRendered_ = false;
    }
}

// Averaging window (ms) for a measured noise scatter (Hz). Found by sweeping the window against noise on generated
// pictures (tests/sstv_weak): the best window was 2 ms at 85 Hz, 3 ms at 160-250 Hz and 5 ms beyond about 350 Hz. The
// scatter measure saturates near 400 Hz, where the noise is at least that bad.
static double windowMs(double noiseHz) {
    if (noiseHz < 0) return 0;
    static const double hz[] = {10, 85, 160, 260, 400}, ms[] = {0, 2.0, 2.8, 4.0, 5.0};
    if (noiseHz <= hz[0]) return ms[0];
    for (int i = 1; i < 5; ++i)
        if (noiseHz <= hz[i]) return ms[i - 1] + (ms[i] - ms[i - 1]) * (noiseHz - hz[i - 1]) / (hz[i] - hz[i - 1]);
    return ms[4];
}

// The sync pulse is a pure 1200 Hz tone, so the scatter of the per-sample frequency inside it measures the noise (a clean
// signal scatters by a few Hz; 10 dB SNR by hundreds). A median absolute deviation, so the odd noise click does not count.
double SstvDemod::lineNoise() const {
    const Spec &sp = spec(mode_);
    const long hi = curSync_ - (long)(0.25 * sp.syncLen * kRate), lo = curSync_ - (long)(0.75 * sp.syncLen * kRate);
    if (lo - 1 < base_ || hi >= total_ || hi - lo < 8) return 0;
    std::vector<double> d;
    d.reserve((size_t)(hi - lo + 1));
    for (long i = lo; i <= hi; ++i) d.push_back(std::atan2((double)ci_[i - base_], (double)cr_[i - base_]) * kRate / kTwoPi);
    std::vector<double> t = d;
    std::nth_element(t.begin(), t.begin() + t.size() / 2, t.end());
    const double med = t[t.size() / 2];
    for (auto &v : t) v = std::fabs(v - med);
    std::nth_element(t.begin(), t.begin() + t.size() / 2, t.end());
    return 1.4826 * t[t.size() / 2];
}

void SstvDemod::renderLine(int line) {
    const Spec &sp = spec(mode_);
    const int W = sp.width;
    std::vector<float> plane[4];
    // The measured line time against the nominal one is the sender's clock error; it stretches everything inside the
    // line equally, so positions and lengths follow it (the line start itself comes from the sync pulse).
    const double k = lHat_ / sp.lineTime;
    {   // noise from this line's own sync pulse, smoothed over lines so one odd pulse does not swing the window
        const double n = lineNoise();
        if (n > 0) noiseHz_ = noiseHz_ < 0 ? n : 0.7 * noiseHz_ + 0.3 * n;
    }
    for (int c = 0; c < sp.nch; ++c) {
        const Channel ch{sp.ch[c].plane, sp.ch[c].start * k, sp.ch[c].len * k, sp.ch[c].nativeW};
        auto &p = plane[c];
        p.assign(W, 0.f);
        // Average over longer stretches as the signal gets noisier: the window is the pixel's own length when the signal
        // is clean and grows with the measured noise (about 2 ms at 15 dB SNR, 5 ms at the worst); the picture gets
        // softer sideways but stops being speckled. See windowMs().
        const double pixelMs = ch.len / ch.nativeW * 1e3;
        const int half = std::max(2, (int)std::lround(std::max(pixelMs, windowMs(noiseHz_)) * 1e-3 * kRate / 2.0));
        // A pixel's window must not reach into the neighbouring sync pulse, porch or separator: clamp it to the channel.
        const long segLo = curSync_ + (long)std::lround(ch.start * kRate), segHi = curSync_ + (long)std::lround((ch.start + ch.len) * kRate) - 1;
        // The input filter smears every transition by about half a millisecond, so pixels right at either end of the
        // channel see the neighbouring tone; they take the value of the nearest pixel that does not.
        const long edge = (long)(0.0005 * kRate);
        int lo = -1, hi = -1;
        for (int x = 0; x < W; ++x) {
            const long idx = curSync_ + (long)std::lround((ch.start + (x + 0.5) / W * ch.len) * kRate);
            double f;
            p[x] = freqSpan(std::max(idx - half, segLo), std::min(idx + half, segHi), f) ? (float)((f - (kBlack + shift_)) * 255.0 / (kWhite - kBlack)) : 0.f;
            if (idx >= segLo + edge && idx <= segHi - edge) { if (lo < 0) lo = x; hi = x; }
        }
        if (lo >= 0) {
            for (int x = 0; x < lo; ++x) p[x] = p[lo];
            for (int x = hi + 1; x < W; ++x) p[x] = p[hi];
        }
    }
    auto yuv = [&](uint8_t *dst, const float *Y, const float *Cr, const float *Cb) {
        for (int x = 0; x < W; ++x) {
            dst[x * 3 + 0] = clip8((100 * Y[x] + 140 * Cr[x] - 17850) / 100.0);
            dst[x * 3 + 1] = clip8((100 * Y[x] - 71 * Cr[x] - 33 * Cb[x] + 13260) / 100.0);
            dst[x * 3 + 2] = clip8((100 * Y[x] + 178 * Cb[x] - 22695) / 100.0);
        }
    };
    if (sp.rowsPerLine == 2) {
        // PD: even-row luminance, R-Y, B-Y, odd-row luminance.
        yuv(&rgb_[(size_t)(2 * line) * W * 3], plane[0].data(), plane[1].data(), plane[2].data());
        yuv(&rgb_[(size_t)(2 * line + 1) * W * 3], plane[3].data(), plane[1].data(), plane[2].data());
        rowsRendered_ = 2 * line + 2;
        if (misses_ == 0) exposed_ = rowsRendered_;
        return;
    }
    uint8_t *dst = &rgb_[(size_t)line * W * 3];
    if (sp.yuv) {
        yuv(dst, plane[0].data(), plane[1].data(), plane[2].data());   // Robot 72
    } else if (!sp.robot) {
        for (int x = 0; x < W; ++x)
            for (int c = 0; c < sp.nch; ++c) dst[x * 3 + sp.ch[c].plane] = clip8(plane[c][x]);
    } else {
        // The separator after the luminance scan says which colour difference follows: 1500 Hz = R-Y, 2300 Hz = B-Y.
        const long mid = curSync_ + (long)std::lround((sp.sepStart + sp.sepLen / 2) * k * kRate);
        const int sepHalf = (int)(0.4 * sp.sepLen * kRate / 2);
        const double p15 = tonePower(mid, sepHalf, kBlack + shift_), p23 = tonePower(mid, sepHalf, kWhite + shift_);
        const bool isRy = (p15 + p23 > 0) ? (p15 > p23) : (line % 2 == 0);
        auto &store = isRy ? chromaR_ : chromaB_;
        for (int x = 0; x < W; ++x) store[x] = plane[1][x];
        yuv(dst, plane[0].data(), chromaR_.data(), chromaB_.data());
    }
    rowsRendered_ = line + 1;
    if (misses_ == 0) exposed_ = rowsRendered_;
}

void SstvDemod::finish(const char *why) {
    picState_ = 2;
    char msg[96];
    if (why) { std::snprintf(msg, sizeof msg, "\nSSTV picture ended: %s (%d of %d lines)\n", why, exposed_, spec(mode_).lines); if (out_) *out_ += msg; }
    else { exposed_ = rowsRendered_; if (out_) *out_ += "\nSSTV picture complete\n"; }
    state_ = Idle;
    tones_.clear(); pulses_.clear(); sLow_ = 0;
    nextTone_ = (double)total_;
}

size_t SstvDemod::pollPicture(std::vector<uint8_t> &out) {
    if (pictureId_ == 0) return 0;
    const Spec &sp = spec(mode_);
    const int rowsDone = std::min(exposed_, sp.lines);
    const int first = rowsReported_, count = std::max(0, rowsDone - rowsReported_);
    const size_t start = out.size(), rowBytes = (size_t)sp.width * 3;
    int32_t header[7] = {pictureId_, sp.width, sp.lines, 1, first, count, picState_};
    out.resize(start + sizeof header + count * rowBytes);
    std::memcpy(out.data() + start, header, sizeof header);
    if (count) std::memcpy(out.data() + start + sizeof header, rgb_.data() + (size_t)first * rowBytes, count * rowBytes);
    rowsReported_ += count;
    return out.size() - start;
}
