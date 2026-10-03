#pragma once
#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <vector>

// Captures mono float audio via Oboe into a single-producer/single-consumer ring.
// Consumers (waterfall, modems) read snapshots of the most recent samples.
class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    static constexpr int kRingSize = 1 << 17;  // ~2.7 s at 48 kHz, power of two

    bool start(int deviceId, int requestedRate);
    void stop();
    int sampleRate() const { return sampleRate_; }
    int deviceId() const { return stream_ ? stream_->getDeviceId() : 0; }

    // Software gain applied to captured audio (not to injected test audio), and a level meter.
    void setGain(float g) { gain_.store(g, std::memory_order_relaxed); }
    // Peak and RMS (linear, 0..1 of full scale) since the previous call; resets the accumulators.
    void takeLevel(float &peak, float &rms);
    bool running() const { return stream_ != nullptr || virtual_; }

    // Virtual input (WAV playback / tests): samples are pushed instead of captured.
    void startVirtual(int rate);
    void inject(const float *in, int n);

    // Copies the latest `n` samples (oldest first). Returns false if fewer are available.
    bool latest(float *out, int n) const;
    // Copies samples written since `cursor` (advancing it). Skips ahead if the consumer fell behind.
    int readNew(float *out, int maxN, uint64_t &cursor) const;
    uint64_t totalFrames() const { return written_.load(std::memory_order_acquire); }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *, void *data, int32_t frames) override;

private:
    std::shared_ptr<oboe::AudioStream> stream_;
    std::vector<float> ring_ = std::vector<float>(kRingSize, 0.f);
    std::atomic<uint64_t> written_{0};
    int sampleRate_ = 48000;
    bool virtual_ = false;
    std::atomic<float> gain_{1.f};
    std::atomic<float> peak_{0.f};
    std::atomic<double> sumSq_{0.0};
    std::atomic<uint32_t> count_{0};
};
