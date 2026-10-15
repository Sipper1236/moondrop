#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v mvn >/dev/null || { echo 'Building requires Maven and a JDK 17+.' >&2; exit 1; }
mvn -B package
java --class-path target/moondrop.jar scripts/PortablePackage.java
echo 'Created dist/Moondrop.zip. Users install Java separately; no runtime is included.'
