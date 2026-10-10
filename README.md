# Radio Modem

An Android app that receives amateur-radio digital modes and weather fax, with optional CAT rig control. It
replicates part of [fldigi](http://www.w1hkj.com/) on a phone or tablet.

**Status:** receive only. Transmit is deliberately on hold. The decoders are verified against generated signals
(host tests mirror fldigi's transmit side); real over-the-air reception and CAT with real radios are still being
tested.

## What it does

| Area | Details |
|---|---|
| Modes | BPSK31/63/125, QPSK31/63/125, MFSK4/8/16/31/32/64/128 (including pictures), Olivia (4–64 tones × 125–2000 Hz), weather fax (IOC 576 and 288), SSTV (Martin M1/M2, Scottie S1/S2, Robot 36, PD50/90/120/160/180/240/290, with automatic mode detection from the VIS header) |
| Waterfall | Sharp zero-padded spectrum, frequency ruler, bright modem passband with the rest dimmed, uniform zoom, tap/drag/step buttons for the carrier, auto brightness |
| Weather fax | Automatic APT start/stop and phasing, AFC, auto slant (frozen after N lines), manual slant/shift, save/share |
| MFSK pictures | Decoded inline in the text feed and saved to Pictures/RadioModem |
| Fax schedule | Bundled NOAA worldwide snapshot plus live refresh; 24-hour timeline per station, UTC/local, one-tap Tune |
| Shortwave Radiogram | This week's transmissions (day, time, frequency, transmitter) read from the weekly schedule picture on swradiogram.net with on-device OCR; countdowns, one-tap Tune (MFSK32, 1500 Hz), per-transmission reminders, the week's lineup and notes |
| Saved frequencies | PSK31 calling frequencies, every known weather-fax station (named by location) and the Shortwave Radiogram transmitters; import/export CSV and JSON |
| Rigs | Several saved rigs (Yaesu FT-891, FT-991A, FTdx10 via flrig definitions) plus an audio-only shortwave receiver; default mode and bandwidth used when tuning; serial options |
| Background | A Monitoring switch and a foreground service keep decoding with the screen off |
| Logging | One text log per session, optional timestamps |

## Using it

1. Grant microphone access (this is also how a USB sound card's audio reaches Android). Notifications are needed for monitoring and reminders.
2. Pick the audio input under **Audio setup**.
3. Optional: add your radio under **Rig setup**, plug it in through an OTG adapter, and connect.
4. Choose a mode (the mode chip), then tap the waterfall or use the `−10 −1 +1 +10` buttons to put the carrier on
   the signal. Weather fax is tuned USB at the published frequency minus 1.9 kHz (the saved entries already do this).

## Building

Requirements: Android SDK (compileSdk 37), NDK 30.0.16248370, CMake 3.31.6, JDK 17. `local.properties` must point at the SDK.

```sh
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # Kotlin unit tests
tests/run_host_tests.sh               # native DSP tests (g++ only, no device)
tools/sstv_crosscheck.py              # SSTV decoder against an independent encoder (pip install PySSTV pillow numpy)
```

## Layout

- `app/src/main/cpp` – native DSP (`radiodsp`): audio capture (Oboe), spectrum, PSK/MFSK/Olivia/WEFAX demodulators, JNI bridge.
- `app/src/main/java/app/radiomodem` – Kotlin and Compose UI. `rig/` CAT and saved rigs, `data/` saved frequencies,
  `schedule/` fax schedule parsing and timeline, `radiogram/` Shortwave Radiogram feed, OCR, parser and reminders, `ui/` screens.
- `app/src/main/assets/rigs` – rig definitions. `schedules/` – bundled NOAA snapshot. `tessdata/` – Tesseract English model (Apache 2.0).
- `tools/flrig_extract.py` – lifts mode and bandwidth tables from flrig's rig classes; the CAT commands are hand-written
  in `tools/rigs/*.commands.json`. `tools/fetch_schedule_snapshot.py` refreshes the schedule snapshot.
- `tests/` – host tests with transmit generators mirrored from fldigi.

## SSTV

`sstv_demod.cpp` finds a transmission by its VIS header (or, if that was missed, by four sync pulses at a mode's line interval, so a picture can be joined part-way), reads the mode from it, takes the receiver's tuning error from
the header's 1900 Hz leader, and then aligns every line from its own sync pulse, so clock error and drift cannot skew the
picture. Pictures appear in the feed like MFSK pictures. Mode timings follow N7CXI's specification, checked against
slowrx. It is tested against a transmit generator written from the specification, against PySSTV (an independent
encoder) with noise, tuning error and clock error, and against third-party recordings. Not yet supported: Robot 72 and Scottie DX.

## Shortwave Radiogram schedule

The schedule is only published as a picture in each weekly post, so the app reads the blog's RSS feed
(`swradiogram.net/rss`), takes the week's dates from the post title, and reads the table from the picture with
Tesseract. Rows are validated (weekday, time, a plausible shortwave frequency, a known transmitter name) before use.
If the picture cannot be read, the last known table is kept with the new dates; a copy of the current table is
bundled so the screen works offline. Once a week is over and the next post is not out yet, the usual table is carried
forward and labelled as such. Reminders use exact alarms when Android allows them and are set again after a restart.

## Adding a radio

flrig has no data-driven rig database (each radio is a C++ class), so a model needs its flrig source and a small
command file:

```sh
curl -O https://raw.githubusercontent.com/w1hkj/flrig/master/src/rigs/yaesu/FT991A.cxx
tools/flrig_extract.py FT991A.cxx tools/rigs/FT-991A.commands.json app/src/main/assets/rigs/FT-991A.json
```

Only the Yaesu ASCII CAT family is implemented so far.

## License

GNU General Public License, version 3 or (at your option) any later version; see `LICENSE`. The decoders are ports of
fldigi algorithms and the rig tables come from flrig, both GPLv3. Pawel Jalocha's Olivia/MFSK headers are vendored
unchanged under `app/src/main/cpp/jalocha`. Other components: Oboe (Apache 2.0), usb-serial-for-android (MIT),
PdfBox-Android (Apache 2.0), Tesseract4Android with the Tesseract English model (Apache 2.0). NOAA schedule data is a US government work; the Shortwave Radiogram schedule belongs to its producer and is only read, not republished.
