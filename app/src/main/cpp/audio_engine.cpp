#include "audio_engine.h"
#include <cmath>

bool AudioEngine::start(int deviceId, int requestedRate) {
    stop();
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Input)
        ->setPerformanceMode(oboe::PerformanceMode::None)  // decoding is not latency-critical
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Mono)
        ->setSampleRate(requestedRate)
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setInputPreset(oboe::InputPreset::Unprocessed)  // no AGC/NS: we need raw audio
        ->setDataCallback(this);
    if (deviceId > 0) b.setDeviceId(deviceId);

    oboe::Result r = b.openStream(stream_);
    if (r != oboe::Result::OK) {
        // Some devices (notably USB audio) reject the unprocessed preset; the generic one is still unfiltered enough.
        b.setInputPreset(oboe::InputPreset::Generic);
        r = b.openStream(stream_);
    }
    if (r != oboe::Result::OK) { stream_.reset(); return false; }
    sampleRate_ = stream_->getSampleRate();  // verify, don't assume
    written_.store(0);
    if (stream_->requestStart() != oboe::Result::OK) { stream_.reset(); return false; }
    return true;
}

void AudioEngine::stop() {
    virtual_ = false;
    if (stream_) {
        stream_->requestStop();
        stream_->close();
        stream_.reset();
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream *, void *data, int32_t frames) {
    const float *in = static_cast<const float *>(data);
    const float g = gain_.load(std::memory_order_relaxed);
    uint64_t w = written_.load(std::memory_order_relaxed);
    float peak = 0; double sq = 0;
    for (int i = 0; i < frames; ++i) {
        float v = in[i] * g;
        ring_[(w + i) & (kRingSize - 1)] = v;
        float a = v < 0 ? -v : v;
        if (a > peak) peak = a;
        sq += (double)v * v;
    }
    written_.store(w + frames, std::memory_order_release);
    // Single producer (this callback), single consumer (takeLevel): plain max/accumulate is enough.
    if (peak > peak_.load(std::memory_order_relaxed)) peak_.store(peak, std::memory_order_relaxed);
    sumSq_.store(sumSq_.load(std::memory_order_relaxed) + sq, std::memory_order_relaxed);
    count_.store(count_.load(std::memory_order_relaxed) + (uint32_t)frames, std::memory_order_relaxed);
    return oboe::DataCallbackResult::Continue;
}

bool AudioEngine::latest(float *out, int n) const {
    uint64_t w = written_.load(std::memory_order_acquire);
    if (w < (uint64_t)n) return false;
    for (int i = 0; i < n; ++i) out[i] = ring_[(w - n + i) & (kRingSize - 1)];
    return true;
}

int AudioEngine::readNew(float *out, int maxN, uint64_t &cursor) const {
    uint64_t w = written_.load(std::memory_order_acquire);
    if (cursor > w) cursor = w;                       // stream restarted
    if (w - cursor > (uint64_t)(kRingSize / 2)) cursor = w - kRingSize / 2;  // overrun: drop old
    int n = (int)std::min<uint64_t>(w - cursor, (uint64_t)maxN);
    for (int i = 0; i < n; ++i) out[i] = ring_[(cursor + i) & (kRingSize - 1)];
    cursor += n;
    return n;
}

void AudioEngine::startVirtual(int rate) {
    stop();
    sampleRate_ = rate;
    written_.store(0);
    virtual_ = true;
}

void AudioEngine::inject(const float *in, int n) {
    uint64_t w = written_.load(std::memory_order_relaxed);
    for (int i = 0; i < n; ++i) ring_[(w + i) & (kRingSize - 1)] = in[i];
    written_.store(w + n, std::memory_order_release);
}

void AudioEngine::takeLevel(float &peak, float &rms) {
    peak = peak_.exchange(0.f, std::memory_order_relaxed);
    double sq = sumSq_.exchange(0.0, std::memory_order_relaxed);
    uint32_t n = count_.exchange(0, std::memory_order_relaxed);
    rms = n ? (float)std::sqrt(sq / n) : 0.f;
}
