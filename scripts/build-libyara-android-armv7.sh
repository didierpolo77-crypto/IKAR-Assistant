#!/usr/bin/env bash
# Build official libyara for Android armeabi-v7a; CI uses the current Android command-line tools.
set -euo pipefail

YARA_TAG="${YARA_TAG:-v4.5.0}"
OPENSSL_TAG="${OPENSSL_TAG:-openssl-3.5.9}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_HOME:-}/ndk/27.2.12479018}"
HOST_TAG="${HOST_TAG:-linux-x86_64}"
API="${ANDROID_API:-23}"
ROOT="${PWD}"
WORK="${ROOT}/.build/yara-armv7"
SRC="${WORK}/yara"
OPENSSL_SRC="${WORK}/openssl"
OPENSSL_PREFIX="${WORK}/openssl-install"
PREFIX="${WORK}/install"
TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${HOST_TAG}/bin"

test -x "${TOOLCHAIN}/armv7a-linux-androideabi${API}-clang" || {
  echo "Android NDK ARMv7 clang introuvable : ${TOOLCHAIN}" >&2
  exit 2
}

rm -rf "${WORK}"
mkdir -p "${WORK}"
git clone --depth 1 --branch "${OPENSSL_TAG}" https://github.com/openssl/openssl.git "${OPENSSL_SRC}"
git clone --depth 1 --branch "${YARA_TAG}" https://github.com/VirusTotal/yara.git "${SRC}"

# Build a supported OpenSSL LTS statically for the exact Android ARMv7 ABI.
export ANDROID_NDK_ROOT="${ANDROID_NDK_HOME}"
export PATH="${TOOLCHAIN}:${PATH}"
cd "${OPENSSL_SRC}"
./Configure android-arm -D__ANDROID_API__="${API}" no-shared no-tests no-module --prefix="${OPENSSL_PREFIX}"
make -j2 build_sw
make install_sw
OPENSSL_CRYPTO="${OPENSSL_PREFIX}/lib/libcrypto.a"
test -s "${OPENSSL_CRYPTO}"

cd "${SRC}"
./bootstrap.sh

export CC="${TOOLCHAIN}/armv7a-linux-androideabi${API}-clang"
export CXX="${TOOLCHAIN}/armv7a-linux-androideabi${API}-clang++"
export AR="${TOOLCHAIN}/llvm-ar"
export RANLIB="${TOOLCHAIN}/llvm-ranlib"
export STRIP="${TOOLCHAIN}/llvm-strip"
export CFLAGS="-O2 -fPIC"
export CPPFLAGS="-I${OPENSSL_PREFIX}/include"
export LDFLAGS="-L${OPENSSL_PREFIX}/lib"
export LIBS="-lcrypto -ldl -lm"

./configure \
  --host=arm-linux-androideabi \
  --prefix="${PREFIX}" \
  --disable-shared \
  --enable-static \
  --without-crypto

make -j2
make install

LIB="${PREFIX}/lib/libyara.a"
test -s "${LIB}"
mkdir -p "${ROOT}/artifacts/libyara/armeabi-v7a"
cp "${LIB}" "${ROOT}/artifacts/libyara/armeabi-v7a/libyara.a"
mkdir -p "${ROOT}/artifacts/openssl/armeabi-v7a"
cp "${OPENSSL_CRYPTO}" "${ROOT}/artifacts/openssl/armeabi-v7a/libcrypto.a"
cp -R "${PREFIX}/include/yara" "${ROOT}/artifacts/libyara/"

# Stage the validated ARMv7 archive and public headers for the Android CMake build.
NATIVE_YARA="${ROOT}/app/src/main/cpp/third_party/yara"
mkdir -p "${NATIVE_YARA}/armeabi-v7a" "${NATIVE_YARA}/include" "${NATIVE_YARA}/openssl/armeabi-v7a"
cp "${LIB}" "${NATIVE_YARA}/armeabi-v7a/libyara.a"
cp "${OPENSSL_CRYPTO}" "${NATIVE_YARA}/openssl/armeabi-v7a/libcrypto.a"
cp -R "${PREFIX}/include/yara" "${NATIVE_YARA}/include/"

echo "YARA tag: ${YARA_TAG}"
echo "OpenSSL tag: ${OPENSSL_TAG}"
echo "Android API: ${API}"
echo "ABI: armeabi-v7a"
file "${LIB}"
"${TOOLCHAIN}/llvm-ar" t "${LIB}" | head -n 10
