# Moondrop for Android

A native Java Android client with a dark mint interface, cached server lists, protocol filters, and Quick Connect. Android 8.0 or newer is required. Java does not need to be installed on the phone.

The VPN service uses sing-box 1.14.3, built from [pinned upstream source](https://github.com/SagerNet/sing-box/tree/7054cac5124465ffdb145870d7c01885566711b1). It supports encrypted Shadowsocks, TLS/REALITY VLESS, Trojan, Hysteria 2, and TUIC. Unencrypted proxies are excluded. Connection labels describe configured protocol features and do not guarantee censorship bypass.

## Use

1. Install **Moondrop-0.1.1-Android.apk** from the [Android release](https://github.com/Sipper1236/moondrop/releases/tag/android-v0.1.1) on your phone. Allow installation from your browser or file manager if Android asks.
2. Open Moondrop. Saved servers appear first; feeds refresh in the background.
3. Tap **Connect** and approve Android’s VPN connection request. The button becomes **Cancel** during selection and **Disconnect** once connected.
4. Select a country/server for a specific location. Settings contains optional **Advanced mode** and protocol switches.

Moondrop checks GitHub Releases for Android updates at startup; use the **↓** button to check manually. It verifies the APK checksum, package identity, version, and signing certificate before handing it to Android. Android always asks you to approve installation. Updates only work when the release is signed with the same publisher key as the installed app. The current CI release build is unsigned, and the existing Android publisher key is not configured in this repository, so a future signed release needs that key before in-place updates can install. Android will reject mismatched or unsigned APKs.

Saved profiles appear immediately after the first successful feed refresh; a fresh installation needs internet to load its initial list. Completed quality measurements are reused for seven days. Quick Connect verifies the encrypted HTTPS path before starting the VPN. Cold selection compares working candidates; automatic testing is bounded to avoid scanning the entire public catalog in one pass.

Android’s VPN covers other apps. Moondrop itself is excluded to keep the networking engine and its feed requests from looping through its own tunnel. Browsing DNS uses encrypted DNS through the selected proxy; resolving a proxy server’s hostname uses the underlying Android network. Server profiles and credentials stay in private app storage and are excluded from Android backups and diagnostics. Disconnect restores ordinary network routing.

## Optional privacy controls

Settings includes **Privacy mode**, manual country selection, explicit single-server checks, profile/history erasure, optional two-hop routing, and Android VPN strict-blocking instructions. Ordinary automatic testing remains available when Privacy mode and two-hop routing are off. Operators contacted by that workflow can see your connecting IP.

Privacy mode pauses background scans, reuses cached ranking, and attempts one server after explicit confirmation; failure does not silently contact another operator. Manual country/device-region ordering never contacts an IP geolocation provider. Two-hop mode implements a real sing-box detour, uses a Shadowsocks entry with a Shadowsocks/VLESS/Trojan exit, and does not fall back directly. Independent ownership is not verified and traffic correlation remains possible.

Saved profiles and the last approved pinned VPN configuration use AES-GCM with a non-exportable Android Keystore key. Older plaintext catalogs migrate atomically; unreadable encrypted data is retained until you explicitly erase it. The settings erasure action removes both profile and restoration caches.

For strict blocking, connect successfully first, then open **Device VPN & strict blocking** and enable Android's **Always-on VPN** and **Block connections without VPN**. Moondrop restores only the exact approved route, without searching other servers. Android, rather than an app checkbox alone, enforces the block. Moondrop itself is excluded for transport/feed access; physical-device IPv6/DNS/UDP crash and handover coverage remains a separate validation requirement.

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

Bootstrap on censored networks: saved profiles are available immediately, even
when server feeds cannot be reached. Quick Connect prioritizes a small history
of actual successful direct connections, separately from speed measurements.
These proven entry profiles survive feed refreshes even if they disappear from a
feed; failed attempts demote them for the current session without deleting their
last successful history. After a connection is established,
feed refreshes use a loopback proxy through the active VPN route, including when
Android excludes Moondrop itself from device VPN routing. A protected refresh
never silently falls back to a direct feed request. Background benchmarks and
tests of a chained exit do not mark that exit as a proven direct entry.
Erasing saved profiles also erases this entry history. At least one saved or
imported entry must still be reachable from the underlying network.

Settings → Import server links accepts pasted encrypted public server links
without any network request. Imported profiles survive feed refreshes. Private
network endpoints are intentionally unsupported in this import flow.

Privacy controls are optional in Settings. “Ask before contacting a proxy” can be
enabled independently of Privacy mode: it prompts before connecting, attempts
one cached-ranked server, and pauses background testing without changing your
saved automatic-testing preference. Manual single-server tests still require
confirmation. Preferred country and two-hop entry selection are available in
normal Settings; Advanced mode is only needed for protocol and diagnostic detail.
“Check for updates automatically” defaults to on and can be disabled without
removing the manual update button. Country selection never sends an IP lookup.

Public feed endpoint validation, supported encryption, TLS certificate checks,
encrypted saved credentials, and APK checksum / matching publisher signature
verification remain mandatory. Android’s Always-on VPN and “Block connections
without VPN” controls provide optional operating-system blocking for other apps.
