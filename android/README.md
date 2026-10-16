# Moondrop for Android

A native Java Android client with a dark mint interface, cached server lists, protocol filters, and Quick Connect. Android 8.0 or newer is required. Java does not need to be installed on the phone.

The VPN service uses sing-box 1.14.3, built from [pinned upstream source](https://github.com/SagerNet/sing-box/tree/7054cac5124465ffdb145870d7c01885566711b1). It supports encrypted Shadowsocks, TLS/REALITY VLESS, Trojan, Hysteria 2, and TUIC. Unencrypted proxies are excluded. Connection labels describe configured protocol features and do not guarantee censorship bypass.

## Use

1. Install **Moondrop-0.1.0-Android.apk** from the [Android release](https://github.com/Sipper1236/moondrop/releases/tag/android-v0.1.0) on your phone. Allow installation from your browser or file manager if Android asks.
2. Open Moondrop. Saved servers appear first; feeds refresh in the background.
3. Tap **Connect** and approve Android’s VPN connection request. The button becomes **Cancel** during selection and **Disconnect** once connected.
4. Select a country/server for a specific location. Settings contains optional **Advanced mode** and protocol switches.

Saved profiles appear immediately after the first successful feed refresh; a fresh installation needs internet to load its initial list. Completed quality measurements are reused for seven days. Quick Connect verifies the encrypted HTTPS path before starting the VPN. Cold selection compares working candidates; automatic testing is bounded to avoid scanning the entire public catalog in one pass.

Android’s VPN covers other apps. Moondrop itself is excluded to keep the networking engine and its feed requests from looping through its own tunnel. Browsing DNS uses encrypted DNS through the selected proxy; resolving a proxy server’s hostname uses the underlying Android network. Server profiles and credentials stay in private app storage and are excluded from Android backups and diagnostics. Disconnect restores ordinary network routing.

## Build

Requirements: JDK 17, Go 1.25.5+, Android SDK platform 36, SDK Build Tools 35.0.0, and NDK 28.0.13004108.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.0.13004108"
bash android/native/build.sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

The wrapper pins Gradle 8.11.1 with its official distribution checksum. The networking library is compiled from source using pinned SagerNet gomobile tools. Its generated AAR is a build output and must not be committed. `assembleRelease` produces an unsigned APK; distribution builds must be signed with the same private Android signing key for updates to install over earlier releases. Never commit a signing key or password.

The Android workflow builds ARM64, ARMv7, and x86_64 native libraries, checks the Java backend, and produces APK artifacts. Runtime tests exercise actual encrypted loopback traffic and engine cleanup. The controlled emulator VPN test sends traffic from a separate app UID through the real TUN adapter to an encrypted Shadowsocks fixture. Run `bash android/scripts/test-device.sh /path/to/sing-box` with a running API 35 emulator. Wi-Fi/cellular handovers and device-specific battery restrictions still need physical-device testing.

## Source and credits

Moondrop and its Android bridge are GPL-3.0-or-later; see [LICENSE](../LICENSE). The bridge links the official GPL sing-box core, whose version, source commit, feature tags, and build commands are recorded in [native/build.sh](native/build.sh). The Java parser and server model are shared with the desktop app. See [THIRD_PARTY.md](../THIRD_PARTY.md) and upstream license notices for dependency licenses. The Android service integration follows the official [libbox platform interface](https://github.com/SagerNet/sing-box/blob/v1.14.3/experimental/libbox/platform.go).
