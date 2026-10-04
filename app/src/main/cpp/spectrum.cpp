#include "spectrum.h"
#include <cmath>
#include <algorithm>

Spectrum::Spectrum(int window, int fft) : win_(window), n_(fft), window_(window), buf_(fft), twiddle_(fft / 2), rev_(fft) {
    for (int i = 0; i < win_; ++i)
        window_[i] = 0.5f - 0.5f * std::cos(2.f * (float)M_PI * i / (win_ - 1));
    for (int k = 0; k < n_ / 2; ++k) {
        double a = -2.0 * M_PI * k / n_;
        twiddle_[k] = {(float)std::cos(a), (float)std::sin(a)};
    }
    int bits = 0;
    while ((1 << bits) < n_) ++bits;
    for (int i = 0; i < n_; ++i) {
        int r = 0;
        for (int b = 0; b < bits; ++b) if (i & (1 << b)) r |= 1 << (bits - 1 - b);
        rev_[i] = r;
    }
}

// In-place radix-2 FFT with a precomputed twiddle table (no accumulated phase error on long transforms).
void Spectrum::fft() {
    for (int i = 0; i < n_; ++i) if (i < rev_[i]) std::swap(buf_[i], buf_[rev_[i]]);
    for (int len = 2; len <= n_; len <<= 1) {
        const int half = len / 2, step = n_ / len;
        for (int i = 0; i < n_; i += len)
            for (int k = 0; k < half; ++k) {
                auto u = buf_[i + k], v = buf_[i + k + half] * twiddle_[k * step];
                buf_[i + k] = u + v;
                buf_[i + k + half] = u - v;
            }
    }
}

void Spectrum::compute(const float *samples, float *outDb, int count) {
    for (int i = 0; i < win_; ++i) buf_[i] = {samples[i] * window_[i], 0.f};
    for (int i = win_; i < n_; ++i) buf_[i] = {0.f, 0.f};
    fft();
    const float norm = 2.f / win_;
    count = std::min(count, n_ / 2);
    for (int i = 0; i < count; ++i)
        outDb[i] = 20.f * std::log10(std::abs(buf_[i]) * norm + 1e-9f);
}
