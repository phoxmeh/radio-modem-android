#include "olivia_demod.h"
#include "jalocha/pj_mfsk.h"
#include <algorithm>
#include <cmath>

struct OliviaDemod::Impl {
    MFSK_Receiver<double> rx;
    double fcOffset = 0;
    std::vector<double> buf;
};

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
}  // namespace

OliviaDemod::OliviaDemod(int inputRate, int toneExp, int bwExp, float centerHz)
    : impl_(new Impl), inDecim_(std::max(1, inputRate / 8000)), center_(centerHz) {
    if (inDecim_ > 1) {
        preTaps_ = lowpass(inDecim_ * 8 + 1, 3400.0, inputRate);
        preBuf_.assign(preTaps_.size(), 0.f);
    }
    auto &rx = impl_->rx;
    const int bandwidth = 125 * (1 << bwExp);
    rx.Tones = 2 * (1 << toneExp);
    rx.Bandwidth = bandwidth;
    rx.SyncMargin = 8;     // fldigi defaults
    rx.SyncIntegLen = 4;
    rx.SampleRate = 8000;
    rx.InputSampleRate = 8000;
    bandwidthHz_ = (float)(bandwidth - bandwidth / (double)rx.Tones);
    impl_->fcOffset = bandwidth * (1.0 - 0.5 / rx.Tones) / 2.0;
    setSquelch(squelch_);
    setCarrier(centerHz);
}

OliviaDemod::~OliviaDemod() = default;

void OliviaDemod::setSquelch(float t) {
    squelch_ = t;
    impl_->rx.SyncThreshold = t > 0 ? std::clamp(t * 100.0 / 5.0 + 3.0, 0.0, 90.0) : 0.0;
}

void OliviaDemod::setReverse(bool r) { reverse_ = r; setCarrier(center_); }

void OliviaDemod::setCarrier(float hz) {
    center_ = hz;
    auto &rx = impl_->rx;
    rx.Reverse = reverse_ ? 1 : 0;
    rx.FirstCarrierMultiplier = (float)((reverse_ ? hz + impl_->fcOffset : hz - impl_->fcOffset) / 500.0);
    rx.Preset();
}

void OliviaDemod::process(const float *in, int n, std::string &out) {
    auto &rx = impl_->rx;
    auto &buf = impl_->buf;
    buf.clear();
    if (inDecim_ <= 1) {
        buf.assign(in, in + n);
    } else {
        const int len = (int)preTaps_.size();
        for (int i = 0; i < n; ++i) {
            preBuf_[prePos_] = in[i];
            prePos_ = (prePos_ + 1) % len;
            if (++preCount_ < inDecim_) continue;
            preCount_ = 0;
            float acc = 0;
            for (int k = 0, p = prePos_; k < len; ++k, p = (p + 1) % len) acc += preTaps_[k] * preBuf_[p];
            buf.push_back(acc);
        }
    }
    if (buf.empty()) return;
    rx.Process(buf.data(), (int)buf.size());
    uint8_t ch;
    while (rx.GetChar(ch) > 0)
        if (ch > 7) out.push_back((char)ch);
    quality_ = (float)(std::clamp(5.0 * (rx.SignalToNoiseRatio() - 3.0), 0.0, 100.0) / 100.0);
}
