# Moondrop

by rayyan

A small, independent Java desktop client for encrypted Shadowsocks servers from [Shadowmere](https://shadowmere.xyz/), [GFP](https://github.com/gfpcom/free-proxy-list), [V2rayCollector](https://github.com/MhdiTaheri/V2rayCollector), and [Morpheus](https://github.com/morpheusadam/v2ray-config). Dark navy, warm text, mint accents, country flags, one list, one connection. Not an official Shadowmere release.

![Moondrop with country flags and Quick Connect](docs/preview.png)

## Install

Moondrop runs on Windows (x64/ARM64), macOS (Intel/Apple Silicon), and Linux x64 with **Java 17 or newer**. Java 21 LTS is recommended. Java is installed separately and is **not bundled with Moondrop**. A desktop Java runtime (JRE) is enough; a JDK works too. You do not need Maven or any development tools to run the download.

### 1. Install Java

If `java -version` already reports version 17 or newer, skip to downloading Moondrop.

#### Windows

Open PowerShell or Windows Terminal and run:

```powershell
winget install --exact --id EclipseAdoptium.Temurin.21.JRE
```

Close and reopen the terminal, then run:

```powershell
java -version
```

Prefer an installer? Download [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21), choose **Windows**, package type **JRE**, your CPU architecture (usually **x64**), and the **MSI** installer. Run it and keep **Add to PATH** enabled. Reopen the terminal to check the version. The JRE package is listed in [Microsoft's WinGet catalog](https://github.com/microsoft/winget-pkgs/tree/master/manifests/e/EclipseAdoptium/Temurin/21/JRE). See [Microsoft's Java setup instructions](https://learn.microsoft.com/en-us/windows/dev-environment/java).

#### macOS

**Automatic setup:** download and extract Moondrop below, then double-click `installmacos.command`. It checks for Java 17+ with desktop support. If missing, it downloads the correct Temurin 21 installer for Intel or Apple Silicon, verifies its SHA-256 checksum and macOS package signature, and asks for administrator authorization to install Java separately. It then opens Moondrop. No Homebrew is needed.

For manual installation instead:

Download [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21), choose **macOS**, and install the **PKG**:

- **Apple Silicon** (M1, M2, M3, and newer): choose **aarch64**.
- **Intel Mac**: choose **x64**.

Apple menu → **About This Mac** identifies your chip. If you already use Homebrew, you can instead install [Temurin 21](https://formulae.brew.sh/cask/temurin@21):

```sh
brew install --cask temurin@21
```

Open a new Terminal window and check:

```sh
java -version
```

#### Linux

Install the desktop runtime for your distribution. Do not choose a package with only `headless` in its name: Moondrop needs a graphical desktop.

**Ubuntu 24.04 or newer** ([package](https://packages.ubuntu.com/openjdk-21-jre)):

```sh
sudo apt update
sudo apt install openjdk-21-jre
```

**Debian 12 or newer** ([default runtime](https://packages.debian.org/bookworm/default-jre), Java 17 on Debian 12):

```sh
sudo apt update
sudo apt install default-jre
```

**Fedora** ([Java 21 package](https://packages.fedoraproject.org/pkgs/java-21-openjdk/java-21-openjdk/)):

```sh
sudo dnf install java-21-openjdk
```

If your Fedora release no longer provides Java 21, install its current desktop OpenJDK runtime (Java 17 or newer) or use the [Temurin installer instructions](https://adoptium.net/installation/linux/).

**Arch Linux** ([Java 21 package](https://archlinux.org/packages/extra/x86_64/jre21-openjdk/)):

```sh
sudo pacman -Syu jre21-openjdk
```

Check the installed version:

```sh
java -version
```

### 2. Download Moondrop

1. Open the [Moondrop v0.4.1 release](https://github.com/Sipper1236/moondrop/releases/tag/v0.4.1). This is a prerelease.
2. Under **Assets**, download **Moondrop.zip**. The same archive works on all supported operating systems; it contains Moondrop and launchers, without Java.
3. Extract the entire ZIP. On Windows and macOS, keep that folder for running Moondrop. On Linux, the installer below copies the app to a stable location in your user account. Do not run from inside the ZIP.

You can also download **moondrop.jar** alone and run it with `java -jar moondrop.jar`.

### 3. Open Moondrop

**Windows:** double-click `launch-windows.bat` in the extracted folder. Alternatively, open a terminal in that folder (right-click the folder background → **Open in Terminal**) and run:

```powershell
java -jar .\moondrop.jar
```

**macOS:** first double-click `installmacos.command` to check/install Java and open Moondrop. For later launches, use `launch-macos.command`. If the setup needs executable permission, open Terminal, change into the extracted folder, and run:

```sh
cd "$HOME/Downloads/Moondrop"
chmod +x installmacos.command
./installmacos.command
```

Replace the example folder path with wherever you extracted Moondrop. You can also run `java -jar moondrop.jar` from that folder.

**Linux:** open a terminal in the extracted folder and install the app-menu shortcut:

```sh
chmod +x install-linux.sh
./install-linux.sh
```

Then open **Moondrop** from your application menu. No `sudo` is needed, and Java stays a separate installation. The installer copies the app to `${XDG_DATA_HOME:-$HOME/.local/share}/moondrop`, creates `~/.local/bin/moondrop`, and installs `moondrop.desktop` and the SVG icon under your user data directory. The stable app folder is writable by your account so automatic updates can replace the JAR.

The repository and ZIP also include `moondrop.desktop` and `moondrop.svg`. The desktop file is a template using `Exec=moondrop` and `Icon=moondrop`; the installer writes a ready-to-use desktop entry with absolute paths.

For a portable launch without installing a shortcut, run `chmod +x launch-linux.sh` followed by `./launch-linux.sh`, or use `java -jar moondrop.jar` from the extracted folder.

### 4. Connect

Moondrop combines encrypted Shadowsocks entries from [Shadowmere](https://shadowmere.xyz/), [GFP](https://github.com/gfpcom/free-proxy-list), [V2rayCollector](https://github.com/MhdiTaheri/V2rayCollector), and [Morpheus](https://github.com/morpheusadam/v2ray-config). Only their Shadowsocks feeds are imported: HTTP, SOCKS, VLESS, VMess, Trojan, unencrypted entries, and unsupported plugins/ciphers are excluded. Identical connections are deduplicated across sources. Rows show the source; search for `GFP`, `V2rayCollector`, `Morpheus`, or `Shadowmere` to narrow the list. If one source fails, the others still load. Country flags reflect source labels when provided; unknown locations remain unknown.

Each server shows verified HTTPS latency in milliseconds; lower is faster. Tests run in the background and the list defaults to **Fastest first**. Successful results are saved for **30 minutes**, and failed checks for **5 minutes**, so reopening or refreshing Moondrop reuses fresh measurements. Hover a result to see when it was measured. Use **Retest speeds** to check again, or **Stop testing** to cancel.

Click **Quick Connect**. Moondrop automatically connects to the fastest tested server that successfully completes its HTTPS checks. Without cached results it samples up to 256 candidates across sources and countries, limits initial address checks to eight seconds, and compares HTTPS latency through up to 20 responsive candidates. It uses a fresh cached ranking when available and verifies the selected connection again. This is the fastest server tested, not a promise about every server or download speed. Failed checks are excluded from Quick Connect until their cache expires or you retest. An HTTPS check confirms that a server works from your current network; it cannot guarantee censorship bypass on another network.

On the first connection, Moondrop downloads its separate **sing-box networking engine** from the official upstream release and verifies its checksum. This is not a Java installation. The first launch and engine download need working internet access.

On **Windows and macOS**, enable **Use as system proxy** to route compatible applications through Moondrop. macOS may ask for permission to change network settings.

On **Linux**, configure your browser or application's proxy manually: choose **HTTP** or **SOCKS5**, host **127.0.0.1**, port **17890**. You can also use these settings in applications on Windows or macOS when system proxy mode is disabled. Moondrop is an application proxy; applications that ignore proxy settings will use their own connection.

Click **Disconnect** when finished. Close Moondrop normally so it can restore any system proxy settings it changed.

### Automatic updates

Moondrop checks this project's GitHub releases in the background at launch and every six hours. A newer JAR downloads automatically and is verified against the release's SHA-256 checksum and application version. A verified staged download is reused, so it is not downloaded again on every launch.

When **Restart to update** appears, click it when convenient. Moondrop restores proxy settings, closes its engines, waits for the app to exit, replaces the JAR, and restarts using your installed Java. It keeps `moondrop.jar.previous` beside the JAR as a backup. Java is never downloaded or bundled by the updater.

**Check for updates** retries manually. An unavailable update service does not block server browsing or connections. Updates require a writable app folder; move the extracted Moondrop folder somewhere you own if needed. Updates follow this project's published preview releases as well as regular releases. Older releases without the updater must be upgraded manually once using the download above.

If an update cannot install or launch, the helper keeps/restores the previous app. Logs are in the `updates` subfolder of Moondrop's data folder listed below. You can also close Moondrop and rename `moondrop.jar.previous` back to `moondrop.jar` for manual rollback.

### If it will not open

- **“Java not found” / “java is not recognized”:** install Java above, close and reopen your terminal, then check `java -version`. On Windows, make sure the installer added Java to PATH.
- **Java 8 or `UnsupportedClassVersionError`:** the launcher is finding an older Java installation. Install Java 17+ and make sure `java -version` reports the newer version before trying again.
- **Double-clicking the JAR does nothing:** use the platform launcher or `java -jar moondrop.jar` in a terminal. A JAR file association is not required.
- **Cannot find `moondrop.jar`:** extract the whole ZIP and keep the launcher beside the JAR. Run the command from that folder.
- **No display / `HeadlessException` on Linux:** use a graphical desktop session and install the desktop JRE listed above.
- **First connection fails:** check that your internet connection can reach GitHub for the engine download, then retry. Public servers can also go offline; refresh the list and try Quick Connect again.
- **Connection was interrupted and the system proxy is stuck:** reopen Moondrop and use **Disconnect** so it can recover the saved settings.

### Remove Moondrop

Disconnect and close the app, then delete the extracted Moondrop folder. For a Linux app-menu installation, also remove these files and folders (replace the data directory if you set `XDG_DATA_HOME`):

- `~/.local/share/moondrop`
- `~/.local/share/applications/moondrop.desktop`
- `~/.local/share/icons/hicolor/scalable/apps/moondrop.svg`
- `~/.local/bin/moondrop`

Java is a separate installation and stays installed for other applications.

If you also want to remove Moondrop's downloaded engine and saved state, delete its data folder only after disconnecting successfully:

- Windows: `%LOCALAPPDATA%\Shadowmere`
- macOS: `~/Library/Application Support/Shadowmere`
- Linux: `~/.local/share/shadowmere-desktop`

These older Shadowmere folder names are retained for compatibility and proxy recovery.

## Use

1. Open Moondrop. The app loads the current active server list.
2. Click **Quick Connect**, or select a server and click **Connect selected server**.
3. On first connection, the app automatically downloads sing-box 1.14.3 for your OS and CPU from SagerNet's official GitHub release and checks its pinned SHA-256 checksum. **Install engine** also lets you prepare it ahead of time.
4. With **Use as system proxy** enabled, compatible macOS/Windows apps use the connection. Without it, configure an app to use HTTP or SOCKS5 at `127.0.0.1:17890`.
5. **Disconnect** restores the previous system proxy settings and stops this app's engine.

This is an application proxy, not a VPN or TUN tunnel. Apps that ignore system proxy settings are not covered. macOS may require network-settings permission. On Windows this configures the current user's Internet Settings, not WinHTTP. Corporate policy, automatic discovery, and applications with their own proxy settings can override it. Local mode never modifies OS proxy settings. Public proxy operators control their servers; availability and trust vary. HTTPS certificate validation stays enabled.

## Quick Connect

Each server row shows measured HTTPS latency, with **Fastest first** sorting. Background testing keeps the list usable. Results are cached to disk for 30 minutes (failed checks: 5 minutes); Quick Connect reuses fresh rankings and verifies the chosen connection again. **Retest speeds** bypasses the cache. These are latency measurements, not bandwidth estimates.

When no fresh cached server succeeds, Quick Connect samples up to 256 candidates across sources and countries, spends up to eight seconds checking TCP responsiveness, then measures an actual HTTPS request through up to 20 responsive Shadowsocks endpoints. Both stages keep sources represented so a large feed cannot crowd out smaller ones. It picks the lowest measured HTTPS time among successful tests and verifies the selected connection again before marking it connected. This means **fastest tested**, not a guaranteed globally fastest server or a bandwidth benchmark. Measurements use Google's HTTPS connectivity endpoint; its availability can affect results. Cancel stops the search and closes its temporary engine. A refresh discovers new servers; there is no background subscription polling or silent server switching.

## Run from source

Requires JDK 17+ and Maven 3.9+:

```sh
mvn package
java -jar target/moondrop.jar
```

The runnable JAR works on macOS (Apple Silicon and Intel), Windows (x64 and ARM64), and Linux x64. OS proxy automation is macOS/Windows only. The first engine download requires internet access. No accounts or credentials to type.

## Portable downloads / packaging

The same **Moondrop.zip** works on macOS (Apple Silicon and Intel), Windows (x64 and ARM64), and Linux x64. It includes `moondrop.jar`, platform launchers, the Linux user installer, `moondrop.desktop`, `moondrop.svg`, instructions, and licenses. It contains **no Java runtime**. The standalone JAR is also available on the [release page](https://github.com/Sipper1236/moondrop/releases/tag/v0.4.1).

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

Moondrop keeps the original Shadowmere-named data folders for compatibility and recovery across the rename. Only one app instance can own the proxy settings. A durable snapshot is written before system settings change and removed after restoration. An interrupted session is recovered on next launch. A force-killed app can leave system proxy settings enabled until then; reopen Moondrop to restore them. Startup terminates leftover cores only when a private ownership record matches their PID, start time, and executable; session files must belong to this app's data directory. If settings recovery fails, the snapshot is retained and a live listener is kept available while you retry Disconnect. Server passwords exist only in memory and temporary session configuration files, removed on disconnect and after startup recovery. Latency measurements and hashed server identifiers are persisted in `server-performance.json`; passwords are not stored in that cache. No browsing history or server list is persisted.

## Architecture and credits

Bundled SVG flags render consistently without requiring emoji fonts. Java 17 + Swing/[FlatLaf](https://www.formdev.com/flatlaf/), Gson, Apache Commons Compress. The UI stays on the event thread; feed requests, engine installation, and connection work run off-thread. A single sing-box process provides the loopback HTTP/SOCKS endpoint and Shadowsocks encryption. The benchmark uses one separate temporary core with isolated routing per candidate.

Shadowmere's [API implementation](https://github.com/shadowmere-xyz/shadowmere/blob/main/proxylist/views.py) supplies active server records; [SagerNet sing-box](https://github.com/SagerNet/sing-box) supplies the networking core. This project's code is GPL-3.0-or-later; dependencies retain their own licenses. See `THIRD_PARTY.md`.
