#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ "$(uname -s)" == Darwin ]] || { echo 'This script requires macOS and a full JDK 21.' >&2; exit 1; }
[[ -f target/moondrop.jar ]] || { echo 'Build target/moondrop.jar before packaging.' >&2; exit 1; }
version=$(/usr/bin/xmllint --xpath 'string(/*[local-name()="project"]/*[local-name()="version"])' pom.xml)
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'pom.xml must have a numeric application version.' >&2; exit 1; }
# jpackage requires a positive leading CFBundleVersion component. Keep the public
# release/JAR version unchanged, with a monotonic +1 major for the Mac build number.
IFS=. read -r major minor patch <<< "$version"
mac_version="$((10#$major+1)).$minor.$patch"
case "$(uname -m)" in arm64) architecture=arm64 ;; x86_64) architecture=x64 ;; *) echo 'Unsupported macOS architecture.' >&2; exit 1 ;; esac
jdk_bin="${JAVA_HOME:?Set JAVA_HOME to a full JDK 21}/bin"
rm -rf -- target/native-runtime target/native-input target/native-icons target/native-image target/native-installer
mkdir -p target/native-input target/native-icons target/native-image target/native-installer dist
cp target/moondrop.jar README.md INSTALL.md LICENSE THIRD_PARTY.md MACOS-TUNNEL.md WINDOWS-TUNNEL.md target/native-input/
"$jdk_bin/jlink" --add-modules java.se,jdk.crypto.ec,jdk.unsupported --strip-debug --no-header-files --no-man-pages --output target/native-runtime
"$jdk_bin/java" -Djava.awt.headless=true --class-path target/moondrop.jar scripts/DesktopIcons.java moondrop.svg target/native-icons
iconset=target/native-icons/moondrop.iconset
mkdir -p "$iconset"
for size in 16 32 128 256 512; do
    /usr/bin/sips -z "$size" "$size" target/native-icons/moondrop.png --out "$iconset/icon_${size}x${size}.png" >/dev/null
    doubled=$((size*2))
    /usr/bin/sips -z "$doubled" "$doubled" target/native-icons/moondrop.png --out "$iconset/icon_${size}x${size}@2x.png" >/dev/null
done
/usr/bin/iconutil -c icns "$iconset" -o target/native-icons/moondrop.icns
"$jdk_bin/jpackage" --type app-image --name Moondrop --app-version "$mac_version" --vendor rayyan \
    --input target/native-input --main-jar moondrop.jar --main-class xyz.shadowmere.desktop.Main \
    --runtime-image target/native-runtime --java-options '-Dmoondrop.distribution=native' \
    --icon target/native-icons/moondrop.icns --mac-package-identifier xyz.moondrop.desktop \
    --mac-app-category public.app-category.utilities --dest target/native-image
"$jdk_bin/jpackage" --type dmg --name Moondrop --app-version "$mac_version" --vendor rayyan \
    --app-image target/native-image/Moondrop.app --mac-package-identifier xyz.moondrop.desktop \
    --mac-app-category public.app-category.utilities --dest target/native-installer
mv -f "target/native-installer/Moondrop-${mac_version}.dmg" "dist/Moondrop-${version}-macOS-${architecture}.dmg"
echo "Created dist/Moondrop-${version}-macOS-${architecture}.dmg (unsigned, with a Java runtime)."
