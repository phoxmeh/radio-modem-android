#!/usr/bin/env python3
"""Cross-checks the native SSTV decoder against an independent encoder (PySSTV).

Generates a test picture, encodes it in each supported mode (optionally impaired: noise, tuning error, clock error),
decodes the WAV with the host build of the decoder and prints the mean pixel error. Needs: pip install PySSTV pillow numpy.
"""
import subprocess, sys, os, wave, tempfile
import numpy as np
from PIL import Image, ImageDraw
from pysstv.color import MartinM1, MartinM2, ScottieS1, ScottieS2, Robot36, ScottieDX, PD90, PD120, PD160, PD180, PD240

# PySSTV has no PD50 or PD290; they differ from PD90 only in size and timing.
class PD50(PD90):
    VIS_CODE = 0x5D; PIXEL = 0.286
class PD290(PD90):
    VIS_CODE = 0x5E; WIDTH = 800; HEIGHT = 616; PIXEL = 0.286

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, '..', 'app', 'src', 'main', 'cpp')

def build(tmp):
    exe = os.path.join(tmp, 'sstv_decode')
    subprocess.check_call(['g++', '-std=c++17', '-O2', '-I' + SRC, os.path.join(HERE, '..', 'tests', 'sstv_decode.cpp'),
                           os.path.join(SRC, 'sstv_demod.cpp'), '-o', exe])
    return exe

def picture(w, h):
    im = Image.new('RGB', (w, h), (30, 30, 90))
    d = ImageDraw.Draw(im)
    for i in range(0, w, 40): d.rectangle([i, 0, i + 19, h // 3], fill=(255 * (i // 40 % 2), 200, 40 + i % 200))
    for y in range(h // 3, 2 * h // 3, 8): d.line([0, y, w, y + 20], fill=(255, 255, 255), width=3)
    d.ellipse([w // 3, 2 * h // 3 - 20, 2 * w // 3, h - 10], fill=(220, 40, 40), outline=(255, 255, 0), width=4)
    return im

def impair(wavpath, out, noise, offset_hz, clock_ppm):
    w = wave.open(wavpath); rate = w.getframerate(); x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float64) / 32768
    if clock_ppm:  # the receiver's clock runs fast or slow: resample by interpolation
        n = len(x); t = np.arange(int(n / (1 + clock_ppm * 1e-6))) * (1 + clock_ppm * 1e-6)
        x = np.interp(t, np.arange(n), x)
    if offset_hz:  # mistuned receiver: shift every tone by offset_hz (via the analytic signal)
        X = np.fft.fft(x); h = np.zeros(len(x)); h[0] = 1; h[1:len(x) // 2] = 2; h[len(x) // 2] = 1
        a = np.fft.ifft(X * h); x = np.real(a * np.exp(2j * np.pi * offset_hz * np.arange(len(x)) / rate))
    if noise: x = x + np.random.default_rng(1).normal(0, noise, len(x))
    y = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    o = wave.open(out, 'wb'); o.setnchannels(1); o.setsampwidth(2); o.setframerate(rate); o.writeframes(y.tobytes()); o.close()

def ppm(path):
    with open(path, 'rb') as f:
        assert f.readline().strip() == b'P6'; w, h = map(int, f.readline().split()); f.readline()
        return np.frombuffer(f.read(), dtype=np.uint8).reshape(h, w, 3)

def main():
    modes = [('Martin M1', MartinM1, 0), ('Martin M2', MartinM2, 1), ('Scottie S1', ScottieS1, 2), ('Scottie S2', ScottieS2, 3), ('Robot 36', Robot36, 4), ('Scottie DX', ScottieDX, 12),
             ('PD50', PD50, 5), ('PD90', PD90, 6), ('PD120', PD120, 7), ('PD160', PD160, 8), ('PD180', PD180, 9), ('PD240', PD240, 10), ('PD290', PD290, 11)]
    cases = [('clean', 0, 0, 0), ('noise 0.15', 0.15, 0, 0), ('tuned +60 Hz', 0, 60, 0), ('clock +500 ppm', 0, 0, 500)]
    if os.environ.get('QUICK'): cases = cases[:1]
    only = sys.argv[1:] 
    ok = True
    with tempfile.TemporaryDirectory() as tmp:
        exe = build(tmp)
        for name, cls, idx in modes:
            if only and name not in only: continue
            im = picture(cls.WIDTH, cls.HEIGHT)
            enc = cls(im, 48000, 16); wav = os.path.join(tmp, 'a.wav'); enc.write_wav(wav)
            ref = np.asarray(im.convert('RGB'), dtype=float)
            # PySSTV's M2 and S2 take 160-pixel pictures but time them as the real 320-pixel modes.
            if cls.WIDTH == 160: ref = np.repeat(ref, 2, axis=1)
            for cname, noise, off, ppm_ in cases:
                w2 = os.path.join(tmp, 'b.wav'); impair(wav, w2, noise, off, ppm_)
                out = subprocess.run([exe, w2, os.path.join(tmp, 'pic')], capture_output=True, text=True)
                files = [f for f in os.listdir(tmp) if f.startswith('pic-') and f.endswith('.ppm')]
                if not files: print(f'{name:11s} {cname:15s} FAIL (no picture) {out.stdout.strip()[-80:]}'); ok = False; continue
                got = ppm(os.path.join(tmp, files[0])).astype(float)
                err = np.abs(got - ref).mean()
                lim = {'clean': 22, 'noise 0.15': 40, 'tuned +60 Hz': 22, 'clock +500 ppm': 25}[cname] + (15 if name.startswith('PD') else 0)
                good = got.shape == ref.shape and err < lim
                ok &= good
                print(f'{name:11s} {cname:15s} {"PASS" if good else "FAIL"}  mean error {err:5.1f}/255  ({got.shape[1]}x{got.shape[0]})')
                for f in files: os.remove(os.path.join(tmp, f))
    sys.exit(0 if ok else 1)

main()
