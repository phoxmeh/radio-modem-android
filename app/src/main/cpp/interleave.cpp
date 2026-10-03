#include "interleave.h"
#include <cstring>

Interleave::Interleave(int size, int depth, Direction dir)
    : size_(size), depth_(depth), dir_(dir), table_(size * size * depth) { flush(); }

void Interleave::flush() {
    std::memset(table_.data(), dir_ == Rev ? kPuncture : 0, table_.size());
}

void Interleave::symbols(unsigned char *psyms) {
    for (int k = 0; k < depth_; ++k) {
        for (int i = 0; i < size_; ++i)
            for (int j = 0; j < size_ - 1; ++j) *tab(k, i, j) = *tab(k, i, j + 1);
        for (int i = 0; i < size_; ++i) *tab(k, i, size_ - 1) = psyms[i];
        for (int i = 0; i < size_; ++i)
            psyms[i] = dir_ == Fwd ? *tab(k, i, size_ - i - 1) : *tab(k, i, i);
    }
}

void Interleave::bits(unsigned int *pbits) {
    unsigned char syms[16];
    for (int i = 0; i < size_; ++i) syms[i] = (*pbits >> (size_ - i - 1)) & 1;
    symbols(syms);
    *pbits = 0;
    for (int i = 0; i < size_; ++i) *pbits = (*pbits << 1) | syms[i];
}
