#pragma once
// MFSK receiver (MFSK4/8/16/31/32/64/128, no image mode), following fldigi src/mfsk/mfsk.cxx (GPLv3).
// Chain: 8 kHz analytic signal -> mix tone block to base -> band-pass -> sliding DFT over the tone bins ->
// per-symbol hard/soft decisions -> diagonal de-interleaver -> K=7 Viterbi (two decoders vote for odd
// bits/symbol) -> IZ8BLY varicode.
#include <array>
#include <complex>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
#include "interleave.h"
#include "viterbi.h"

class MfskDemod {
public:
    enum Mode { MFSK4, MFSK8, MFSK16, MFSK31, MFSK32, MFSK64, MFSK128 };
    static constexpr int kMaxTones = 32;
    // A bogus header in noise must not allocate a huge buffer; real MFSK pictures are far smaller.
    static constexpr long kMaxPicturePixels = 2000000;

    // centerHz: audio frequency of the middle of the tone block.
    MfskDemod(int inputRate, Mode mode, float centerHz);
    ~MfskDemod();
    void setCarrier(float hz) { frequency_ = hz; }
    float carrier() const { return (float)frequency_; }
    void setReverse(bool r) { reverse_ = r; }
    void setAfc(bool on) { afcOn_ = on; }
    void setSquelch(float t) { squelch_ = t; }  // 0..1 of viterbi metric scale
    float quality() const { return (float)(metric_ / 100.0); }
    // Total occupied bandwidth in Hz (first tone to last tone).
    float bandwidth() const { return (float)bandwidth_; }
    void process(const float *in, int n, std::string &out);

    // ---- pictures (MFSK16/31/32/64/128) -----------------------------------------------------------
    // A transmission announces a picture in the text stream ("Pic:320x240C;"), then switches to FM
    // pixel data: 1 byte per RXspp samples, frequency across the tone block = 0..255.
    // Poll appends a header of 7 little-endian int32 {pictureId, width, height, color, firstRow,
    // rowCount, state (0 none, 1 receiving, 2 complete)} followed by rowCount rows of RGB.
    size_t pollPicture(std::vector<uint8_t> &out);
    int picturesStarted() const { return pictureId_; }

private:
    using cmplx = std::complex<double>;
    struct Fir {  // fldigi C_FIR_filter, no decimation: taps[0] multiplies the oldest sample
        std::vector<double> ti, tq, bi, bq;
        int pos = 0;
        void init(std::vector<double> i, std::vector<double> q);
        cmplx run(cmplx in);
    };
    struct Sfft {  // fldigi sfft: sliding DFT of bins [first, last)
        int len, first, last, ptr = 0;
        double k2;
        std::vector<cmplx> vrot, bins, delay;
        Sfft(int len, int first, int last);
        void run(cmplx in, cmplx *result);
    };

    void sample(double x);
    cmplx mixer(cmplx in, double f);
    int harddecode(const cmplx *in);
    void softdecode(const cmplx *bins);
    void decodesymbol(unsigned char symbol);
    void recvbit(int bit);
    void synchronize();
    void afc();
    void recvchar(int c);
    bool checkPictureHeader(char c);
    void recvpic(cmplx z);
    void rxInit();

    // geometry
    int samplerate_ = 8000, symlen_, symbits_, depth_, basetone_, numtones_;
    double tonespacing_, basefreq_, bandwidth_;

    // front end
    int inDecim_;
    std::vector<float> preTaps_, preBuf_;
    int prePos_ = 0, preCount_ = 0;
    Fir hilbert_, bandpass_;
    std::unique_ptr<Sfft> sfft_;
    double frequency_, phaseacc_ = 0;

    // symbol pipeline
    std::vector<std::array<cmplx, kMaxTones>> pipe_;
    int pipeptr_ = 0, synccounter_ = 0;
    int currsymbol_ = 0, prev1symbol_ = 0, prev2symbol_ = 0;
    cmplx currvector_, prev1vector_, prev2vector_;
    bool staticburst_ = false;
    double afcmetric_ = 0, freqerr_ = 0;
    std::vector<double> syncAvg_;  // Cmovavg(8)
    int syncPos_ = 0;
    bool syncEmpty_ = true;
    double syncSum_ = 0;
    int cwiCounter_[kMaxTones] = {};

    // decoder
    std::unique_ptr<Interleave> rxinlv_;
    std::unique_ptr<Viterbi> dec1_, dec2_;
    unsigned char symbolpair_[2] = {0, 0};
    int symcounter_ = 0;
    double met1_ = 0, met2_ = 0, metric_ = 0;
    unsigned datashreg_ = 1;

    // pictures
    enum RxState { Data, PictureStart, Picture };
    Mode mode_;
    RxState rxstate_ = Data;
    int counter_ = 0, symbolbit_ = 0;
    char picheader_[64];
    int picW_ = 0, picH_ = 0, rxspp_ = 8, picturesize_ = 0;  // picW_/picH_/color_ are scratch for header parsing
    bool color_ = false;
    int curW_ = 0, curH_ = 0;      // the picture being received (picW_ etc. are clobbered by later characters)
    bool curColor_ = false;
    int pixelnbr_ = 0, col_ = 0, row_ = 0, rgb_ = 0;
    double picf_ = 0;
    cmplx prevz_{0, 0};
    int pictureId_ = 0, picState_ = 0, picRowsReported_ = 0;
    std::vector<uint8_t> picRgb_;

    bool reverse_ = false, afcOn_ = false;
    float squelch_ = 0.3f;
    std::string *out_ = nullptr;
};
