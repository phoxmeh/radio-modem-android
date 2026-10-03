#include "spectrum.h"
#include <cmath>

Spectrum::Spectrum(int n) : n_(n), window_(n), buf_(n) {
    for (int i = 0; i < n; ++i)
        window_[i] = 0.5f - 0.5f * std::cos(2.f * (float)M_PI * i / (n - 1));
}

// In-place iterative radix-2 FFT.
void Spectrum::fft() {
    for (int i = 1, j = 0; i < n_; ++i) {
        int bit = n_ >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(buf_[i], buf_[j]);
    }
    for (int len = 2; len <= n_; len <<= 1) {
        float ang = -2.f * (float)M_PI / len;
        std::complex<float> wl(std::cos(ang), std::sin(ang));
        for (int i = 0; i < n_; i += len) {
            std::complex<float> w(1.f, 0.f);
            for (int k = 0; k < len / 2; ++k) {
                auto u = buf_[i + k], v = buf_[i + k + len / 2] * w;
                buf_[i + k] = u + v;
                buf_[i + k + len / 2] = u - v;
                w *= wl;
            }
        }
    }
}

void Spectrum::compute(const float *samples, float *outDb) {
    for (int i = 0; i < n_; ++i) buf_[i] = {samples[i] * window_[i], 0.f};
    fft();
    const float norm = 2.f / n_;
    for (int i = 0; i < n_ / 2; ++i)
        outDb[i] = 20.f * std::log10(std::abs(buf_[i]) * norm + 1e-9f);
}
