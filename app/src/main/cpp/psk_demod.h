#pragma once
#include <complex>
#include <cstdint>
#include <string>
#include <memory>
#include <vector>
#include "viterbi.h"

// BPSK/QPSK demodulator for PSK31/63/125.
// Pipeline: decimate to 8 kHz -> mix to baseband -> lowpass/decimate to 16 samples/symbol
// -> early/late style bit-clock pick via smoothed |y| -> differential detect -> varicode.
// Algorithm structure follows fldigi src/psk/psk.cxx (GPLv3).
class PskDemod {
public:
    enum Baud { PSK31 = 0, PSK63 = 1, PSK125 = 2 };

    PskDemod(int inputRate, Baud baud, float carrierHz, bool qpsk = false);
    void setCarrier(float hz) { carrierHz_ = hz; }
    float carrier() const { return carrierHz_; }
    // Feeds audio; decoded characters are appended to `out`.
    void process(const float *in, int n, std::string &out);
    // Relative signal quality 0..1 (sync-buffer contrast), useful for squelch/UI.
    float quality() const { return quality_; }
    // Characters are only emitted while quality() >= threshold (0 disables squelch).
    void setSquelch(float threshold) { squelch_ = threshold; }
    // Reverse: QPSK on the opposite sideband (fldigi "REV"). No effect on BPSK, which is differential.
    void setReverse(bool r) { reverse_ = r; }
    // AFC: nudges the carrier from the residual phase error of each symbol (fldigi phaseafc).
    void setAfc(bool on) { afcOn_ = on; }

private:
    static constexpr int kRate = 8000;   // internal sample rate
    static constexpr int kPerSym = 16;   // samples per symbol after decimation

    void inputSample8k(float x, std::string &out);
    void symbol(std::complex<float> y, std::string &out);
    void pushBit(int bit, std::string &out);
    void rxQpsk(std::complex<float> y, std::string &out);
    void updateAfc(std::complex<float> y);

    int inRate_, inDecim_;
    std::vector<float> preTaps_, preBuf_;
    int prePos_ = 0, preCount_ = 0;

    float carrierHz_;
    double phase_ = 0;
    int symLen_, decim_;
    std::vector<float> taps_, bufI_, bufQ_;
    int bufPos_ = 0, decimCount_ = 0;

    uint64_t tick_ = 0;
    float sync_[kPerSym] = {};
    int best_ = 0;
    std::complex<float> prev_{1.f, 0.f};
    float quality_ = 0;
    float squelch_ = 0.3f;

    bool qpsk_ = false, reverse_ = false, afcOn_ = false;
    std::complex<double> afcQuality_{0, 0};  // IIR average of exp(j*n*phase step): how tightly steps hit the constellation
    double afcMetric_ = 0;
    std::unique_ptr<Viterbi> viterbi_;  // QPSK: K=5, polys 0x17/0x19 (fldigi)

    std::string bits_;  // varicode bits since last separator
    int zeros_ = 0;
};
