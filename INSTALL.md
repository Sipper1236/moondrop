# Install Moondrop

Moondrop runs on Windows (x64/ARM64), macOS (Intel/Apple Silicon), and Linux x64 with **Java 17 or newer**. Java 21 LTS is recommended. Java is installed separately and is **not bundled with Moondrop**. A desktop Java runtime (JRE) is enough; a JDK works too. You do not need Maven or any development tools to run the download.

## 1. Install Java

If `java -version` already reports version 17 or newer, skip to downloading Moondrop.

### Windows

Open PowerShell or Windows Terminal and run:

```powershell
winget install --exact --id EclipseAdoptium.Temurin.21.JRE
```

Close and reopen the terminal, then run:

```powershell
java -version
```

Prefer an installer? Download [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21), choose **Windows**, package type **JRE**, your CPU architecture (usually **x64**), and the **MSI** installer. Run it and keep **Add to PATH** enabled. Reopen the terminal to check the version. The JRE package is listed in [Microsoft's WinGet catalog](https://github.com/microsoft/winget-pkgs/tree/master/manifests/e/EclipseAdoptium/Temurin/21/JRE). See [Microsoft's Java setup instructions](https://learn.microsoft.com/en-us/windows/dev-environment/java).

### macOS

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

### Linux

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

## 2. Download Moondrop

1. Open the [Moondrop v0.6.0 release](https://github.com/Sipper1236/moondrop/releases/tag/v0.6.0). This is a prerelease.
2. Under **Assets**, download **Moondrop.zip**. The same archive works on all supported operating systems; it contains Moondrop and launchers, without Java.
3. Extract the entire ZIP. On Windows and macOS, keep that folder for running Moondrop. On Linux, the installer below copies the app to a stable location in your user account. Do not run from inside the ZIP.

You can also download **moondrop.jar** alone and run it with `java -jar moondrop.jar`.

## 3. Open Moondrop

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

## 4. Connect

New and expired checks run **nearby first**: your approximate country comes from a direct HTTPS lookup at [IPWHOIS](https://ipwhois.io/documentation), cached locally for 24 hours. Only the country code and lookup time are saved. Moondrop compares reported server countries using country-center distances; unknown locations come last. If lookup fails, Moondrop reuses the saved country; without one, testing continues without a distance preference. A VPN or upstream proxy can affect the detected country. To override it, launch with an ISO country code, for example `java -Dmoondrop.country=CA -jar moondrop.jar`. This changes test order; measured reliability, median HTTPS latency, and a bounded download estimate decide the best tested connection.

Moondrop imports protected proxy connections from [Shadowmere](https://shadowmere.xyz/), [GFP](https://github.com/gfpcom/free-proxy-list), [V2rayCollector](https://github.com/MhdiTaheri/V2rayCollector), and [Morpheus](https://github.com/morpheusadam/v2ray-config). Supported profiles are **encrypted Shadowsocks**, **VLESS with verified TLS or REALITY**, **Trojan with TLS**, **Hysteria2 with TLS**, and **TUIC with TLS**. Plain HTTP/SOCKS proxies, plaintext VLESS, profiles disabling TLS verification, VMess, and unsupported plugins, transports, or ciphers are excluded.

The app automatically loads Shadowmere's Shadowsocks feed; GFP's Shadowsocks, Hysteria2, and TUIC feeds; V2rayCollector's Shadowsocks and mixed feeds filtered to supported protected profiles; and Morpheus's Shadowsocks, VLESS, Trojan, Hysteria2, and TUIC bundles. Identical connections are deduplicated across sources. Rows show the source; search for `GFP`, `V2rayCollector`, `Morpheus`, or `Shadowmere` to narrow the list. If one source fails, the others still load. Country flags reflect source labels when provided; unknown locations remain unknown.

For each reachable candidate, Moondrop makes **three HTTPS requests**, shows the median successful latency in milliseconds and the percentage that succeeded. Lower latency and higher reliability are better. Servers that fail the initial TCP check do not receive HTTPS samples. To estimate download speed, it downloads at most **1 MiB through each of the best five servers**, chosen by reliability then median latency: at most **5 MiB per testing pass**, rather than a download through every server. Fresh cached download measurements are skipped. The rate is an end-to-end estimate including TLS setup, not a sustained bandwidth guarantee.

Completed checks, including failures and download estimates, are cached locally for **7 days**. Results are saved as testing progresses, so closing and reopening Moondrop resumes missing checks instead of starting the full scan again. Automatic testing checks only new, changed, or expired servers. Existing working entries with one old HTTPS sample receive a three-request upgrade; the old failure cache stays intact. Older measurements remain visible until replaced. Hover a result to see when it was measured. Use **Stop testing** to pause; completed checks stay saved.

Click **Quick Connect**. Moondrop reuses the cached quality ranking and verifies the selected server again before connecting. Ranking puts complete three-request results first, then reliability, then a score combining median latency with the estimated time to transfer 256 KiB. When download speed is unknown, the score adds one second; a failed download ranks behind other results with the same quality and reliability. Without a usable cached result, Quick Connect samples up to 256 candidates across sources and countries, limits initial address checks to eight seconds, and compares HTTPS results through up to 20 responsive candidates. This is the best connection tested, not a promise about every server or download speed. Failed checks are excluded until they expire or you retest.

The cache reflects **your machine and network**; it is not a shared global ranking. After changing networks or routes, use **Retest speeds** to force a fresh check of every server, including cached successes and failures. A successful HTTPS check confirms that a server works from your current network. Actual censorship bypass depends on the network, server, and protocol configuration; TLS alone does not guarantee it everywhere.

On the first connection, Moondrop downloads its separate **sing-box networking engine** from the official upstream release and verifies its checksum. This is not a Java installation. The first launch and engine download need working internet access.

On **Windows and macOS**, enable **Use as system proxy** to route compatible applications through Moondrop. macOS may ask for permission to change network settings.

On **Linux**, configure your browser or application's proxy manually: choose **HTTP** or **SOCKS5**, host **127.0.0.1**, port **17890**. You can also use these settings in applications on Windows or macOS when system proxy mode is disabled. Moondrop is an application proxy; applications that ignore proxy settings will use their own connection.

Click **Disconnect** when finished. Close Moondrop normally so it can restore any system proxy settings it changed.

## Automatic updates

Moondrop checks this project's GitHub releases in the background at launch and every six hours. A newer JAR downloads automatically and is verified against the release's SHA-256 checksum and application version. A verified staged download is reused, so it is not downloaded again on every launch.

When **Restart to update** appears, click it when convenient. Moondrop restores proxy settings, closes its engines, waits for the app to exit, replaces the JAR, and restarts using your installed Java. It keeps `moondrop.jar.previous` beside the JAR as a backup. Java is never downloaded or bundled by the updater.

**Check for updates** retries manually. An unavailable update service does not block server browsing or connections. Updates require a writable app folder; move the extracted Moondrop folder somewhere you own if needed. Updates follow this project's published preview releases as well as regular releases. Older releases without the updater must be upgraded manually once using the download above.

If an update cannot install or launch, the helper keeps/restores the previous app. Logs are in the `updates` subfolder of Moondrop's data folder listed below. You can also close Moondrop and rename `moondrop.jar.previous` back to `moondrop.jar` for manual rollback.

## If it will not open

- **“Java not found” / “java is not recognized”:** install Java above, close and reopen your terminal, then check `java -version`. On Windows, make sure the installer added Java to PATH.
- **Java 8 or `UnsupportedClassVersionError`:** the launcher is finding an older Java installation. Install Java 17+ and make sure `java -version` reports the newer version before trying again.
- **Double-clicking the JAR does nothing:** use the platform launcher or `java -jar moondrop.jar` in a terminal. A JAR file association is not required.
- **Cannot find `moondrop.jar`:** extract the whole ZIP and keep the launcher beside the JAR. Run the command from that folder.
- **No display / `HeadlessException` on Linux:** use a graphical desktop session and install the desktop JRE listed above.
- **First connection fails:** check that your internet connection can reach GitHub for the engine download, then retry. Public servers can also go offline; refresh the list and try Quick Connect again.
- **Connection was interrupted and the system proxy is stuck:** reopen Moondrop and use **Disconnect** so it can recover the saved settings.

## Remove Moondrop

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
