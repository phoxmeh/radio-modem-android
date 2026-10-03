cmake_minimum_required(VERSION 3.22)
project(radiodsp LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)

find_package(oboe REQUIRED CONFIG)

add_library(radiodsp SHARED
    jni_bridge.cpp
    audio_engine.cpp
    spectrum.cpp
    psk_demod.cpp
    viterbi.cpp
    interleave.cpp
    mfsk_demod.cpp
    olivia_demod.cpp
    wefax_demod.cpp)
target_link_libraries(radiodsp oboe::oboe log)
