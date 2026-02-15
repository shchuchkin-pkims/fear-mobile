# F.E.A.R. - Fully Encrypted Anonymous Routing

<div align="center">

![F.E.A.R. Project](./doc/images/banner_small.png)

**Privacy-focused secure messaging platform with end-to-end encryption**

*Бояться - это нормально...*

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)]()
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blue.svg)](https://kotlinlang.org)

[Features](#features) • [Quick Start](#quick-start) • [Desktop Version](#desktop-version)

</div>

---

## Overview

**F.E.A.R. Messenger** is the Android client for the F.E.A.R. secure messaging platform. Fully compatible with the [desktop server and clients](https://github.com/shchuchkin-pkims/fear) — supports encrypted text, audio calls, video calls, ECDH key exchange, identity verification, and file transfer.

## Features

- **End-to-end encrypted messaging** — AES-256-GCM with Lazysodium; server never sees plaintext
- **Encrypted audio calls** — Opus codec via JNI, AES-256-GCM over UDP
- **Encrypted video calls** — Compatible with desktop VP8 video calls
- **ECDH key exchange** — Create rooms with auto-generated keys or join via X25519 exchange
- **Identity verification** — Ed25519 keypairs with Trust On First Use (TOFU) model
- **File transfer** — Encrypted file sharing with CRC32 integrity checks
- **Push notifications** — Notifications for new messages when app is in background
- **Light and dark themes** — Toggle between light and dark UI from the menu
- **Recent hosts** — Dropdown with recently used server addresses
- **Foreground service** — Persistent connection even when screen is locked
- **Cross-platform** — Full compatibility with desktop F.E.A.R. server and clients

## Requirements

- Android 7.0+ (API 24)
- Permissions: Internet, Microphone, Camera, Notifications

## Quick Start

### Install from APK

1. Download the latest release from [Releases](https://github.com/shchuchkin-pkims/fear-mobile/releases)
2. Install the APK on your device
3. Grant requested permissions

### Build from Source

```bash
git clone https://github.com/shchuchkin-pkims/fear-mobile.git
cd fear-mobile
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

See [BUILD.md](BUILD.md) for detailed build instructions.

### Connect

1. Start a F.E.A.R. server on your PC (see [desktop version](https://github.com/shchuchkin-pkims/fear))
2. Open the app and enter:
   - **Server Host** — server IP address
   - **Port** — server port
   - **Room** — room name
   - **Your Name** — your username
   - **Room Key** — encryption key (Base64)
3. Press **Connect**

Or use **Create Room** to auto-generate a key, or leave the key field empty and press **Connect** to join via ECDH key exchange.

## Usage

### Connection Modes

- **Create Room** — generates a random encryption key and starts a new room
- **Join Room** — connects with an empty key field; ECDH key exchange happens automatically
- **Connect** — connects with a manually entered key

### Chat

- Type a message and press **Send**
- Send files via `/sendfile filename` or the attachment button
- Messages are encrypted with AES-256-GCM before transmission

### Audio Calls

1. Connect to a room
2. Tap the **Audio Call** button
3. Both parties must be in the same room
4. Tap **End Call** to finish

### Video Calls

Compatible with desktop video calls. Start from the call menu in the room.

### Themes

Toggle between light and dark themes from the hamburger menu (top-left).

### Push Notifications

When the app is in the background or the screen is locked, new messages trigger push notifications. Tap a notification to return to the chat.

### Identity Verification

On first connection to a peer, their Ed25519 public key is saved locally. On subsequent connections, keys are compared. A mismatch triggers a security warning. Manage trusted keys from the menu.

## Architecture

```
app/src/main/java/com/fear/
├── MainActivity.kt         # Main screen: connection form, chat, menu
├── FearClient.kt           # TCP client, encryption, ECDH, identity
├── AudioCallManager.kt     # Audio call manager (Opus via JNI, UDP)
├── AudioCallService.kt     # Foreground service for audio calls
├── ChatService.kt          # Foreground service for chat connection
├── MessageNotifier.kt      # Push notification handler
├── IdentityManager.kt      # Ed25519 identity and trusted keys
├── ThemeManager.kt         # Light/dark theme persistence
├── TrustedKeysActivity.kt  # Trusted keys management screen
├── OpusCodec.kt            # JNI wrapper for Opus codec
├── Crypto.kt               # AES-256-GCM encryption
├── Common.kt               # Constants and utilities
└── Message.kt              # Data classes

app/src/main/cpp/
├── opus_jni.cpp            # JNI implementation for Opus
└── opus/                   # Prebuilt Opus libraries (arm64, armv7, x86, x86_64)
```

## Protocol Compatibility

The Android app uses the same binary protocol as the desktop version:

| Channel | Format |
|---------|--------|
| TCP chat | `[2 room_len][room][2 name_len][name][2 nonce_len][nonce][1 type][4 clen][cipher]` (LE) |
| UDP audio | `[0x01][seq(8 BE)][AES-GCM(opus) + 16-byte tag]` |
| ECDH | MSG_TYPE_KEY_REQUEST (15) / MSG_TYPE_KEY_RESPONSE (16) |
| Identity | Ed25519 signatures on ECDH responses |

## Development

### Requirements

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17+
- Android SDK 34, NDK 25.1+, CMake 3.22.1+

### Debug

```bash
# Build and install
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# View logs
adb logcat -s "ACM_DEBUG" "FC_DEBUG" "MainActivity" "OpusJNI"
```

## Desktop Version

The desktop F.E.A.R. application (Linux/Windows) with GUI and CLI is available at:
**[github.com/shchuchkin-pkims/fear](https://github.com/shchuchkin-pkims/fear)**

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/name`
3. Make changes and test
4. Open a Pull Request

## License

MIT License — see [LICENSE](LICENSE) for details.

## Acknowledgments

- **[Lazysodium](https://github.com/nicksulker/lazysodium-android)** — libsodium bindings for Android
- **[Opus](https://opus-codec.org/)** — Xiph.Org Foundation
- **[Kotlin](https://kotlinlang.org/)** — JetBrains

---

<div align="center">

**Stay Anonymous. Stay Secure.**

Made by Shchuchkin E. Yu. and the F.E.A.R. Project community

</div>
