cmake_minimum_required(VERSION 3.22)
project(qplayer_uac)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The userspace USB Audio Class driver. Pure usbfs ioctls — no libusb, no other
# dependencies — because everything it needs is the fd UsbDeviceConnection hands
# over. See uac_output.cpp.
add_library(qplayer_uac SHARED uac_output.cpp)

# Android 15 16 KB page-size compatibility: ELF LOAD segments must be aligned
# to 16 KB or devices running 16 KB kernels refuse to load the library.
target_link_options(qplayer_uac PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")

target_link_libraries(qplayer_uac
        PUBLIC
        log)
