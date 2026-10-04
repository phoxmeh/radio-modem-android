#pragma once
#include <vector>
#include <complex>

// Hann-windowed power spectrum in dB for the waterfall. The window covers `window` samples; the FFT is
// zero-padded to `fft` points, which interpolates the bins (fs/fft apart) so narrow signals draw smoothly.
class Spectrum {
public:
    Spectrum(int window = 8192, int fft = 16384);
    int windowSize() const { return win_; }
    int fftSize() const { return n_; }
    // `samples` has windowSize() entries; `outDb` receives the first `count` bins (0 .. fs/2), count <= fftSize()/2.
    void compute(const float *samples, float *outDb, int count);
private:
    int win_, n_;
    std::vector<float> window_;
    std::vector<std::complex<float>> buf_, twiddle_;
    std::vector<int> rev_;
    void fft();
};
