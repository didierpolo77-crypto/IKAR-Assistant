#!/usr/bin/env bash
# Build official libyara for Android armeabi-v7a; CI uses the current Android command-line tools.
set -euo pipefail

YARA_TAG="${YARA_TAG:-v4.5.0}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_HOME:-}/ndk/27.2.12479018}"
HOST_TAG="${HOST_TAG:-linux-x86_64}"
API="${ANDROID_API:-23}"
ROOT="${PWD}"
WORK="${ROOT}/.build/yara-armv7"
SRC="${WORK}/yara"
PREFIX="${WORK}/install"
TOOLCHAIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${HOST_TAG}/bin"

test -x "${TOOLCHAIN}/armv7a-linux-androideabi${API}-clang" || {
  echo "Android NDK ARMv7 clang introuvable : ${TOOLCHAIN}" >&2
  exit 2
}

rm -rf "${WORK}"
mkdir -p "${WORK}"
git clone --depth 1 --branch "${YARA_TAG}" https://github.com/VirusTotal/yara.git "${SRC}"

cd "${SRC}"
./bootstrap.sh

export CC="${TOOLCHAIN}/armv7a-linux-androideabi${API}-clang"
export CXX="${TOOLCHAIN}/armv7a-linux-androideabi${API}-clang++"
export AR="${TOOLCHAIN}/llvm-ar"
export RANLIB="${TOOLCHAIN}/llvm-ranlib"
export STRIP="${TOOLCHAIN}/llvm-strip"
export CFLAGS="-O2 -fPIC"
export CPPFLAGS=""
export LDFLAGS=""

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
cp -R "${PREFIX}/include/yara" "${ROOT}/artifacts/libyara/"

# Stage the validated ARMv7 archive and public headers for the Android CMake build.
NATIVE_YARA="${ROOT}/app/src/main/cpp/third_party/yara"
mkdir -p "${NATIVE_YARA}/armeabi-v7a" "${NATIVE_YARA}/include"
cp "${LIB}" "${NATIVE_YARA}/armeabi-v7a/libyara.a"
cp -R "${PREFIX}/include/yara" "${NATIVE_YARA}/include/"

echo "YARA tag: ${YARA_TAG}"
echo "Android API: ${API}"
echo "ABI: armeabi-v7a"
file "${LIB}"
"${TOOLCHAIN}/llvm-ar" t "${LIB}" | head -n 10
