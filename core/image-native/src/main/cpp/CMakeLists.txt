cmake_minimum_required(VERSION 3.22.1)
project(lifeos_mmsi_native LANGUAGES CXX)

add_library(lifeos_mmsi_native SHARED
    mmsi_native.cpp
)

target_compile_features(lifeos_mmsi_native PRIVATE cxx_std_20)
target_compile_options(lifeos_mmsi_native PRIVATE -O3 -fno-exceptions)

find_library(log-lib log)
target_link_libraries(lifeos_mmsi_native PRIVATE ${log-lib})
