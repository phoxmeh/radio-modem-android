#include "mfsk_demod.h"
#include "mfsk_varicode.h"
#include <algorithm>
#include <cmath>
#include <cctype>
#include <cstring>

namespace {
constexpr double kTwoPi = 2.0 * M_PI;

double sinc(double x) { return std::fabs(x) < 1e-10 ? 1.0 : std::sin(M_PI * x) / (M_PI * x); }
double cosc(double x) { return std::fabs(x) < 1e-10 ? 0.0 : (1.0 - std::cos(M_PI * x)) / (M_PI * x); }
double hamming(double x) { return 0.54 - 0.46 * std::cos(kTwoPi * x); }

// fldigi C_FIR_filter::bp_FIR
std::vector<double> bpFir(int len, bool hilbert, double f1, double f2) {
    std::vector<double> fir(len);
    for (int i = 0; i < len; ++i) {
        double t = i - (len - 1.0) / 2.0, h = i * (1.0 / (len - 1.0)), x;
        if (!hilbert) x = (2 * f2 * sinc(2 * f2 * t) - 2 * f1 * sinc(2 * f1 * t)) * hamming(h);
        else          x = -((2 * f2 * cosc(2 * f2 * t) - 2 * f1 * cosc(2 * f1 * t)) * hamming(h));
        fir[i] = x;
    }
    return fir;
}

// NOTE: fldigi's names are swapped relative to convention - "grayencode" is the prefix XOR and
// "graydecode" is x ^ (x >> 1). Kept as-is so signals interoperate.
unsigned char graydecode(unsigned char d) { return d ^ (d >> 1); }

double decayavg(double average, double input, int weight) {
    if (weight <= 1) return input;
    return (input - average) / (double)weight + average;
}

std::vector<float> lowpass(int len, double fc, double fs) {
    std::vector<float> h(len);
    double sum = 0;
    for (int i = 0; i < len; ++i) {
        double m = i - (len - 1) / 2.0;
        double s = (m == 0) ? 2 * fc / fs : std::sin(2 * M_PI * fc * m / fs) / (M_PI * m);
        h[i] = (float)(s * (0.5 - 0.5 * std::cos(2 * M_PI * i / (len - 1))));
        sum += h[i];
    }
    for (auto &v : h) v = (float)(v / sum);
    return h;
}
}  // namespace

// ---- fldigi filter primitives ----------------------------------------------------------------

void MfskDemod::Fir::init(std::vector<double> i, std::vector<double> q) {
    ti = std::move(i); tq = std::move(q);
    bi.assign(ti.size(), 0.0); bq.assign(ti.size(), 0.0);
    pos = 0;
}

MfskDemod::cmplx MfskDemod::Fir::run(cmplx in) {
    const int n = (int)ti.size();
    bi[pos] = in.real(); bq[pos] = in.imag();
    pos = (pos + 1) % n;  // pos now indexes the oldest sample
    double r = 0, im = 0;
    for (int k = 0, p = pos; k < n; ++k, p = (p + 1) % n) { r += ti[k] * bi[p]; im += tq[k] * bq[p]; }
    return {r, im};
}

MfskDemod::Sfft::Sfft(int len_, int first_, int last_)
    : len(len_), first(first_), last(last_), k2(1.0), vrot(len_), bins(len_, 0.0), delay(len_, 0.0) {
    const double K1 = 0.99999999999, tau = kTwoPi / len;
    double phi = 0;
    for (int i = 0; i < len; ++i) {
        vrot[i] = cmplx(K1 * std::cos(phi), K1 * std::sin(phi));
        phi += tau;
        k2 *= K1;
    }
}

void MfskDemod::Sfft::run(cmplx in, cmplx *result) {
    cmplx &de = delay[ptr];
    const cmplx z(in.real() - k2 * de.real(), in.imag() - k2 * de.imag());
    de = in;
    if (++ptr >= len) ptr = 0;
    for (int i = first; i < last; ++i) *result++ = bins[i] = bins[i] * vrot[i] + z * vrot[i];
}

// ---- setup -----------------------------------------------------------------------------------

MfskDemod::MfskDemod(int inputRate, Mode mode, float centerHz) : mode_(mode), frequency_(centerHz) {
    std::memset(picheader_, ' ', sizeof picheader_); picheader_[63] = 0;
    // samplerate 8000 for all supported modes
    switch (mode) {
        case MFSK4:   symlen_ = 2048; symbits_ = 5; depth_ = 5;  basetone_ = 256; numtones_ = 32; break;
        case MFSK8:   symlen_ = 1024; symbits_ = 5; depth_ = 5;  basetone_ = 128; numtones_ = 32; break;
        case MFSK31:  symlen_ = 256;  symbits_ = 3; depth_ = 10; basetone_ = 32;  numtones_ = 8;  break;
        case MFSK32:  symlen_ = 256;  symbits_ = 4; depth_ = 10; basetone_ = 32;  numtones_ = 16; break;
        case MFSK64:  symlen_ = 128;  symbits_ = 4; depth_ = 10; basetone_ = 16;  numtones_ = 16; break;
        case MFSK128: symlen_ = 64;   symbits_ = 4; depth_ = 20; basetone_ = 8;   numtones_ = 16; break;
        case MFSK16: default:
                      symlen_ = 512;  symbits_ = 4; depth_ = 10; basetone_ = 64;  numtones_ = 16; break;
    }
    tonespacing_ = (double)samplerate_ / symlen_;
    basefreq_ = 1.0 * samplerate_ * basetone_ / symlen_;
    bandwidth_ = (numtones_ - 1) * tonespacing_;

    inDecim_ = std::max(1, inputRate / samplerate_);
    if (inDecim_ > 1) {
        preTaps_ = lowpass(inDecim_ * 8 + 1, 3400.0, inputRate);
        preBuf_.assign(preTaps_.size(), 0.f);
    }

    hilbert_.init(bpFir(37, false, 0.05, 0.45), bpFir(37, true, 0.05, 0.45));
    const double cf = basefreq_ + bandwidth_ / 2.0;
    const double flo = (cf - bandwidth_ / 2 - 2 * tonespacing_) / samplerate_;
    const double fhi = (cf + bandwidth_ / 2 + 2 * tonespacing_) / samplerate_;
    auto bp = bpFir(127, false, flo, fhi);
    bandpass_.init(bp, bp);

    sfft_ = std::make_unique<Sfft>(symlen_, basetone_, basetone_ + numtones_);
    pipe_.assign(2 * symlen_, {});
    syncAvg_.assign(8, 0.0);

    // NASA K=7 code; two decoders because odd bits/symbol split a code pair across symbols.
    dec1_ = std::make_unique<Viterbi>(7, 0x6d, 0x4f);
    dec2_ = std::make_unique<Viterbi>(7, 0x6d, 0x4f);
    for (auto *d : {dec1_.get(), dec2_.get()}) { d->setTraceback(45); d->setChunkSize(1); }
    rxinlv_ = std::make_unique<Interleave>(symbits_, depth_, Interleave::Rev);
}

MfskDemod::~MfskDemod() = default;

// ---- receive ---------------------------------------------------------------------------------

void MfskDemod::process(const float *in, int n, std::string &out) {
    out_ = &out;
    if (inDecim_ <= 1) {
        for (int i = 0; i < n; ++i) sample(in[i]);
        return;
    }
    const int len = (int)preTaps_.size();
    for (int i = 0; i < n; ++i) {
        preBuf_[prePos_] = in[i];
        prePos_ = (prePos_ + 1) % len;
        if (++preCount_ < inDecim_) continue;
        preCount_ = 0;
        float acc = 0;
        for (int k = 0, p = prePos_; k < len; ++k, p = (p + 1) % len) acc += preTaps_[k] * preBuf_[p];
        sample(acc);
    }
}

MfskDemod::cmplx MfskDemod::mixer(cmplx in, double f) {
    // Tones are expected at basetone*spacing; `frequency_` is the middle of the tone block.
    f -= tonespacing_ * basetone_ + bandwidth_ / 2;
    cmplx z = in * cmplx(std::cos(phaseacc_), std::sin(phaseacc_));
    phaseacc_ -= kTwoPi * f / samplerate_;
    if (phaseacc_ < 0) phaseacc_ += kTwoPi;
    return z;
}

void MfskDemod::sample(double x) {
    cmplx z = hilbert_.run(cmplx(x, x));          // analytic signal
    z = mixer(z, frequency_);                      // shift tone block to its nominal bins
    z = bandpass_.run(z);
    sfft_->run(z, pipe_[pipeptr_].data());

    if (rxstate_ == PictureStart) {
        if (counter_ <= 0) rxstate_ = Data;  // a stray character cancelled the countdown
        else if (--counter_ == 0) {
            counter_ = picturesize_;
            rxstate_ = Picture;
            picState_ = 1;
        }
    }
    if (rxstate_ == Picture) {
        if (--counter_ == 0) { picState_ = 2; rxInit(); }
        else recvpic(z);
        return;
    }

    if (--synccounter_ <= 0) {
        synccounter_ = symlen_;
        const cmplx *bins = pipe_[pipeptr_].data();
        currsymbol_ = harddecode(bins);
        currvector_ = bins[currsymbol_];
        softdecode(bins);
        synchronize();
        afc();
        prev2symbol_ = prev1symbol_; prev2vector_ = prev1vector_;
        prev1symbol_ = currsymbol_;  prev1vector_ = currvector_;
    }
    pipeptr_ = (pipeptr_ + 1) % (2 * symlen_);
}

int MfskDemod::harddecode(const cmplx *in) {
    double max = 0, avg = 0;
    int symbol = 0, burst = 0;
    for (int i = 0; i < numtones_; ++i) avg += std::abs(in[i]);
    avg /= numtones_;
    if (avg < 1e-20) avg = 1e-20;
    for (int i = 0; i < numtones_; ++i) {
        double v = std::abs(in[i]);
        if (v > max) { max = v; symbol = i; }
        if (v > 2.0 * avg) ++burst;
    }
    staticburst_ = (burst == numtones_);
    afcmetric_ = staticburst_ ? 0.0 : 0.95 * afcmetric_ + 0.05 * (2 * max / avg);
    return symbol;
}

void MfskDemod::softdecode(const cmplx *bins) {
    constexpr int kCwiMax = 6;  // longest valid run of a repeated tone
    double b[8] = {0}, sum = 0;
    unsigned char symbols[8];

    for (int i = 0; i < numtones_; ++i)
        if (cwiCounter_[i] < kCwiMax) sum += std::abs(bins[i]);
    const double avg = sum / numtones_;
    if (sum < 1e-10) sum = 1e-10;

    const int cwiSymbol = reverse_ ? (numtones_ - 1) - currsymbol_ : currsymbol_;
    for (int i = 1; i < numtones_; ++i) {  // tone 0 is never treated as interference
        int k = reverse_ ? (numtones_ - 1) - i : i;
        if (k == cwiSymbol) ++cwiCounter_[k]; else --cwiCounter_[k];
        if (cwiCounter_[k] < 0) cwiCounter_[k] = 0;
        if (cwiCounter_[k] > kCwiMax) cwiCounter_[k] = kCwiMax + 1;
    }

    for (int i = 0; i < numtones_; ++i) {
        int j = graydecode((unsigned char)i);
        int k = reverse_ ? (numtones_ - 1) - i : i;
        double mag;
        if (cwiCounter_[k] > kCwiMax) mag = avg;                       // soft-puncture a stuck tone
        else if (cwiSymbol == k)      mag = 2.0 * std::abs(bins[k]);    // hard decision gets a vote
        else                          mag = std::abs(bins[k]);
        for (int m = 0; m < symbits_; ++m) b[m] += (j & (1 << (symbits_ - m - 1))) ? mag : -mag;
    }

    for (int i = 0; i < symbits_; ++i) {
        symbols[i] = staticburst_ ? 128
            : (unsigned char)std::clamp(128.0 + (b[i] / sum * 256.0), 0.0, 255.0);
    }
    rxinlv_->symbols(symbols);
    for (int i = 0; i < symbits_; ++i) {
        symbolbit_ = i + 1;
        decodesymbol(symbols[i]);
        if (counter_) return;  // header seen: let the countdown run, ignore the rest of this symbol
    }
}

void MfskDemod::decodesymbol(unsigned char symbol) {
    symbolpair_[0] = symbolpair_[1];
    symbolpair_[1] = symbol;
    symcounter_ = symcounter_ ? 0 : 1;
    int c, met;

    if (symbits_ == 5 || symbits_ == 3) {  // odd bits/symbol: two interleaved decoders, keep the better
        if (symcounter_) {
            if ((c = dec1_->decode(symbolpair_, &met)) == -1) return;
            met1_ = decayavg(met1_, met, 50);
            if (met1_ < met2_) return;
            metric_ = met1_;
        } else {
            if ((c = dec2_->decode(symbolpair_, &met)) == -1) return;
            met2_ = decayavg(met2_, met, 50);
            if (met2_ < met1_) return;
            metric_ = met2_;
        }
    } else {
        if (symcounter_) return;
        if ((c = dec2_->decode(symbolpair_, &met)) == -1) return;
        met2_ = decayavg(met2_, met, 50);
        metric_ = met2_;
    }

    metric_ -= 60.0;       // fldigi display scaling
    metric_ *= 0.5;
    metric_ = std::clamp(metric_, 0.0, 100.0);
    if (metric_ < squelch_ * 100.0) return;
    recvbit(c);
}

void MfskDemod::recvbit(int bit) {
    datashreg_ = (datashreg_ << 1) | (bit ? 1 : 0);
    if ((datashreg_ & 7) == 1) {
        unsigned sym = datashreg_ >> 1;
        for (int i = 0; i < 256; ++i)
            if (kMfskVaridecode[i] == sym) {
                recvchar(i);
                break;
            }
        datashreg_ = 1;
    }
}

// Re-times the symbol clock from where the previous tone's energy peaks in the 2-symbol history.
void MfskDemod::synchronize() {
    if (currsymbol_ == prev1symbol_ || prev1symbol_ == prev2symbol_) return;
    double val, max = 0, syn = -1;
    int j = pipeptr_;
    for (int i = 0; i < 2 * symlen_; ++i) {
        val = std::abs(pipe_[j][prev1symbol_]);
        if (val > max) { max = val; syn = i; }
        j = (j + 1) % (2 * symlen_);
    }
    // Cmovavg(8)
    if (syncEmpty_) { syncEmpty_ = false; std::fill(syncAvg_.begin(), syncAvg_.end(), syn); syncSum_ = syn * 8; syncPos_ = 0; }
    else { syncSum_ += syn - syncAvg_[syncPos_]; syncAvg_[syncPos_] = syn; syncPos_ = (syncPos_ + 1) % 8; }
    syn = syncSum_ / 8;
    synccounter_ += (int)std::floor((syn - symlen_) / numtones_ + 0.5);
}

void MfskDemod::afc() {
    if (!afcOn_ || afcmetric_ < 3.0 || metric_ < squelch_ * 100.0 || currsymbol_ != prev1symbol_ || staticburst_) return;
    cmplx prevvector = pipe_[pipeptr_ == 0 ? 2 * symlen_ - 1 : pipeptr_ - 1][currsymbol_];
    cmplx z = std::conj(prevvector) * currvector_;
    double f = std::arg(z) * samplerate_ / kTwoPi;
    double f1 = tonespacing_ * (basetone_ + currsymbol_);
    if (std::fabs(f1 - f) < tonespacing_ / 4) {
        freqerr_ = decayavg(freqerr_, f1 - f, 32);  // fldigi mfsk_avg default
        frequency_ -= freqerr_;
    }
}

// ---- pictures ---------------------------------------------------------------------------------

void MfskDemod::rxInit() {
    rxstate_ = Data;
    synccounter_ = 0; symcounter_ = 0;
    met1_ = met2_ = 0; counter_ = 0; rxspp_ = 8;
    for (auto &v : pipe_) v.fill({0, 0});
    freqerr_ = 0; syncEmpty_ = true;
    std::memset(picheader_, ' ', sizeof picheader_); picheader_[63] = 0;
    staticburst_ = false;
}

// fldigi mfsk::check_picture_header: "Pic:<W>x<H>[C][p<N>];" among the last 63 printable characters.
bool MfskDemod::checkPictureHeader(char c) {
    if (c >= ' ' && c <= 'z') {
        std::memmove(picheader_, picheader_ + 1, 62);
        picheader_[62] = c;
    }
    picW_ = 0; picH_ = 0; color_ = false;
    const char *p = std::strstr(picheader_, "Pic:");
    if (!p) return false;
    p += 4;
    if (!*p) return false;
    while (*p && std::isdigit((unsigned char)*p)) picW_ = picW_ * 10 + (*p++ - '0');
    if (*p++ != 'x') return false;
    while (*p && std::isdigit((unsigned char)*p)) picH_ = picH_ * 10 + (*p++ - '0');
    if (*p == 'C') { color_ = true; ++p; }
    if (*p == ';') {
        if (picW_ == 0 || picH_ == 0 || picW_ > 4095 || picH_ > 4095 || (long)picW_ * picH_ > kMaxPicturePixels) return false;
        rxspp_ = 8;
        return true;
    }
    if (*p == 'p') ++p; else return false;
    if (!*p) return false;
    rxspp_ = 8;
    if (*p == '4') rxspp_ = 4;
    if (*p == '2') rxspp_ = 2;
    ++p;
    if (!*p || *p != ';') return false;
    return picW_ != 0 && picH_ != 0 && picW_ <= 4095 && picH_ <= 4095 && (long)picW_ * picH_ <= kMaxPicturePixels;
}

void MfskDemod::recvchar(int c) {
    if (c == -1 || c == 0) return;
    if (out_) out_->push_back((char)c);

    if (checkPictureHeader((char)c)) {
        // Samples left until the pixel data starts. fldigi's empirical values: the receiver recognises the
        // header late (interleaver + Viterbi traceback), and the lag depends on the mode.
        counter_ = 352;
        switch (mode_) {
            case MFSK16: case MFSK32: if (symbolbit_ == symbits_) counter_ += symlen_; break;
            case MFSK64:  counter_ = 4956; if (symbolbit_ % 2 == 0) counter_ += symlen_; break;
            case MFSK128: counter_ = 1824; if (symbolbit_ % 2 == 0) counter_ += symlen_; break;
            case MFSK31:  counter_ = 5216; if (symbolbit_ == symbits_) counter_ += symlen_; break;
            default: break;  // MFSK4/8 have no picture mode
        }
        rxstate_ = PictureStart;
        curW_ = picW_; curH_ = picH_; curColor_ = color_;
        picturesize_ = rxspp_ * curW_ * curH_ * (curColor_ ? 3 : 1);
        pixelnbr_ = 0; col_ = 0; row_ = 0; rgb_ = 0;
        picf_ = 0; prevz_ = {0, 0};
        ++pictureId_; picState_ = 0; picRowsReported_ = 0;
        picRgb_.assign((size_t)curW_ * curH_ * 3, 0);
        std::memset(picheader_, ' ', sizeof picheader_); picheader_[63] = 0;
        return;
    }
    counter_ = 0;
}

// Each pixel is the mean instantaneous frequency over rxspp_ samples, mapped across the tone block to 0..255.
void MfskDemod::recvpic(cmplx z) {
    picf_ += std::arg(std::conj(prevz_) * z) * samplerate_ / kTwoPi;
    prevz_ = z;
    if ((counter_ % rxspp_) != 0) return;

    double f = 256.0 * (picf_ / rxspp_ - basefreq_) / bandwidth_;
    int byte = (int)std::clamp(f, 0.0, 255.0);
    if (reverse_) byte = 255 - byte;
    picf_ = 0;

    auto put = [&](int idx, int v) { if ((size_t)idx < picRgb_.size()) picRgb_[idx] = (uint8_t)v; };
    if (curColor_) {
        // Line-sequential: a full row of R, then G, then B.
        put(rgb_ + row_ + 3 * col_, byte);
        if (++col_ == curW_) {
            col_ = 0;
            if (++rgb_ == 3) { rgb_ = 0; row_ += 3 * curW_; }
        }
    } else {
        for (int i = 0; i < 3; ++i) put(pixelnbr_++, byte);
    }
}

size_t MfskDemod::pollPicture(std::vector<uint8_t> &out) {
    if (pictureId_ == 0) return 0;
    int rowsDone;
    if (picState_ == 2) rowsDone = curH_;
    else if (curColor_) rowsDone = row_ / (3 * curW_);          // a row is complete after its B pass
    else rowsDone = pixelnbr_ / (3 * curW_);
    rowsDone = std::min(rowsDone, curH_);
    const int first = picRowsReported_, count = std::max(0, rowsDone - picRowsReported_);
    const size_t start = out.size(), rowBytes = (size_t)curW_ * 3;
    int32_t header[7] = {pictureId_, curW_, curH_, curColor_ ? 1 : 0, first, count, picState_};
    out.resize(start + sizeof header + count * rowBytes);
    std::memcpy(out.data() + start, header, sizeof header);
    if (count) std::memcpy(out.data() + start + sizeof header, picRgb_.data() + (size_t)first * rowBytes, count * rowBytes);
    picRowsReported_ += count;
    return out.size() - start;
}
