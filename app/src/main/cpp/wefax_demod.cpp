#include "wefax_demod.h"
#include "wefax_filters.h"
#include <algorithm>
#include <cmath>
#include <cstring>

namespace {
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
bool isNear(int a, int b, int tol) { return std::abs(a - b) <= tol; }
constexpr double kShiftHz = 800.0;  // black 1500 .. white 2300 Hz
}  // namespace

WefaxDemod::WefaxDemod(int inputRate, int ioc, float centerHz)
    : inRate_(inputRate), center_(centerHz), userCenter_(centerHz), ioc_(ioc) {
    if (inRate_ != kRate) {
        preTaps_ = lowpass(std::max(33, (inRate_ / kRate) * 24 + 1), 4500.0, inputRate);
        preBuf_.assign(preTaps_.size(), 0.f);
        resStep_ = (double)inRate_ / kRate;
    }
    bufI_.assign(kWefaxFilterTaps, 0.0);
    bufQ_.assign(kWefaxFilterTaps, 0.0);
    deviationRatio_ = (kRate / kShiftHz) / (2 * M_PI);
    width_ = (int)(ioc * M_PI);
    aptStartFreq_ = ioc == 288 ? 675 : 300;
    // The 675 Hz IOC-288 start tone is attenuated by the narrower input filters (pixel swing ~100..150 on
    // Narrow, ~60..200 on Medium), so use the Wide filter and relaxed thresholds there; fldigi's 215/40 otherwise.
    if (ioc == 288) { filter_ = 2; aptHi_ = 190; aptLo_ = 65; }
    defaultLpm_ = ioc == 288 ? 60 : 120;
    lpm_ = defaultLpm_;
}

void WefaxDemod::setLpmInternal(double lpm) {
    if (lpm < 10) return;
    lpm_ = lpm;
    invalidate();
}

void WefaxDemod::setSlantPercent(double pct) { slantPct_ = pct; invalidate(); }
void WefaxDemod::setShift(double f) { shift_ = std::clamp(f, -0.5, 0.5); invalidate(); }

void WefaxDemod::setManual(bool manual) {
    manual_ = manual;
    if (manual) beginImage(false); else restart();
}

void WefaxDemod::restart() {
    state_ = AptStart;
    aptHigh_ = false; aptTrans_ = aptCount_ = 0;
    std::fill(crFreq_, crFreq_ + 3, 0); currFreq_ = 0;
    samples_.clear();
    ++imageId_;
    invalidate();
}

void WefaxDemod::process(const float *in, int n) {
    if (inRate_ == kRate) {
        for (int i = 0; i < n; ++i) sampleRate(in[i]);
        return;
    }
    const int len = (int)preTaps_.size();
    for (int i = 0; i < n; ++i) {
        preBuf_[prePos_] = in[i];
        prePos_ = (prePos_ + 1) % len;
        float acc = 0;
        for (int k = 0, p = prePos_; k < len; ++k, p = (p + 1) % len) acc += preTaps_[k] * preBuf_[p];
        // `acc` is filtered input sample i (time = i). Emit every output time that falls in (i-1, i].
        while (resPos_ <= 0.0) {
            double frac = 1.0 + resPos_;  // position between the previous (0) and current (1) filtered sample
            sampleRate((float)(resPrev_ + (acc - resPrev_) * frac));
            resPos_ += resStep_;
        }
        resPos_ -= 1.0;
        resPrev_ = acc;
    }
}

// Mix the subcarrier to baseband, low-pass, and read the instantaneous frequency offset as a 0..255 "pixel".
void WefaxDemod::sampleRate(float x) {
    nco_ += 2 * M_PI * center_ / kRate;
    if (nco_ > 2 * M_PI) nco_ -= 2 * M_PI;
    const int taps = kWefaxFilterTaps;
    bufI_[bufPos_] = x * std::cos(nco_);
    bufQ_[bufPos_] = x * std::sin(nco_);
    bufPos_ = (bufPos_ + 1) % taps;
    const double *h = kWefaxFilters[filter_];
    double i = 0, q = 0;
    for (int k = 0, p = bufPos_; k < taps; ++k, p = (p + 1) % taps) { i += h[k] * bufI_[p]; q += h[k] * bufQ_[p]; }

    int pix;
    double raw = 255.0;
    const double clip = 0.001;
    if (std::hypot(i, q) <= clip && std::hypot(prevI_, prevQ_) <= clip) {
        pix = 255;  // no signal: white, as fldigi
    } else {
        // arg(conj(prev) * curr)
        double re = prevI_ * i + prevQ_ * q, im = prevI_ * q - prevQ_ * i;
        double ph = std::atan2(im, re);
        raw = 255 * (0.5 - deviationRatio_ * ph);  // unclamped: needed to see how far black/white are off
        pix = (int)std::lround(raw);
        pix = std::min(std::max(0, pix), 255);
    }
    prevI_ = i; prevQ_ = q;
    step(pix, raw);
}

void WefaxDemod::step(int x, double raw) {
    currentValue_ = x;
    if (manual_) {
        samples_.push_back((uint8_t)x);
        return;
    }
    decodeApt(x);
    if (state_ == Phasing) decodePhasing(x, raw);
    if (state_ == Image) {
        samples_.push_back((uint8_t)x);
        if (samples_.size() >= (size_t)(kMaxRows * samplesPerLine())) endImage();
    }
}

// The APT tones are black/white square waves: count white pulses per second.
void WefaxDemod::decodeApt(int x) {
    if (x > aptHi_ && !aptHigh_) { aptHigh_ = true; ++aptTrans_; }
    else if (x < aptLo_ && aptHigh_) aptHigh_ = false;

    if (++aptCount_ < kRate / 2) return;
    crFreq_[2] = crFreq_[1]; crFreq_[1] = crFreq_[0]; crFreq_[0] = currFreq_;
    currFreq_ = kRate * aptTrans_ / aptCount_;
    aptCount_ = aptTrans_ = 0;
    const int *cr = crFreq_;

    if (state_ == AptStart) {
        if (isNear(currFreq_, aptStartFreq_, 8) && isNear(cr[0], aptStartFreq_, 8)) beginPhasing();
        return;
    }
    if (state_ == Image) {
        // A new transmission starting inside an image.
        if ((isNear(currFreq_, aptStartFreq_, 4) && isNear(cr[0], aptStartFreq_, 4) && isNear(cr[1], aptStartFreq_, 4)) ||
            (isNear(currFreq_, aptStartFreq_, 5) && isNear(cr[0], aptStartFreq_, 5) && isNear(cr[1], aptStartFreq_, 5) &&
             isNear(cr[2], aptStartFreq_, 5))) {
            beginPhasing();
            return;
        }
    }
    if ((isNear(currFreq_, aptStopFreq_, 6) && isNear(cr[0], aptStopFreq_, 6)) ||
        (isNear(currFreq_, aptStopFreq_, 7) && isNear(cr[0], aptStopFreq_, 7) && isNear(cr[1], aptStopFreq_, 7)))
        endImage();
}

void WefaxDemod::beginPhasing() {
    state_ = Phasing;
    samples_.clear();
    ++imageId_;
    invalidate();
    lpm_ = defaultLpm_;
    phaseHigh_ = currentValue_ >= 128;
    currPhaseLen_ = currPhaseHigh_ = currPhaseLow_ = 0;
    phaseLines_ = numPhaseLines_ = 0;
    lpmSum_ = 0;
    phasingCount_ = 0;
    center_ = userCenter_;  // each new picture starts from the user's setting
    afcWhite_ = afcBlack_ = 0; afcNWhite_ = afcNBlack_ = 0;
}

void WefaxDemod::beginImage(bool aligned) {
    state_ = Image;
    aligned_ = aligned;
    phase0_ = aligned ? 0.025 : 0.0;
    slantAuto_ = 0; nextRefine_ = 48;
    if (autoSlant_ && aligned && measuredLpm_ > 0 && lpm_ > 0)  // line time from the phasing lines vs the standard speed
        slantAuto_ = std::clamp((lpm_ / measuredLpm_ - 1.0) * 100.0, -1.5, 1.5);
    samples_.clear();
    if (manual_ || !aligned) ++imageId_;
    invalidate();
}

void WefaxDemod::endImage() {
    if (state_ == Image || state_ == Phasing) state_ = AptStart;
    aptHigh_ = false; aptTrans_ = aptCount_ = 0;
    std::fill(crFreq_, crFreq_ + 3, 0); currFreq_ = 0;
}

// A phasing line is a white pulse (5% of the line) on black. Four of them in a row give both the line
// length and the exact line start.
void WefaxDemod::decodePhasing(int x, double raw) {
    constexpr int kWidth = 16;
    phasingHist_[phasingCount_ % kWidth] = x;
    ++phasingCount_;
    if (phasingCount_ >= kWidth) {
        x = 0;
        for (int v : phasingHist_) x += v;
        x /= kWidth;
    }
    ++currPhaseLen_;
    if (x > 188) { ++currPhaseHigh_; afcWhite_ += raw; ++afcNWhite_; }
    else if (x < 68) { ++currPhaseLow_; afcBlack_ += raw; ++afcNBlack_; }

    if (x > 200 && !phaseHigh_) {
        phaseHigh_ = true;
    } else if (x < 25 && phaseHigh_) {
        phaseHigh_ = false;
        if (currPhaseHigh_ >= 0.04 * currPhaseLen_ && currPhaseLow_ >= 0.94 * currPhaseLen_ &&
            currPhaseLen_ >= 0.4 * kRate) {
            double lpm = 60.0 * kRate / currPhaseLen_;
            lpmSum_ += lpm;
            ++phaseLines_;
            numPhaseLines_ = 0;
            if (afcOn_ && afcNWhite_ > 20 && afcNBlack_ > 200) {
                // Black should average 0 and white 255, so their midpoint should sit at 127.5.
                double mid = (afcWhite_ / afcNWhite_ + afcBlack_ / afcNBlack_) / 2.0;
                double errHz = (mid - 127.5) * kShiftHz / 255.0;
                center_ = (float)std::clamp((double)center_ + 0.6 * errHz, userCenter_ - 75.0, userCenter_ + 75.0);
            }
            if (phaseLines_ >= 4) {
                measuredLpm_ = lpmSum_ / phaseLines_;
                // Snap to a standard speed when close, as fldigi's normalize_lpm does.
                for (int std_lpm : {240, 120, 90, 60})
                    if (std::fabs(measuredLpm_ - std_lpm) < 3.0) { lpm_ = std_lpm; break; }
                beginImage(true);
                return;
            }
        } else if (phaseLines_ > 0 && ++numPhaseLines_ >= 5) {
            beginImage(false);  // lost the phasing pattern: start anyway, unaligned
            return;
        }
        currPhaseLen_ = currPhaseHigh_ = currPhaseLow_ = 0;
        afcWhite_ = afcBlack_ = 0; afcNWhite_ = afcNBlack_ = 0;
    }
}

double WefaxDemod::samplesPerLine() const { return 60.0 * kRate / lpm_ * (1.0 + (slantPct_ + slantAuto_) / 100.0); }

void WefaxDemod::setAutoSlant(bool on, int stopLines) {
    autoStop_ = std::max(48, stopLines);
    if (on == autoSlant_) return;
    autoSlant_ = on;
    if (!on && slantAuto_ != 0) { slantAuto_ = 0; invalidate(); }
}

// Vertical features (frames, grid lines, text columns) stay in the same column when the line time is right and
// drift sideways line by line when it is not. Correlate the horizontal gradient of rows `lag` apart over every
// pair so far; the lag of the best match is the drift, and drift / (lag * width) is the line-time error.
void WefaxDemod::refineSlant(int rows) {
    constexpr int kLag = 32, kMaxShift = 24;
    rows = std::min(rows, 800);
    if (rows < kLag + 16) return;
    std::vector<uint8_t> row((size_t)width_);
    std::vector<float> g((size_t)rows * width_);
    for (int r = 0; r < rows; ++r) {
        renderRow(r, row.data());
        float *dst = &g[(size_t)r * width_];
        for (int c = 1; c + 1 < width_; ++c) dst[c] = (float)row[c + 1] - (float)row[c - 1];
        dst[0] = dst[width_ - 1] = 0;
    }
    std::vector<double> corr(2 * kMaxShift + 1, 0.0);
    for (int r = 0; r + kLag < rows; ++r) {
        const float *a = &g[(size_t)r * width_], *b = &g[(size_t)(r + kLag) * width_];
        for (int d = -kMaxShift; d <= kMaxShift; ++d) {
            double sum = 0;
            const int c0 = std::max(0, -d), c1 = std::min(width_, width_ - d);
            for (int c = c0; c < c1; ++c) sum += (double)a[c] * b[c + d];
            corr[d + kMaxShift] += sum;
        }
    }
    int best = 0;
    double mean = 0;
    for (size_t i = 0; i < corr.size(); ++i) { if (corr[i] > corr[best]) best = (int)i; mean += std::fabs(corr[i]); }
    mean /= corr.size();
    if (best == 0 || best == (int)corr.size() - 1 || corr[best] < 2.0 * mean) return;  // no clear picture structure
    const double l = corr[best - 1], c = corr[best], rr = corr[best + 1];
    const double frac = (l - rr) / (2.0 * (l - 2.0 * c + rr));
    const double shift = (best - kMaxShift) + frac;
    const double delta = std::clamp(shift / ((double)kLag * width_) * 100.0, -0.2, 0.2);
    if (std::fabs(delta) < 0.0005) return;
    slantAuto_ += delta;
    invalidate();
}

// Row r, column c covers line phase [r + c/W, r + (c+1)/W); stored sample 0 sits at phase0_ (+shift).
void WefaxDemod::renderRow(int r, uint8_t *dst) const {
    const double spl = samplesPerLine(), base = phase0_ - shift_;
    for (int c = 0; c < width_; ++c) {
        double t0 = (r + (double)c / width_ - base) * spl;
        double t1 = (r + (double)(c + 1) / width_ - base) * spl;
        long a = (long)std::floor(t0), b = std::max(a + 1, (long)std::floor(t1));
        if (b <= 0) { dst[c] = 255; continue; }
        a = std::max(a, 0L);
        b = std::min(b, (long)samples_.size());
        if (a >= b) { dst[c] = 255; continue; }
        long sum = 0;
        for (long k = a; k < b; ++k) sum += samples_[k];
        dst[c] = (uint8_t)(sum / (b - a));
    }
}

size_t WefaxDemod::poll(std::vector<uint8_t> &out) {
    double spl = samplesPerLine();
    const double base = phase0_ - shift_;
    // Row r is complete once its last column's samples exist.
    int complete = (int)std::floor((double)samples_.size() / spl + base);
    complete = std::clamp(complete, 0, kMaxRows);
    if (autoSlant_ && state_ == Image && complete >= nextRefine_) {
        const bool inRange = nextRefine_ <= autoStop_;
        nextRefine_ *= 2;
        if (inRange) {
            refineSlant(complete);
            spl = samplesPerLine();
            complete = std::clamp((int)std::floor((double)samples_.size() / spl + base), 0, kMaxRows);
        }
    }
    const int first = renderedRows_, count = std::max(0, complete - renderedRows_);
    const size_t start = out.size();
    int32_t header[6] = {generation_, width_, first, count, imageId_, (int32_t)state_};
    out.resize(start + sizeof(header) + (size_t)count * width_);
    std::memcpy(out.data() + start, header, sizeof(header));
    for (int i = 0; i < count; ++i) renderRow(first + i, out.data() + start + sizeof(header) + (size_t)i * width_);
    renderedRows_ += count;
    return out.size() - start;
}
