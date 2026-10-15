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

1. Open the [Moondrop v0.1.0 release](https://github.com/Sipper1236/moondrop/releases/tag/v0.1.0). This is a prerelease.
2. Under **Assets**, download **Moondrop.zip**. The same archive works on all supported operating systems; it contains Moondrop and launchers, without Java.
3. Extract the entire ZIP into a folder you want to keep. Run Moondrop from the extracted folder, not from inside the ZIP.

You can also download **moondrop.jar** alone and run it with `java -jar moondrop.jar`.

## 3. Open Moondrop

**Windows:** double-click `launch-windows.bat` in the extracted folder. Alternatively, open a terminal in that folder (right-click the folder background → **Open in Terminal**) and run:

```powershell
java -jar .\moondrop.jar
```

**macOS:** double-click `launch-macos.command`. If it needs executable permission, open Terminal, change into the extracted folder, and run:

```sh
cd "$HOME/Downloads/Moondrop"
chmod +x launch-macos.command
./launch-macos.command
```

Replace the example folder path with wherever you extracted Moondrop. You can also run `java -jar moondrop.jar` from that folder.

**Linux:** open a terminal in the extracted folder and run:

```sh
chmod +x launch-linux.sh
./launch-linux.sh
```

Or run `java -jar moondrop.jar`.

## 4. Connect

Click **Quick Connect**. Moondrop tests servers and automatically connects to the fastest one that successfully completes its HTTPS checks. This is the fastest server tested, not a promise about every server or download speed.

On the first connection, Moondrop downloads its separate **sing-box networking engine** from the official upstream release and verifies its checksum. This is not a Java installation. The first launch and engine download need working internet access.

On **Windows and macOS**, enable **Use as system proxy** to route compatible applications through Moondrop. macOS may ask for permission to change network settings.

On **Linux**, configure your browser or application's proxy manually: choose **HTTP** or **SOCKS5**, host **127.0.0.1**, port **17890**. You can also use these settings in applications on Windows or macOS when system proxy mode is disabled. Moondrop is an application proxy; applications that ignore proxy settings will use their own connection.

Click **Disconnect** when finished. Close Moondrop normally so it can restore any system proxy settings it changed.

## If it will not open

- **“Java not found” / “java is not recognized”:** install Java above, close and reopen your terminal, then check `java -version`. On Windows, make sure the installer added Java to PATH.
- **Java 8 or `UnsupportedClassVersionError`:** the launcher is finding an older Java installation. Install Java 17+ and make sure `java -version` reports the newer version before trying again.
- **Double-clicking the JAR does nothing:** use the platform launcher or `java -jar moondrop.jar` in a terminal. A JAR file association is not required.
- **Cannot find `moondrop.jar`:** extract the whole ZIP and keep the launcher beside the JAR. Run the command from that folder.
- **No display / `HeadlessException` on Linux:** use a graphical desktop session and install the desktop JRE listed above.
- **First connection fails:** check that your internet connection can reach GitHub for the engine download, then retry. Public servers can also go offline; refresh the list and try Quick Connect again.
- **Connection was interrupted and the system proxy is stuck:** reopen Moondrop and use **Disconnect** so it can recover the saved settings.

## Remove Moondrop

Disconnect and close the app, then delete the extracted Moondrop folder. Java is a separate installation and stays installed for other applications.

If you also want to remove Moondrop's downloaded engine and saved state, delete its data folder only after disconnecting successfully:

- Windows: `%LOCALAPPDATA%\Shadowmere`
- macOS: `~/Library/Application Support/Shadowmere`
- Linux: `~/.local/share/shadowmere-desktop`

These older Shadowmere folder names are retained for compatibility and proxy recovery.
