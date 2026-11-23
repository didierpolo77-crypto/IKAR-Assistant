cmake_minimum_required(VERSION 3.22.1)
project(ikar_yara LANGUAGES C CXX)

set(YARA_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/yara")
set(YARA_STATIC "${YARA_ROOT}/armeabi-v7a/libyara.a")
if(NOT EXISTS "${YARA_STATIC}")
    message(FATAL_ERROR "libyara.a ARMv7 absent. Run scripts/build-libyara-android-armv7.sh first.")
endif()

add_library(yara_static STATIC IMPORTED)
set_target_properties(yara_static PROPERTIES IMPORTED_LOCATION "${YARA_STATIC}")

add_library(ikar_yara SHARED ikar_yara_jni.cpp)
target_include_directories(ikar_yara PRIVATE "${YARA_ROOT}/include")
target_compile_features(ikar_yara PRIVATE cxx_std_17)
target_compile_options(ikar_yara PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(ikar_yara PRIVATE yara_static log m)
