cmake_minimum_required(VERSION 3.22.1)
project(ikar_yara LANGUAGES C CXX)

set(YARA_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/third_party/yara")
set(YARA_STATIC "${YARA_ROOT}/armeabi-v7a/libyara.a")
set(OPENSSL_CRYPTO "${YARA_ROOT}/openssl/armeabi-v7a/libcrypto.a")
if(NOT EXISTS "${YARA_STATIC}")
    message(FATAL_ERROR "libyara.a ARMv7 absent. Run scripts/build-libyara-android-armv7.sh first.")
endif()
if(NOT EXISTS "${OPENSSL_CRYPTO}")
    message(FATAL_ERROR "libcrypto.a ARMv7 absent. Run scripts/build-libyara-android-armv7.sh first.")
endif()

add_library(yara_static STATIC IMPORTED)
set_target_properties(yara_static PROPERTIES IMPORTED_LOCATION "${YARA_STATIC}")

add_library(crypto_static STATIC IMPORTED)
set_target_properties(crypto_static PROPERTIES IMPORTED_LOCATION "${OPENSSL_CRYPTO}")

add_library(ikar_yara SHARED ikar_yara_jni.cpp)
target_include_directories(ikar_yara PRIVATE "${YARA_ROOT}/include")
target_compile_features(ikar_yara PRIVATE cxx_std_17)
target_compile_options(ikar_yara PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(ikar_yara PRIVATE yara_static crypto_static log m dl)
