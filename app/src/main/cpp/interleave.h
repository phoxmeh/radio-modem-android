#pragma once
// MFSK diagonal interleaver, ported from fldigi src/mfsk/interleave.cxx (GPLv3, W1HKJ / gmfsk).
#include <vector>

class Interleave {
public:
    enum Direction { Fwd = 0, Rev = 1 };
    static constexpr unsigned char kPuncture = 128;  // "no information" soft symbol

    Interleave(int size, int depth, Direction dir);
    void flush();
    // Operates on `size` soft symbols in place (0..255).
    void symbols(unsigned char *psyms);
    // Operates on `size` hard bits packed in an unsigned int (MSB first).
    void bits(unsigned int *pbits);
private:
    unsigned char *tab(int i, int j, int k) { return &table_[size_ * size_ * i + size_ * j + k]; }
    int size_, depth_;
    Direction dir_;
    std::vector<unsigned char> table_;
};
