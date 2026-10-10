// Decodes a WAV file (16-bit mono, any rate) with SstvDemod and writes each picture as a PPM: sstv_decode in.wav outprefix [mode]
#include "sstv_demod.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

int main(int argc, char **argv) {
    if (argc < 3) { fprintf(stderr, "usage: %s in.wav out-prefix [mode 0-4]\n", argv[0]); return 2; }
    FILE *f = fopen(argv[1], "rb");
    if (!f) { perror("open"); return 2; }
    std::vector<uint8_t> b; uint8_t buf[65536]; size_t n;
    while ((n = fread(buf, 1, sizeof buf, f)) > 0) b.insert(b.end(), buf, buf + n);
    fclose(f);
    if (b.size() < 44 || memcmp(&b[0], "RIFF", 4) || memcmp(&b[8], "WAVE", 4)) { fprintf(stderr, "not a WAV\n"); return 2; }
    int rate = 0, ch = 1; size_t pos = 12, dataOff = 0, dataLen = 0;
    while (pos + 8 <= b.size()) {
        uint32_t len; memcpy(&len, &b[pos + 4], 4);
        if (!memcmp(&b[pos], "fmt ", 4)) { uint16_t c; uint32_t r; memcpy(&c, &b[pos + 10], 2); memcpy(&r, &b[pos + 12], 4); ch = c; rate = (int)r; }
        if (!memcmp(&b[pos], "data", 4)) { dataOff = pos + 8; dataLen = std::min<size_t>(len, b.size() - dataOff); break; }
        pos += 8 + len + (len & 1);
    }
    std::vector<float> s(dataLen / 2 / ch);
    for (size_t i = 0; i < s.size(); ++i) { int16_t v; memcpy(&v, &b[dataOff + i * 2 * ch], 2); s[i] = v / 32768.f; }
    SstvDemod d(rate, argc > 3 ? atoi(argv[3]) : -1);
    std::string text; std::vector<uint8_t> pic; int id = 0, w = 0, h = 0, rows = 0, state = 0, shown = 0;
    std::vector<uint8_t> img;
    auto flush = [&]() {
        if (w && rows) { char name[512]; snprintf(name, sizeof name, "%s-%d.ppm", argv[2], id); FILE *o = fopen(name, "wb");
            fprintf(o, "P6\n%d %d\n255\n", w, h); fwrite(img.data(), 1, (size_t)w * h * 3, o); fclose(o); printf("wrote %s (%dx%d, %d lines)\n", name, w, h, rows); }
    };
    for (size_t i = 0; i < s.size(); i += 480) {
        d.process(&s[i], (int)std::min<size_t>(480, s.size() - i), text);
        pic.clear();
        if (d.pollPicture(pic)) {
            int32_t hd[7]; memcpy(hd, pic.data(), sizeof hd);
            if (hd[0] != id) { flush(); id = hd[0]; w = hd[1]; h = hd[2]; img.assign((size_t)w * h * 3, 0); rows = 0; }
            memcpy(&img[(size_t)hd[4] * w * 3], pic.data() + sizeof hd, (size_t)hd[5] * w * 3);
            rows = hd[4] + hd[5]; state = hd[6];
        }
    }
    flush();
    for (char c : text) putchar(c);
    (void)shown; (void)state;
    return 0;
}
