cmake_minimum_required(VERSION 3.22.1)
project(lifeos_mmsi_native LANGUAGES CXX)

add_library(lifeos_mmsi_native SHARED
    mmsi_native.cpp
    vulkan_probe.cpp
    vulkan_dispatch.cpp
)

target_compile_features(lifeos_mmsi_native PRIVATE cxx_std_20)
target_compile_options(lifeos_mmsi_native PRIVATE -O3 -fno-exceptions)

find_library(log-lib log)
find_library(vulkan-lib vulkan)
target_link_libraries(lifeos_mmsi_native PRIVATE ${log-lib} ${vulkan-lib})
