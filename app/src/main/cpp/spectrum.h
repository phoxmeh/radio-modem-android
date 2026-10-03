#pragma once
#include <vector>
#include <complex>

// Hann-windowed power spectrum in dB for waterfall display.
class Spectrum {
public:
    explicit Spectrum(int n = 4096);
    int size() const { return n_; }
    // `samples` has size() entries; `outDb` receives size()/2 bins (0..fs/2).
    void compute(const float *samples, float *outDb);
private:
    int n_;
    std::vector<float> window_;
    std::vector<std::complex<float>> buf_;
    void fft();
};
