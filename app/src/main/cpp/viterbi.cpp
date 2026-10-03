#include "viterbi.h"
#include <climits>
#include <cstring>

static int parity(unsigned long w) { return __builtin_parityl(w); }

ConvEncoder::ConvEncoder(int k, int poly1, int poly2) : k_(k), poly1_(poly1), poly2_(poly2) { init(); }

void ConvEncoder::init() {
    int size = 1 << k_;
    output_.assign(size, 0);
    for (int i = 0; i < size; ++i) output_[i] = parity(poly1_ & i) | (parity(poly2_ & i) << 1);
    shreg_ = 0;
    mask_ = size - 1;
}

int ConvEncoder::encode(int bit) {
    shreg_ = (shreg_ << 1) | !!bit;
    return output_[shreg_ & mask_];
}

Viterbi::Viterbi(int k, int poly1, int poly2)
    : k_(k), poly1_(poly1), poly2_(poly2), nstates_(1 << (k - 1)), outsize_(1 << k),
      traceback_(k * 12),  // >= 12 constraint lengths to converge from an arbitrary state
      output_(outsize_), metrics_(kPathMem, std::vector<int>(nstates_)),
      history_(kPathMem, std::vector<int>(nstates_)), sequence_(kPathMem, 0) {
    for (int i = 0; i < outsize_; ++i) output_[i] = parity(poly1_ & i) | (parity(poly2_ & i) << 1);
    for (int i = 0; i < 256; ++i) { mettab_[0][i] = 128 - i; mettab_[1][i] = i - 128; }
    reset();
}

void Viterbi::reset() {
    for (int i = 0; i < kPathMem; ++i) {
        std::fill(metrics_[i].begin(), metrics_[i].end(), 0);
        std::fill(history_[i].begin(), history_[i].end(), 0);
    }
    ptr_ = 0;
}

int Viterbi::traceback(int *metric) {
    unsigned p = (ptr_ - 1) % kPathMem, c = 0;
    int best = INT_MIN, bestState = 0;
    for (int i = 0; i < nstates_; ++i)
        if (metrics_[p][i] > best) { best = metrics_[p][i]; bestState = i; }
    sequence_[p] = bestState;
    for (int i = 0; i < traceback_; ++i) {
        unsigned prev = (p - 1) % kPathMem;
        sequence_[prev] = history_[p][sequence_[p]];
        p = prev;
    }
    if (metric) *metric = metrics_[p][sequence_[p]];
    for (int i = 0; i < chunksize_; ++i) {
        c = (c << 1) | (sequence_[p] & 1);
        p = (p + 1) % kPathMem;
    }
    if (metric) *metric = metrics_[p][sequence_[p]] - *metric;
    return (int)c;
}

int Viterbi::decode(const unsigned char sym[2], int *metric) {
    unsigned curr = ptr_, prev = (curr - 1) % kPathMem;
    int met[4];
    met[0] = mettab_[0][sym[1]] + mettab_[0][sym[0]];
    met[1] = mettab_[0][sym[1]] + mettab_[1][sym[0]];
    met[2] = mettab_[1][sym[1]] + mettab_[0][sym[0]];
    met[3] = mettab_[1][sym[1]] + mettab_[1][sym[0]];

    for (int n = 0; n < nstates_; ++n) {
        int s0 = n, s1 = n + nstates_, p0 = s0 >> 1, p1 = s1 >> 1;
        int m0 = metrics_[prev][p0] + met[output_[s0]];
        int m1 = metrics_[prev][p1] + met[output_[s1]];
        if (m0 > m1) { metrics_[curr][n] = m0; history_[curr][n] = p0; }
        else         { metrics_[curr][n] = m1; history_[curr][n] = p1; }
    }
    ptr_ = (ptr_ + 1) % kPathMem;
    if ((ptr_ % chunksize_) == 0) return traceback(metric);

    // Renormalize before the metrics overflow.
    if (metrics_[curr][0] > INT_MAX / 2)
        for (auto &row : metrics_) for (auto &v : row) v -= INT_MAX / 2;
    if (metrics_[curr][0] < INT_MIN / 2)
        for (auto &row : metrics_) for (auto &v : row) v += INT_MIN / 2;
    return -1;
}
