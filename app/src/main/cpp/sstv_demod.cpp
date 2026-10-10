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
    s.name = name; s.vis = vis; s.width = 320; s.lines = 256; s.syncLen = sync; s.robot = false; s.rowsPerLine = 1;
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
    s.name = name; s.vis = vis; s.width = 320; s.lines = 256; s.syncLen = sync; s.robot = false; s.rowsPerLine = 1;
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
    s.name = "Robot 36"; s.vis = 8; s.width = 320; s.lines = 240; s.syncLen = 9e-3; s.robot = true; s.rowsPerLine = 1;
    s.ch[0] = {0, 3e-3, 88e-3, 320};                  // Y
    s.sepStart = 3e-3 + 88e-3; s.sepLen = 4.5e-3;     // 1500 Hz: next is R-Y (even lines); 2300 Hz: B-Y (odd)
    s.ch[1] = {1, 3e-3 + 88e-3 + 4.5e-3 + 1.5e-3, 44e-3, 160};  // chroma, half horizontal resolution
    s.nch = 2;
    s.rowEnd = s.ch[1].start + 44e-3;
    s.lineTime = 150e-3;
    s.firstSyncEndDelay = 0;
    return s;
}

// PD modes (Paul Dunphy, PD-xxx): one sync pulse per two image rows. The frame is sync (20 ms), porch (2.08 ms), then four
// scans back to back: luminance of the even row, R-Y, B-Y (both shared by the two rows), luminance of the odd row.
static SstvDemod::Spec makePD(const char *name, int vis, int width, int lines, double pixel) {
    const double scan = width * pixel, porch = 2.08e-3, sync = 20e-3;
    SstvDemod::Spec s{};
    s.name = name; s.vis = vis; s.width = width; s.lines = lines; s.syncLen = sync; s.robot = false; s.rowsPerLine = 2;
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
    };
    return specs[mode];
}

const char *SstvDemod::modeName(int mode) { return mode >= 0 && mode < kModeCount ? spec(mode).name : "?"; }

// ---- front end ------------------------------------------------------------------------------------------------
SstvDemod::SstvDemod(int inputRate, int selection) : inRate_(inputRate), sel_(selection) {
    if (inRate_ != (int)kRate) {
        preTaps_ = lowpass(std::max(33, (int)(inRate_ / kRate * 24) | 1), 4500.0, inputRate);
        preBuf_.assign(preTaps_.size(), 0.f);
        resStep_ = inRate_ / kRate;
    }
    lp_ = lowpass(81, 1050.0, kRate);
    bi_.assign(lp_.size(), 0.0);
    bq_.assign(lp_.size(), 0.0);
}

void SstvDemod::process(const float *in, int n, std::string &text) {
    out_ = &text;
    if (inRate_ == (int)kRate) {
        for (int i = 0; i < n; ++i) sample(in[i]);
        return;
    }
    const int len = (int)preTaps_.size();
    for (int i = 0; i < n; ++i) {
        preBuf_[prePos_] = in[i];
        prePos_ = (prePos_ + 1) % len;
        float acc = 0;
        for (int k = 0, p = prePos_; k < len; ++k, p = (p + 1) % len) acc += preTaps_[k] * preBuf_[p];
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
    nco_ += kTwoPi * kCenter / kRate;
    if (nco_ > kTwoPi) nco_ -= kTwoPi;
    const int taps = (int)lp_.size();
    bi_[bufPos_] = x * std::cos(nco_);
    bq_[bufPos_] = -x * std::sin(nco_);   // x * exp(-j*nco): a tone above 1900 Hz then advances in phase
    bufPos_ = (bufPos_ + 1) % taps;
    double i = 0, q = 0;
    for (int k = 0, p = bufPos_; k < taps; ++k, p = (p + 1) % taps) { i += lp_[k] * bi_[p]; q += lp_[k] * bq_[p]; }
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

void SstvDemod::onSample() {
    switch (state_) {
        case Idle: huntVis(); break;
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
    base_ = keep;
}

// ---- VIS header -----------------------------------------------------------------------------------------------
// Tones are measured every 10 ms over 20 ms. A header is 4 leader readings (1900 Hz), the start bit (1200), seven
// data bits (1100 = 1, 1300 = 0, least significant first), parity, and the stop bit (1200); each bit is 30 ms, so
// three readings apart. Any of the three alignments of the 10 ms grid may line up with the bits.
void SstvDemod::huntVis() {
    if ((double)total_ < nextTone_) return;
    nextTone_ += kRate * 0.01;
    const long center = total_ - 1 - 220;
    double f;
    if (!freqAt(center, 220, f)) return;
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
            const char *known = vis == 76 ? "Scottie DX" : vis == 12 ? "Robot 72" : vis == 36 ? "Martin M3" : vis == 32 ? "Martin M4" :
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

void SstvDemod::startPicture(int mode, double leaderHz, long stopCenter) {
    const Spec &sp = spec(mode);
    mode_ = mode;
    shift_ = leaderHz - kCenter;
    ++pictureId_;
    picState_ = 1;
    rowsReported_ = 0; rowsRendered_ = 0;
    rgb_.assign((size_t)sp.width * sp.lines * 3, 0);
    chromaR_.assign(sp.width, 128.f); chromaB_.assign(sp.width, 128.f);
    lineIdx_ = 0; misses_ = 0; rowRendered_ = false;
    lHat_ = sp.lineTime;
    stopCenter_ = stopCenter;
    // Martin and Robot: the first sync follows the stop bit, so look from its middle. Scottie sends two scans first.
    scanPos_ = sp.firstSyncEndDelay > 0 ? stopCenter + (long)((0.015 + sp.firstSyncEndDelay - 0.09) * kRate) : stopCenter;
    lowRun_ = 0;
    state_ = AwaitSync;
    tones_.clear();
}

// The end of the first sync pulse: the first low-to-high crossing of the sync threshold after the header.
void SstvDemod::seekFirstSync() {
    const Spec &sp = spec(mode_);
    const double thr = kSyncHz + 150 + shift_;
    const int h = 5;
    while (scanPos_ + h < total_) {
        double f;
        if (!freqAt(scanPos_, h, f)) { ++scanPos_; continue; }
        if (f < thr) ++lowRun_;
        else {
            if (lowRun_ >= (int)(0.5 * sp.syncLen * kRate)) {
                curSync_ = scanPos_;
                state_ = Image;
                return;
            }
            lowRun_ = 0;
        }
        ++scanPos_;
    }
    const double limit = (0.015 + sp.firstSyncEndDelay + 0.6) * kRate;
    if (total_ - stopCenter_ > limit) finish("no sync after the header");
}

// First low-to-high crossing in [lo, hi] closest to... the caller passes a window around where it expects the sync.
long SstvDemod::findCrossing(long lo, long hi, int minRun) const {
    const double thr = kSyncHz + 150 + shift_;
    const int h = 5;
    long best = -1, bestCenter = (lo + hi) / 2;
    double prev;
    if (!freqAt(lo - 1, h, prev)) return -1;
    for (long i = lo; i <= hi; ++i) {
        double f;
        if (!freqAt(i, h, f)) return best;
        if (f >= thr && prev < thr) {
            int run = 0;
            for (long j = i - 1; j >= i - minRun - 1; --j) { double g; if (!freqAt(j, h, g) || g >= thr) break; ++run; }
            if (run >= minRun && (best < 0 || std::labs(i - bestCenter) < std::labs(best - bestCenter))) best = i;
        }
        prev = f;
    }
    return best;
}

// ---- picture --------------------------------------------------------------------------------------------------
void SstvDemod::imageStep() {
    const Spec &sp = spec(mode_);
    const int h = 5;
    for (;;) {
        if (!rowRendered_) {
            const long horizon = curSync_ + (long)((sp.rowEnd + 0.0005) * kRate) + 4;
            if (total_ <= horizon) return;
            renderLine(lineIdx_);
            rowRendered_ = true;
            if (lineIdx_ == sp.lines / sp.rowsPerLine - 1) { finish(nullptr); return; }
        }
        // Where the next line's sync should end, and a little room either side.
        const long expect = curSync_ + (long)std::lround(lHat_ * kRate);
        const long window = (long)(0.006 * kRate);
        if (total_ <= expect + window + h + 2) return;
        const long found = findCrossing(expect - window, expect + window, (int)(0.5 * sp.syncLen * kRate));
        if (found >= 0) {
            const double measured = (double)(found - curSync_) / kRate;
            lHat_ += 0.3 * (measured - lHat_);
            lHat_ = std::min(sp.lineTime * 1.015, std::max(sp.lineTime * 0.985, lHat_));
            curSync_ = found;
            misses_ = 0;
        } else {
            curSync_ = expect;   // keep going on the predicted position through a fade
            if (++misses_ >= 12) { finish("lost sync"); return; }
        }
        ++lineIdx_;
        rowRendered_ = false;
    }
}

void SstvDemod::renderLine(int line) {
    const Spec &sp = spec(mode_);
    const int W = sp.width;
    std::vector<float> plane[4];
    // The measured line time against the nominal one is the sender's clock error; it stretches everything inside the
    // line equally, so positions and lengths follow it (the line start itself comes from the sync pulse).
    const double k = lHat_ / sp.lineTime;
    for (int c = 0; c < sp.nch; ++c) {
        const Channel ch{sp.ch[c].plane, sp.ch[c].start * k, sp.ch[c].len * k, sp.ch[c].nativeW};
        auto &p = plane[c];
        p.assign(W, 0.f);
        const int half = std::max(2, (int)std::lround(ch.len / ch.nativeW * kRate / 2.0));
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
        return;
    }
    uint8_t *dst = &rgb_[(size_t)line * W * 3];
    if (!sp.robot) {
        for (int x = 0; x < W; ++x)
            for (int c = 0; c < sp.nch; ++c) dst[x * 3 + sp.ch[c].plane] = clip8(plane[c][x]);
    } else {
        // The separator after the luminance scan says which colour difference follows: 1500 Hz = R-Y, 2300 Hz = B-Y.
        double sepF = 0;
        const long mid = curSync_ + (long)std::lround((sp.sepStart + sp.sepLen / 2) * k * kRate);
        const bool haveSep = freqAt(mid, (int)(0.4 * sp.sepLen * kRate / 2), sepF);
        const bool isRy = haveSep ? (sepF < kCenter + shift_) : (line % 2 == 0);
        auto &store = isRy ? chromaR_ : chromaB_;
        for (int x = 0; x < W; ++x) store[x] = plane[1][x];
        yuv(dst, plane[0].data(), chromaR_.data(), chromaB_.data());
    }
    rowsRendered_ = line + 1;
}

void SstvDemod::finish(const char *why) {
    picState_ = 2;
    char msg[96];
    if (why) { std::snprintf(msg, sizeof msg, "\nSSTV picture ended: %s (%d of %d lines)\n", why, rowsRendered_, spec(mode_).lines); if (out_) *out_ += msg; }
    else if (out_) *out_ += "\nSSTV picture complete\n";
    state_ = Idle;
    tones_.clear();
    nextTone_ = (double)total_;
}

size_t SstvDemod::pollPicture(std::vector<uint8_t> &out) {
    if (pictureId_ == 0) return 0;
    const Spec &sp = spec(mode_);
    const int rowsDone = std::min(rowsRendered_, sp.lines);
    const int first = rowsReported_, count = std::max(0, rowsDone - rowsReported_);
    const size_t start = out.size(), rowBytes = (size_t)sp.width * 3;
    int32_t header[7] = {pictureId_, sp.width, sp.lines, 1, first, count, picState_};
    out.resize(start + sizeof header + count * rowBytes);
    std::memcpy(out.data() + start, header, sizeof header);
    if (count) std::memcpy(out.data() + start + sizeof header, rgb_.data() + (size_t)first * rowBytes, count * rowBytes);
    rowsReported_ += count;
    return out.size() - start;
}
