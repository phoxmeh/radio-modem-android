#pragma once
// SSTV receiver: Martin M1/M2, Scottie S1/S2/DX, Robot 36/72 and PD50..PD290, with automatic mode detection from the VIS header.
//
// Frequency modulation: 1500 Hz is black, 2300 Hz white, 1200 Hz is the line sync, 1900 Hz the header leader.
// The audio is mixed to baseband around 1900 Hz and the phase advance between samples is stored; frequency at any
// moment is then the argument of a windowed sum of those (an amplitude-weighted mean, so weak stretches count less).
//
// A transmission is found by its VIS header (leader, break, leader, start bit, 7 data bits, parity, stop bit). The
// leader's frequency also gives the receiver's tuning error, applied to the whole picture. After that every line is
// aligned from its own sync pulse (its trailing edge), so clock errors and drift cannot skew the picture.
//
// A transmission whose header was missed is picked up from its sync pulses: four pulses of the same length at a line
// interval that matches a mode start the picture at the latest of them (the top of the picture is then missing).
//
// Mode timings follow N7CXI's "Proposal for SSTV Mode Specifications" (2000) and were cross-checked against the
// slowrx decoder (windytan, ISC licence), whose structure this follows.
#include <cstdint>
#include <string>
#include <vector>

class SstvDemod {
public:
    static constexpr int kModeCount = 14;
    enum Mode { M1 = 0, M2, S1, S2, R36, PD50, PD90, PD120, PD160, PD180, PD240, PD290, R72, SDX };

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
        bool robot;            // Robot 36: one colour difference per line, alternating
        bool yuv;              // luminance, R-Y and B-Y on every line (Robot 72)
        int rowsPerLine;       // PD modes send two image rows per sync pulse
        Channel ch[4]; int nch; double sepStart, sepLen;
    };

private:
    static constexpr double kRate = 24000.0;  // working rate (half of the usual 48 kHz); everything is timed in these samples
    enum State { Idle, AwaitSync, Image };
    static const Spec &spec(int mode);

    void sample(float x);
    void onSample();
    bool freqAt(long idx, int half, double &f) const;
    bool freqSpan(long lo, long hi, double &f) const;   // frequency over samples lo..hi inclusive (mean phase advance)
    // Frequency of the strongest tone between fLo and fHi in a Hann-windowed span centred on `center`: a spectral
    // peak, which stays accurate at signal-to-noise ratios where the mean phase advance is pulled toward the centre.
    bool peakFreq(long center, int half, double fLo, double fHi, double step, double &f) const;
    // Power at one frequency (Hz) in the same kind of window.
    double tonePower(long center, int half, double hz) const;
    void huntVis();
    void huntSync();
    void tryJoin();
    void joinPicture(int mode, long lastSyncEnd, double meanInterval, double shift);
    void startPicture(int mode, double leaderHz, long stopCenter);
    void seekFirstSync();
    void imageStep();
    // How much of the signal around `c` is the 1200 Hz sync tone rather than picture tones (0..1; above 0.5 = sync).
    double syncMetric(long c, int half, bool fast = false) const;   // fast: fewer tones, for the idle search
    int syncHalf(int mode) const;   // window half-length for syncMetric, from the mode's pulse length
    long findCrossing(long lo, long hi, int minRun, long expect, int syncSamples) const;
    double lineNoise() const;   // scatter (Hz) of the frequency estimates inside the line's own sync pulse
    double noiseHz_ = -1;       // smoothed over lines; < 0 until the first line
    void renderLine(int line);
    void finish(const char *why);
    void trim();

    int inRate_, sel_;
    // resampler to kRate
    std::vector<float> preTaps_, preBuf_;   // anti-alias filter; preBuf_ holds the history twice so a window is contiguous
    int prePos_ = 0, preCount_ = 0;
    double resPos_ = 0, resStep_ = 1;
    float resPrev_ = 0;
    // baseband
    std::vector<float> lp_;
    std::vector<float> bi_, bq_;   // history twice over, see preBuf_
    int bufPos_ = 0;
    double nr_ = 1, ni_ = 0;        // the mixing oscillator, advanced by a rotation each sample
    double prevI_ = 0, prevQ_ = 0;
    // stored phase advance per sample (index total_ - stored count .. total_ - 1)
    std::vector<float> cr_, ci_;    // conj(previous) * current baseband sample
    std::vector<float> zr_, zi_;    // the baseband samples themselves
    long base_ = 0, total_ = 0;

    State state_ = Idle;
    // header hunting
    struct Tone { long at; double f; };
    std::vector<Tone> tones_;
    double nextTone_ = 0;
    // header-less starts: recent sync pulses (end sample, length in samples) and the current low run
    struct Pulse { long end; int len; };
    std::vector<Pulse> pulses_;
    int sLow_ = 0, sGap_ = 0;
    long sNext_ = 0;   // next sample at which huntSync() looks
    // picture in progress
    int mode_ = 0;
    double shift_ = 0;
    long stopCenter_ = 0, scanPos_ = 0;
    int lowRun_ = 0, gap_ = 0;
    long curSync_ = 0;
    double lHat_ = 0;
    int lineIdx_ = 0, misses_ = 0, rowsRendered_ = 0;
    int exposed_ = 0;   // rows shown to the caller: those drawn from a sync pulse that was actually heard, or confirmed by a later one
    bool rowRendered_ = false;
    std::vector<uint8_t> rgb_;
    std::vector<float> chromaR_, chromaB_;  // Robot: latest R-Y and B-Y per pixel
    int pictureId_ = 0, picState_ = 0, rowsReported_ = 0;
    std::string *out_ = nullptr;
};
