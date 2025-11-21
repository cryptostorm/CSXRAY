#!/usr/bin/env bash
set -euo pipefail
: "${XRAY_CORE_DIR:?Set XRAY_CORE_DIR to your Xray-core checkout}"
cd "$XRAY_CORE_DIR"

# ──────────────────────────────────────────────────────────────────
# 1 – build the arch-dependent libxray.so
# ──────────────────────────────────────────────────────────────────
build_core() {
    local GOARCH=$1 CC=$2 OUT=$3
    GOOS=android GOARCH=$GOARCH CC=$CC CGO_ENABLED=1 \
        go build -tags "android" -buildmode=c-shared -o "$OUT/libxray.so" xray_wrapper.go
}

build_core amd64 x86_64-linux-android21-clang  x86_64
build_core arm64 aarch64-linux-android21-clang arm64-v8a

# ──────────────────────────────────────────────────────────────────
# 2 – build JNI shim
#     (xray_jni.c)
# ──────────────────────────────────────────────────────────────────
build_jni() {
    local CC=$1 ABI=$2
    $CC -shared -fPIC \
        -I"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include" \
        -L"./$ABI" -lxray -llog -landroid \
        -o "$ABI/libxray_jni.so" xray_jni.c
}

build_jni aarch64-linux-android21-clang  arm64-v8a
build_jni x86_64-linux-android21-clang   x86_64

# ──────────────────────────────────────────────────────────────────
# 3 – show built files
# ──────────────────────────────────────────────────────────────────
ls -l arm64-v8a/* x86_64/*