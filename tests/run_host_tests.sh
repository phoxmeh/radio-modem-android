#!/bin/sh
# Builds and runs the native DSP tests on the host (no Android device needed).
set -e
cd "$(dirname "$0")"
SRC=../app/src/main/cpp
OUT=${TMPDIR:-/tmp}/radiomodem-host-tests
mkdir -p "$OUT"
g++ -std=c++17 -O2 -I$SRC psk_test.cpp $SRC/psk_demod.cpp $SRC/viterbi.cpp -o "$OUT/psk_test"
g++ -std=c++17 -O2 -I$SRC mfsk_test.cpp $SRC/mfsk_demod.cpp $SRC/viterbi.cpp $SRC/interleave.cpp -o "$OUT/mfsk_test"
"$OUT/mfsk_test" --pictures
g++ -std=c++17 -O2 -I$SRC olivia_test.cpp $SRC/olivia_demod.cpp -o "$OUT/olivia_test"
g++ -std=c++17 -O2 -I$SRC wefax_test.cpp $SRC/wefax_demod.cpp -o "$OUT/wefax_test"
"$OUT/psk_test"
"$OUT/mfsk_test"
"$OUT/olivia_test"
"$OUT/wefax_test"
