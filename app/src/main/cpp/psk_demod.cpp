#include "psk_demod.h"
#include "psk_varicode.h"
#include <cmath>
#include <map>
#include <unordered_map>

namespace {
const std::unordered_map<std::string, int> &decodeTable() {
    static const std::unordered_map<std::string, int> t = [] {
        std::unordered_map<std::string, int> m;
        for (int i = 0; i < 256; ++i) m[kPskVaricode[i]] = i;
        return m;
    }();
    return t;
}

// Hann-windowed sinc lowpass, unity DC gain.
std::vector<float> lowpass(int len, double fc, double fs) {
    std::vector<float> h(len);
    double sum = 0;
    for (int i = 0; i < len; ++i) {
        double m = i - (len - 1) / 2.0;
        double s = (m == 0) ? 2 * fc / fs : std::sin(2 * M_PI * fc * m / fs) / (M_PI * m);
        double w = 0.5 - 0.5 * std::cos(2 * M_PI * i / (len - 1));
        h[i] = (float)(s * w);
        sum += h[i];
    }
    for (auto &v : h) v = (float)(v / sum);
    return h;
}
}  // namespace

PskDemod::PskDemod(int inputRate, Baud baud, float carrierHz, bool qpsk)
    : inRate_(inputRate), carrierHz_(carrierHz), qpsk_(qpsk) {
    if (qpsk_) viterbi_ = std::make_unique<Viterbi>(5, 0x17, 0x19);
    inDecim_ = inRate_ / kRate;  // inputRate must be a multiple of 8000
    if (inDecim_ > 1) {
        int len = inDecim_ * 8 + 1;
        preTaps_ = lowpass(len, 3400.0, inRate_);
        preBuf_.assign(len, 0.f);
    }
    const double bauds[] = {31.25, 62.5, 125.0};
    symLen_ = (int)std::lround(kRate / bauds[baud]);
    decim_ = symLen_ / kPerSym;
    int len = symLen_ * 3 / 2 + 1;
    taps_ = lowpass(len, bauds[baud] * 1.3, kRate);
    bufI_.assign(len, 0.f);
    bufQ_.assign(len, 0.f);
}

void PskDemod::process(const float *in, int n, std::string &out) {
    if (inDecim_ <= 1) {
        for (int i = 0; i < n; ++i) inputSample8k(in[i], out);
        return;
    }
    const int len = (int)preTaps_.size();
    for (int i = 0; i < n; ++i) {
        preBuf_[prePos_] = in[i];
        prePos_ = (prePos_ + 1) % len;
        if (++preCount_ < inDecim_) continue;
        preCount_ = 0;
        float acc = 0;
        for (int k = 0, p = prePos_; k < len; ++k, p = (p + 1) % len) acc += preTaps_[k] * preBuf_[p];
        inputSample8k(acc, out);
    }
}

void PskDemod::inputSample8k(float x, std::string &out) {
    phase_ += 2 * M_PI * carrierHz_ / kRate;
    if (phase_ > 2 * M_PI) phase_ -= 2 * M_PI;
    const int len = (int)taps_.size();
    bufI_[bufPos_] = x * (float)std::cos(phase_);
    bufQ_[bufPos_] = -x * (float)std::sin(phase_);
    bufPos_ = (bufPos_ + 1) % len;
    if (++decimCount_ < decim_) return;
    decimCount_ = 0;

    float i = 0, q = 0;
    for (int k = 0, p = bufPos_; k < len; ++k, p = (p + 1) % len) {
        i += taps_[k] * bufI_[p];
        q += taps_[k] * bufQ_[p];
    }
    std::complex<float> y(i, q);

    const int ph = (int)(tick_++ % kPerSym);
    // Running average that converges fast at start (1/n), then settles to a 50-symbol memory.
    const float a = std::max(0.02f, 1.f / (float)(tick_ / kPerSym + 1));
    sync_[ph] += a * (std::abs(y) - sync_[ph]);
    if (ph == best_) {
        symbol(y, out);
        // Re-pick the symbol sampling phase, with hysteresis against dithering.
        int arg = 0;
        float mx = 0, mn = 1e30f;
        for (int p = 0; p < kPerSym; ++p) {
            if (sync_[p] > mx) { mx = sync_[p]; arg = p; }
            if (sync_[p] < mn) mn = sync_[p];
        }
        if (arg != best_ && sync_[arg] > 1.1f * sync_[best_]) best_ = arg;
        quality_ = (tick_ > 16 * 20 && mx > 1e-9f) ? (mx - mn) / mx : 0.f;  // warm-up: sync_ starts empty
    }
}

void PskDemod::symbol(std::complex<float> y, std::string &out) {
    updateAfc(y);  // uses prev_, so must run before it is replaced
    if (qpsk_) { rxQpsk(y, out); return; }
    float dot = (y * std::conj(prev_)).real();
    prev_ = y;
    pushBit(dot > 0 ? 1 : 0, out);
}

void PskDemod::pushBit(int bit, std::string &out) {
    // A single 0 is held back: "0" followed by "1" is part of a code, "00" ends the character.
    if (bit == 0) {
        if (++zeros_ == 2 && !bits_.empty()) {
            auto it = decodeTable().find(bits_);
            if (it != decodeTable().end() && quality_ >= squelch_) out.push_back((char)it->second);
            bits_.clear();
        }
        if (zeros_ > 2) zeros_ = 2;
    } else {
        if (zeros_ == 1) bits_.push_back('0');
        zeros_ = 0;
        bits_.push_back('1');
        if (bits_.size() > 12) bits_.clear();  // no valid code is this long
    }
}

// Mirrors fldigi psk::rx_symbol + rx_qpsk. fldigi mixes with exp(+j*w*t) while we use exp(-j*w*t), so its
// baseband is the conjugate of ours and its phase step is the negative of our y * conj(prev) angle.
void PskDemod::rxQpsk(std::complex<float> y, std::string &out) {
    float phase = -std::arg(y * std::conj(prev_));
    prev_ = y;
    if (phase < 0) phase += 2.f * (float)M_PI;
    int bits = (int)(phase / (float)M_PI_2 + 0.5f) & 3;
    if (!reverse_) bits = (4 - bits) & 3;  // fldigi "!reverse" (normal sideband)
    unsigned char sym[2];
    sym[0] = (bits & 1) ? 255 : 0;
    sym[1] = (bits & 2) ? 0 : 255;  // top bit is flipped
    int c = viterbi_->decode(sym);
    if (c != -1)
        for (int mask = 0x80; mask; mask >>= 1) pushBit((c & mask) ? 1 : 0, out);
}

// fldigi psk::phaseafc. The residual of each symbol's phase step from the nearest constellation point is a
// frequency error: error * fs / (2*pi*symbollen) Hz, applied with gain 1/dcdbits. Gated by how consistently
// the steps hit the constellation (afcmetric) and by the squelch (fldigi's DCD).
void PskDemod::updateAfc(std::complex<float> y) {
    const int n = qpsk_ ? 4 : 2;
    const double step = std::arg(y * std::conj(prev_));
    const double decay = 50.0;  // fldigi SQLDECAY
    afcQuality_ += std::complex<double>(std::cos(n * step) - afcQuality_.real(), std::sin(n * step) - afcQuality_.imag()) / decay;
    afcMetric_ += (std::norm(afcQuality_) - afcMetric_) / 50.0;

    if (!afcOn_ || afcMetric_ < 0.05 || quality_ < squelch_) return;
    const double quantum = 2.0 * M_PI / n;
    double error = step - std::round(step / quantum) * quantum;
    if (error < -M_PI / 2 || error > M_PI / 2) return;
    const double freqErr = error * kRate / (2.0 * M_PI * symLen_);
    const double scBw = (double)kRate / symLen_;
    const double dcdbits = 8192.0 / symLen_;  // 32 / 64 / 128 for 31 / 63 / 125 baud
    if (std::fabs(freqErr) < scBw) carrierHz_ += (float)(freqErr / dcdbits);
}
