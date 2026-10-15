#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v mvn >/dev/null || { echo 'Install Maven and a JDK 17+ first.' >&2; exit 1; }
mvn -B package
mkdir -p target/moondrop-package-input dist
cp target/moondrop.jar target/moondrop-package-input/
case "$(uname -s)" in
  Darwin) kind=dmg ;;
  Linux) kind=app-image ;;
  *) echo 'On Windows use scripts/package.ps1.' >&2; exit 1 ;;
esac
jpackage --type "$kind" --name Moondrop --app-version 0.1.0 \
  --vendor 'rayyan' \
  --description 'A minimal Shadowsocks client for Shadowmere community servers' \
  --input target/moondrop-package-input --main-jar moondrop.jar \
  --main-class xyz.shadowmere.desktop.Main --dest dist \
  --add-modules java.base,java.desktop,java.net.http,java.logging,jdk.crypto.ec,jdk.unsupported \
  --java-options '-Dfile.encoding=UTF-8'
echo 'Native application created in dist/ (includes Java runtime).'
