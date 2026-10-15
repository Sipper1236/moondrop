# Moondrop

by rayyan

A small, independent Java desktop client for [Shadowmere](https://shadowmere.xyz/) community Shadowsocks servers. Dark navy, warm text, mint accents, country flags, one list, one connection. Not an official Shadowmere release.

![Moondrop with country flags and Quick Connect](docs/preview.png)

## Install

Install **Java 17 or newer** separately (Java 21 LTS recommended), then download [Moondrop.zip](https://github.com/Sipper1236/moondrop/releases/tag/v0.1.0), extract it, and open the launcher for your operating system. **Java is not bundled with Moondrop.**

[Step-by-step Java and Moondrop installation for Windows, macOS, and Linux](INSTALL.md) covers installers, package-manager commands, launching, and troubleshooting. You do not need Maven to run a download.

## Use

1. Open Moondrop. The app loads the current active server list.
2. Click **Quick Connect**, or select a server and click **Connect selected server**.
3. On first connection, the app automatically downloads sing-box 1.14.3 for your OS and CPU from SagerNet's official GitHub release and checks its pinned SHA-256 checksum. **Install engine** also lets you prepare it ahead of time.
4. With **Use as system proxy** enabled, compatible macOS/Windows apps use the connection. Without it, configure an app to use HTTP or SOCKS5 at `127.0.0.1:17890`.
5. **Disconnect** restores the previous system proxy settings and stops this app's engine.

This is an application proxy, not a VPN or TUN tunnel. Apps that ignore system proxy settings are not covered. macOS may require network-settings permission. On Windows this configures the current user's Internet Settings, not WinHTTP. Corporate policy, automatic discovery, and applications with their own proxy settings can override it. Local mode never modifies OS proxy settings. Public proxy operators control their servers; availability and trust vary. HTTPS certificate validation stays enabled.

## Quick Connect

Checks TCP responsiveness across the loaded list with bounded parallelism, then measures an actual HTTPS request through up to 20 of the most responsive Shadowsocks endpoints. It picks the lowest measured HTTPS time among successful tests and verifies the selected connection again before marking it connected. This means **fastest tested**, not a guaranteed globally fastest server or a bandwidth benchmark. Measurements use Google's HTTPS connectivity endpoint; its availability can affect results. Cancel stops the search and closes its temporary engine. A refresh discovers new servers; there is no background subscription polling or silent server switching.

## Run from source

Requires JDK 17+ and Maven 3.9+:

```sh
mvn package
java -jar target/moondrop.jar
```

The runnable JAR works on macOS (Apple Silicon and Intel), Windows (x64 and ARM64), and Linux x64. OS proxy automation is macOS/Windows only. The first engine download requires internet access. No accounts or credentials to type.

## Portable downloads / packaging

The same **Moondrop.zip** works on macOS (Apple Silicon and Intel), Windows (x64 and ARM64), and Linux x64. It includes `moondrop.jar`, platform launchers, instructions, and licenses. It contains **no Java runtime**. The standalone JAR is also available on the [release page](https://github.com/Sipper1236/moondrop/releases/tag/v0.1.0).

Developers can build the ZIP using JDK 17+ and Maven 3.9+:

```sh
bash scripts/package.sh      # macOS / Linux
```

```powershell
./scripts/package.ps1        # Windows
```

Output: `dist/Moondrop.zip`. Packaging uses an explicit file list, so local engine downloads, state, and runtimes stay out of the archive. The GitHub Actions workflow builds on Linux, macOS Apple Silicon, macOS Intel, and Windows, and tests the native networking engine. The engine downloads separately on first connection.

## Verification

```sh
mvn test
mvn -Dengine.test.path=/absolute/path/to/sing-box test
java -jar target/moondrop.jar --install-engine
java -jar target/moondrop.jar --smoke-test
```

The integration test runs a real local Shadowsocks server and client, verifies an encrypted HTTP round trip, and checks child-process/config cleanup. The smoke test fetches live servers, runs Quick Connect, verifies HTTPS through the winning server, then disconnects. Neither changes system proxy settings. Isolate data with `-Dshadowmere.dataDir=/temporary/folder` before `-jar`.

Native macOS/Windows system-proxy behavior must be acceptance-tested on those platforms, including an existing manual proxy, PAC configuration, disconnect, engine failure, forced quit, and recovery at next launch. Linux validation does not substitute for those checks.

## State and recovery

- macOS: `~/Library/Application Support/Shadowmere`
- Windows: `%LOCALAPPDATA%/Shadowmere`
- Linux: `~/.local/share/shadowmere-desktop`

Moondrop keeps the original Shadowmere-named data folders for compatibility and recovery across the rename. Only one app instance can own the proxy settings. A durable snapshot is written before system settings change and removed after restoration. An interrupted session is recovered on next launch. A force-killed app can leave system proxy settings enabled until then; reopen Moondrop to restore them. Startup terminates leftover cores only when a private ownership record matches their PID, start time, and executable; session files must belong to this app's data directory. If settings recovery fails, the snapshot is retained and a live listener is kept available while you retry Disconnect. Server passwords exist only in memory and temporary session configuration files, removed on disconnect and after startup recovery. No browsing history or server list is persisted.

## Architecture and credits

Bundled SVG flags render consistently without requiring emoji fonts. Java 17 + Swing/[FlatLaf](https://www.formdev.com/flatlaf/), Gson, Apache Commons Compress. The UI stays on the event thread; feed requests, engine installation, and connection work run off-thread. A single sing-box process provides the loopback HTTP/SOCKS endpoint and Shadowsocks encryption. The benchmark uses one separate temporary core with isolated routing per candidate.

Shadowmere's [API implementation](https://github.com/shadowmere-xyz/shadowmere/blob/main/proxylist/views.py) supplies active server records; [SagerNet sing-box](https://github.com/SagerNet/sing-box) supplies the networking core. This project's code is GPL-3.0-or-later; dependencies retain their own licenses. See `THIRD_PARTY.md`.
