#pragma once
// HF weather-fax (WEFAX) receiver, following fldigi src/wefax/wefax.cxx (GPLv3): FM demodulation of the
// 1500 Hz (black) .. 2300 Hz (white) subcarrier, APT start/stop tone detection by counting black/white
// transitions, phasing-line alignment, then pixel extraction.
//
// Instead of writing pixels as they arrive, the demodulated 11.025 kHz sample stream of the current image is
// kept, and rows are (re)rendered from it. That makes slant (line-length error), horizontal shift and LPM
// corrections exact and reversible after the fact.
#include <cstdint>
#include <vector>

class WefaxDemod {
public:
    enum State { AptStart = 0, Phasing = 1, Image = 2 };  // AptStart also means "waiting / finished"

    // ioc: 576 or 288. centerHz: carrier of the fax subcarrier (1900 Hz in USB with dial = assigned - 1.9 kHz).
    WefaxDemod(int inputRate, int ioc, float centerHz = 1900.f);

    // Center of the fax subcarrier (1900 Hz). The AFC refines it from the phasing lines.
    void setCenter(float hz) { center_ = userCenter_ = hz; }
    float center() const { return center_; }
    // AFC: while receiving the phasing lines, black and white should read 0 and 255; their offset is the
    // centre error. (fldigi measures the same thing from the waterfall's power spectrum.)
    void setAfc(bool on) { afcOn_ = on; }
    void setFilter(int idx) { filter_ = idx < 0 ? 0 : idx > 2 ? 2 : idx; }
    // Manual: skip APT and phasing, start the image right away (for pictures already in progress).
    void setManual(bool manual);
    void setLpm(double lpm) { setLpmInternal(lpm); }
    void setSlantPercent(double pct);        // + lengthens the assumed line time
    void setShift(double fractionOfWidth);   // horizontal offset, -0.5..0.5
    void restart();                          // back to waiting for APT start; discards the image

    void process(const float *in, int n);

    int width() const { return width_; }
    State state() const { return state_; }
    int imageId() const { return imageId_; }
    double lpm() const { return lpm_; }
    double measuredLpm() const { return measuredLpm_; }
    bool manual() const { return manual_; }

    // Appends newly rendered rows. Header (6 little-endian int32): generation, width, firstRow, rowCount,
    // imageId, state. A changed generation means everything was re-rendered: the client should restart at
    // firstRow == 0. Returns the number of bytes appended.
    size_t poll(std::vector<uint8_t> &out);

private:
    static constexpr int kRate = 11025;  // fldigi's WEFAX rate; its filter taps are designed for it
    static constexpr int kMaxRows = 2800;

    void sampleRate(float x);
    void step(int x, double raw);
    void decodeApt(int x);
    void decodePhasing(int x, double raw);
    void beginPhasing();
    void beginImage(bool aligned);
    void endImage();
    void setLpmInternal(double lpm);
    void invalidate() { ++generation_; renderedRows_ = 0; }
    double samplesPerLine() const;
    void renderRow(int r, uint8_t *dst) const;

    // front end
    int inRate_;
    // Resampler to kRate: low-pass at the input rate, then linear interpolation between filtered samples.
    std::vector<float> preTaps_, preBuf_;
    int prePos_ = 0;
    double resPos_ = 0, resStep_ = 1;  // next output time (in input samples) and step per output
    float resPrev_ = 0;
    float center_, userCenter_;
    bool afcOn_ = false;
    double afcWhite_ = 0, afcBlack_ = 0;
    int afcNWhite_ = 0, afcNBlack_ = 0;
    int filter_ = 1;
    double nco_ = 0;
    std::vector<double> bufI_, bufQ_;
    int bufPos_ = 0;
    double prevI_ = 0, prevQ_ = 0;
    double deviationRatio_;

    // geometry / modes
    int ioc_, width_, aptStartFreq_, aptStopFreq_ = 450, defaultLpm_;
    int aptHi_ = 215, aptLo_ = 40;  // black/white thresholds for counting APT pulses

    // state machine
    State state_ = AptStart;
    bool manual_ = false;
    int imageId_ = 0;
    // APT
    bool aptHigh_ = false;
    int aptTrans_ = 0, aptCount_ = 0;
    int crFreq_[3] = {0, 0, 0}, currFreq_ = 0;
    // phasing
    bool phaseHigh_ = false;
    int currPhaseLen_ = 0, currPhaseHigh_ = 0, currPhaseLow_ = 0;
    int phaseLines_ = 0, numPhaseLines_ = 0;
    double lpmSum_ = 0, measuredLpm_ = 0;
    int phasingHist_[16] = {};
    int phasingCount_ = 0;
    int currentValue_ = 0;

    // image
    double lpm_;
    double slantPct_ = 0, shift_ = 0;
    double phase0_ = 0.025;  // line phase of stored sample 0 (phasing pulse falling edge is at +2.5%)
    bool aligned_ = true;
    std::vector<uint8_t> samples_;
    int generation_ = 0, renderedRows_ = 0;
};
