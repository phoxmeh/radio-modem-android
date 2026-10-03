#pragma once
// Convolutional encoder and Viterbi decoder, ported from fldigi src/filters/viterbi.cxx and
// src/include/viterbi.h (GPLv3, Dave Freese W1HKJ / Phil Karn KA9Q, via gmfsk). Behaviour is kept
// identical so QPSK31/63/125 interoperates with fldigi: decode() returns a chunk of `chunksize` bits
// (MSB = oldest) every `chunksize` symbols, otherwise -1.
#include <vector>

class ConvEncoder {
public:
    ConvEncoder(int k, int poly1, int poly2);
    void init();
    // Returns the 2-bit output symbol for one input bit.
    int encode(int bit);
private:
    int k_, poly1_, poly2_;
    std::vector<int> output_;
    unsigned shreg_ = 0, mask_ = 0;
};

class Viterbi {
public:
    static constexpr int kPathMem = 256;
    Viterbi(int k, int poly1, int poly2);
    void reset();
    // Traceback depth and bits returned per decode call (fldigi MFSK uses 45 and 1).
    bool setTraceback(int trace) { if (trace < 0 || trace > kPathMem - 1) return false; traceback_ = trace; return true; }
    bool setChunkSize(int chunk) { if (chunk < 1 || chunk > traceback_) return false; chunksize_ = chunk; return true; }
    // sym[0], sym[1]: soft symbols 0..255 (255 = strong "1"). Returns decoded chunk or -1.
    int decode(const unsigned char sym[2], int *metric = nullptr);
private:
    int traceback(int *metric);
    int k_, poly1_, poly2_, nstates_, outsize_;
    int traceback_, chunksize_ = 8;
    std::vector<int> output_;
    std::vector<std::vector<int>> metrics_, history_;
    std::vector<int> sequence_;
    int mettab_[2][256];
    unsigned ptr_ = 0;
};
