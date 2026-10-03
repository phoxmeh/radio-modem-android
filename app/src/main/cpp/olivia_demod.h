#pragma once
// Olivia receiver: a thin wrapper over Pawel Jalocha's MFSK_Receiver, configured the way fldigi's
// src/olivia/olivia.cxx does (8 kHz internal rate, first carrier = center - fc_offset, 500 Hz multiplier).
#include <memory>
#include <string>
#include <vector>

class OliviaDemod {
public:
    // toneExp: 1..5 -> 4,8,16,32,64 tones.  bwExp: 0..4 -> 125,250,500,1000,2000 Hz.
    OliviaDemod(int inputRate, int toneExp, int bwExp, float centerHz);
    ~OliviaDemod();
    void setCarrier(float hz);
    void setReverse(bool r);
    void setSquelch(float t);  // 0 disables; fldigi maps slider/5 + 3 to the sync S/N threshold
    float carrier() const { return center_; }  // Olivia has no AFC; its sync search handles small offsets
    float quality() const { return quality_; }
    float bandwidth() const { return bandwidthHz_; }
    void process(const float *in, int n, std::string &out);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
    int inDecim_;
    std::vector<float> preTaps_, preBuf_;
    int prePos_ = 0, preCount_ = 0;
    float bandwidthHz_, quality_ = 0, squelch_ = 0.3f, center_;
    bool reverse_ = false;
};
