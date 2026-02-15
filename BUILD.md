# Build Instructions — F.E.A.R. Android

## Quick Start

```bash
git clone https://github.com/shchuchkin-pkims/fear-mobile.git
cd fear-mobile
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

---

## Requirements

### Software

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17+ | [Adoptium](https://adoptium.net/) or Oracle |
| Android Studio | Hedgehog+ | [developer.android.com](https://developer.android.com/studio) |
| Android SDK | API 24-34 | Via SDK Manager |
| NDK | 25.1+ | Via SDK Manager (for Opus JNI) |
| CMake | 3.22.1+ | Via SDK Manager |

### SDK Components

Open Android Studio > Settings > SDK Manager and install:

**SDK Platforms:**
- Android 14.0 (API 34)
- Android 7.0 (API 24)

**SDK Tools:**
- Android SDK Build-Tools 34.0.0
- NDK (Side by side) 25.1+
- CMake 3.22.1+
- Android SDK Command-line Tools

---

## Environment Setup

### Set Environment Variables

**Linux/macOS:**
```bash
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH=$PATH:$ANDROID_HOME/platform-tools
```

**Windows:**
```
ANDROID_HOME = C:\Users\<name>\AppData\Local\Android\Sdk
JAVA_HOME = C:\Program Files\Java\jdk-17
PATH += %ANDROID_HOME%\platform-tools
```

### Verify

```bash
java -version      # 17+
adb version        # Android Debug Bridge
cmake --version    # 3.22.1+
```

---

## Debug Build

### Via Android Studio

1. Open project folder
2. Wait for Gradle sync
3. Build > Make Project (Ctrl+F9)
4. Run > Run 'app' (to install on device)

### Via Command Line

```bash
./gradlew assembleDebug
```

### Install on Device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Release Build

### 1. Create Keystore

```bash
keytool -genkey -v -keystore fear-app.keystore \
  -alias fearapp -keyalg RSA -keysize 2048 -validity 10000
```

Save the keystore and password securely.

### 2. Build Release APK

```bash
./gradlew assembleRelease
```

### 3. Sign APK

```bash
ZIPALIGN=$ANDROID_HOME/build-tools/34.0.0/zipalign
APKSIGNER=$ANDROID_HOME/build-tools/34.0.0/apksigner

$ZIPALIGN -v -p 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  app/build/outputs/apk/release/app-release-aligned.apk

$APKSIGNER sign \
  --ks fear-app.keystore \
  --ks-key-alias fearapp \
  --out app/build/outputs/apk/release/F.E.A.R.Messenger-release.apk \
  app/build/outputs/apk/release/app-release-aligned.apk
```

### 4. Verify Signature

```bash
$APKSIGNER verify --print-certs \
  app/build/outputs/apk/release/F.E.A.R.Messenger-release.apk
```

---

## Opus Library

The project includes prebuilt Opus libraries for all architectures:

```
app/src/main/cpp/opus/
├── include/opus/       # Headers
└── libs/
    ├── arm64-v8a/      # libopus.so
    ├── armeabi-v7a/
    ├── x86/
    └── x86_64/
```

JNI wrapper: `app/src/main/cpp/opus_jni.cpp`

To rebuild Opus from source, download from [opus-codec.org](https://opus-codec.org/) and cross-compile with the Android NDK toolchain.

---

## Troubleshooting

### "SDK location not found"
Create `local.properties` in project root:
```
sdk.dir=/path/to/Android/Sdk
```

### "NDK not configured"
Install NDK via SDK Manager, or add to `local.properties`:
```
ndk.dir=/path/to/Android/Sdk/ndk/25.1.8937393
```

### "INSTALL_FAILED_UPDATE_INCOMPATIBLE"
Uninstall the old version first:
```bash
adb uninstall com.fear
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Native build errors
```bash
./gradlew clean
rm -rf app/.cxx app/build
./gradlew assembleDebug
```

### Gradle sync failed
```bash
rm -rf ~/.gradle/caches
```
Then: File > Invalidate Caches > Invalidate and Restart

---

## Useful Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk

# View app logs
adb logcat -s "ACM_DEBUG" "FC_DEBUG" "MainActivity" "OpusJNI"

# Clear app data
adb shell pm clear com.fear

# Run unit tests
./gradlew test
```
