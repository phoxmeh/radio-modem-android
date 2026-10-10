#pragma once
// SSTV receiver: Martin M1/M2, Scottie S1/S2, Robot 36 and PD50..PD290, with automatic mode detection from the VIS header.
//
// Frequency modulation: 1500 Hz is black, 2300 Hz white, 1200 Hz is the line sync, 1900 Hz the header leader.
// The audio is mixed to baseband around 1900 Hz and the phase advance between samples is stored; frequency at any
// moment is then the argument of a windowed sum of those (an amplitude-weighted mean, so weak stretches count less).
//
// A transmission is found by its VIS header (leader, break, leader, start bit, 7 data bits, parity, stop bit). The
// leader's frequency also gives the receiver's tuning error, applied to the whole picture. After that every line is
// aligned from its own sync pulse (its trailing edge), so clock errors and drift cannot skew the picture.
//
// Mode timings follow N7CXI's "Proposal for SSTV Mode Specifications" (2000) and were cross-checked against the
// slowrx decoder (windytan, ISC licence), whose structure this follows.
#include <cstdint>
#include <string>
#include <vector>

class SstvDemod {
public:
    static constexpr int kModeCount = 12;
    enum Mode { M1 = 0, M2, S1, S2, R36, PD50, PD90, PD120, PD160, PD180, PD240, PD290 };

    // selection: -1 = any supported mode (from the VIS header), otherwise a Mode to accept (others are reported and ignored).
    SstvDemod(int inputRate, int selection);

    // Audio at the input rate. `text` receives short status lines ("SSTV Martin M1 (VIS 44)").
    void process(const float *in, int n, std::string &text);

    // Appends new picture rows as RGB. Header (7 little-endian int32): pictureId, width, height, color(1),
    // firstRow, rowCount, state (0 waiting, 1 receiving, 2 finished). Same layout as MfskDemod::pollPicture.
    size_t pollPicture(std::vector<uint8_t> &out);
    int picturesStarted() const { return pictureId_; }
    bool receiving() const { return state_ != Idle; }
    static const char *modeName(int mode);

    // Mode description (public so the table can be built in the .cpp).
    struct Channel { int plane; double start, len; int nativeW; };
    struct Spec {
        const char *name; int vis, width, lines;
        double syncLen, lineTime, rowEnd, firstSyncEndDelay;
        bool robot; int rowsPerLine;   // PD modes send two image rows per sync pulse
        Channel ch[4]; int nch; double sepStart, sepLen;
    };

private:
    static constexpr double kRate = 22050.0;  // working rate; everything is timed in these samples
    enum State { Idle, AwaitSync, Image };
    static const Spec &spec(int mode);

    void sample(float x);
    void onSample();
    bool freqAt(long idx, int half, double &f) const;
    bool freqSpan(long lo, long hi, double &f) const;   // frequency over samples lo..hi inclusive
    void huntVis();
    void startPicture(int mode, double leaderHz, long stopCenter);
    void seekFirstSync();
    void imageStep();
    long findCrossing(long lo, long hi, int minRun) const;
    void renderLine(int line);
    void finish(const char *why);
    void trim();

    int inRate_, sel_;
    // resampler to kRate
    std::vector<float> preTaps_, preBuf_;
    int prePos_ = 0;
    double resPos_ = 0, resStep_ = 1;
    float resPrev_ = 0;
    // baseband
    std::vector<float> lp_;
    std::vector<double> bi_, bq_;
    int bufPos_ = 0;
    double nco_ = 0, prevI_ = 0, prevQ_ = 0;
    // stored phase advance per sample (index total_ - stored count .. total_ - 1)
    std::vector<float> cr_, ci_;
    long base_ = 0, total_ = 0;

    State state_ = Idle;
    // header hunting
    struct Tone { long at; double f; };
    std::vector<Tone> tones_;
    double nextTone_ = 0;
    // picture in progress
    int mode_ = 0;
    double shift_ = 0;
    long stopCenter_ = 0, scanPos_ = 0;
    int lowRun_ = 0;
    long curSync_ = 0;
    double lHat_ = 0;
    int lineIdx_ = 0, misses_ = 0, rowsRendered_ = 0;
    bool rowRendered_ = false;
    std::vector<uint8_t> rgb_;
    std::vector<float> chromaR_, chromaB_;  // Robot: latest R-Y and B-Y per pixel
    int pictureId_ = 0, picState_ = 0, rowsReported_ = 0;
    std::string *out_ = nullptr;
};
