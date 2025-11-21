# CSXRAY Android client

This repository contains an Android client that controls an Xray-core instance via JNI. It includes:

- A small Android UI for loading a JSON config and starting/stopping Xray.
- A foreground service that manages the native process and status.
- A gRPC stats client for Xray’s StatsService.
- A tiny native wrapper (Go + C/JNI) plus a patch to Xray-core that lets the UI know when the core is active, listening, or stopped.

The actual Xray-core source code is not included here. You are expected to provide your own Xray-core checkout and build the native libraries yourself (see “Building the native libraries” below).

This app is meant to run alongside the official WireGuard app. It connects to the cryptostorm.is VPN/Xray servers, but it also has custom options for those who want to use their own servers.   

See <a href="https://cryptostorm.is/blog/xray" target="_blank">https://cryptostorm.is/blog/xray</a> for more details.

-------------------------------------------------------------------------------

## Repository layout

- android/ – Android Studio project.
  - app/ – Single app module with Kotlin source, resources, and manifest.
- native/ – Native integration for Xray-core (Go wrapper, JNI shim, build script, and patch file).

The app code lives under:

    android/app/src/main/java/com/cryptostorm/xray/

The native integration lives under:

    native/

-------------------------------------------------------------------------------

## Quick code index

For people who just want to skim the important code without digging through the full tree, here are direct links to the main files:

### Android service & UI

- Main Activity (UI + config generator)  
  <a href="./android/app/src/main/java/com/cryptostorm/xray/MainActivity.kt">android/app/src/main/java/com/cryptostorm/xray/MainActivity.kt</a>

- Foreground service (starts/stops Xray, keeps it alive)  
  <a href="./android/app/src/main/java/com/cryptostorm/xray/CSXrayService.kt">android/app/src/main/java/com/cryptostorm/xray/CSXrayService.kt</a>

- JNI bridge (Kotlin ↔ C/JNI layer)  
  <a href="./android/app/src/main/java/com/cryptostorm/xray/XrayBridge.kt">android/app/src/main/java/com/cryptostorm/xray/XrayBridge.kt</a>

- Xray state signals (ACTIVE / LISTENING / STOPPED)  
  <a href="./android/app/src/main/java/com/cryptostorm/xray/XraySignals.kt">android/app/src/main/java/com/cryptostorm/xray/XraySignals.kt</a>

- Stats client (gRPC connection to Xray-core’s StatsService)  
  <a href="./android/app/src/main/java/com/cryptostorm/xray/XrayStatsClient.kt">android/app/src/main/java/com/cryptostorm/xray/XrayStatsClient.kt</a>

### Native side (Go + C/JNI + patch)

- Go wrapper around Xray-core (builds as libxray.so)  
  <a href="./native/xray_wrapper.go">native/xray_wrapper.go</a>

- JNI shim (builds as libxray_jni.so)  
  <a href="./native/xray_jni.c">native/xray_jni.c</a>

- Xray-core patch (signals for ACTIVE / LISTENING / STOPPED)  
  <a href="./native/signals.patch">native/signals.patch</a>

- Build script for native libraries  
  <a href="./native/build.sh">native/build.sh</a>

-------------------------------------------------------------------------------

## Building the Android app

You can build the Android app with Android Studio or with the Gradle wrapper.

### 1. Open in Android Studio

1. Start Android Studio.
2. Choose “Open an existing project”.
3. Select the android/ directory in this repository.
4. Allow Gradle to sync.

### 2. Command-line build (optional)

From the repository root:

    cd android
    ./gradlew assembleDebug

This produces a debug APK under:

    android/app/build/outputs/apk/

-------------------------------------------------------------------------------

## Building the native libraries

The app expects two native libraries per ABI:

- libxray.so – Go wrapper (xray_wrapper.go)
- libxray_jni.so – JNI bridge (xray_jni.c)

Place the built libraries into:

- android/app/src/main/jniLibs/arm64-v8a/
- android/app/src/main/jniLibs/x86_64/

### Prerequisites (tested on Linux)

- Go
- Android NDK
- A local checkout of Xray-core

Example environment setup:

    export ANDROID_NDK_HOME=/path/to/android-ndk
    export XRAY_CORE_DIR=/path/to/Xray-core

### Steps

1. Clone Xray-core:

       git clone https://github.com/XTLS/Xray-core.git "$XRAY_CORE_DIR"

2. Apply the provided patch:

       cd "$XRAY_CORE_DIR"
       git apply /path/to/repo/native/signals.patch

3. Build the native libraries:

       cd /path/to/repo/native
       XRAY_CORE_DIR="$XRAY_CORE_DIR" ./build.sh

4. Copy the outputs into the Android project:

       mkdir -p ../android/app/src/main/jniLibs/arm64-v8a
       mkdir -p ../android/app/src/main/jniLibs/x86_64

       cp arm64-v8a/libxray.so        ../android/app/src/main/jniLibs/arm64-v8a/
       cp arm64-v8a/libxray_jni.so    ../android/app/src/main/jniLibs/arm64-v8a/

       cp x86_64/libxray.so           ../android/app/src/main/jniLibs/x86_64/
       cp x86_64/libxray_jni.so       ../android/app/src/main/jniLibs/x86_64/

5. Rebuild the Android app.
