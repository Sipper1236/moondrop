#!/usr/bin/env bash
set -euo pipefail

# Build our own binding from pinned upstream source, never a third-party APK.
# Requires Go toolchain 1.26.9, JDK 17, Android SDK and NDK 28.0.13004108.
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
android_dir="$(cd -- "$script_dir/.." && pwd)"
work_dir="${MOONDROP_NATIVE_WORK_DIR:-$android_dir/.native-build}"
source_dir="$work_dir/sing-box"
upstream_commit=7054cac5124465ffdb145870d7c01885566711b1
mkdir -p "$work_dir" "$android_dir/app/libs"
if [[ ! -d "$source_dir/.git" ]]; then
  git clone --depth 1 --branch v1.14.3 https://github.com/SagerNet/sing-box.git "$source_dir"
fi
if [[ "$(git -C "$source_dir" rev-parse HEAD)" != "$upstream_commit" ]]; then
  echo 'Refusing unpinned sing-box source; remove the native build directory and retry.' >&2
  exit 1
fi
export GOTOOLCHAIN=go1.26.9
"$android_dir/../scripts/engine-security-patch.sh" "$source_dir"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK installation}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.0.13004108}"
export GOBIN="$work_dir/bin"
mkdir -p "$GOBIN" "$source_dir/moondropnative"
cp "$script_dir/bridge.go" "$source_dir/moondropnative/bridge.go"
cp "$script_dir/bridge_test.go" "$source_dir/moondropnative/bridge_test.go"
cd "$source_dir"
go test -ldflags=-checklinkname=0 -tags=with_gvisor,with_quic,with_utls,badlinkname,tfogo_checklinkname0 ./moondropnative
go install golang.org/x/vuln/cmd/govulncheck@v1.9.0
"$GOBIN/govulncheck" -tags=with_gvisor,with_quic,with_utls,badlinkname,tfogo_checklinkname0 ./moondropnative
go list -deps -tags=with_gvisor,with_quic,with_utls,badlinkname,tfogo_checklinkname0 \
  -f '{{if .Module}}{{.ImportPath}}@{{.Module.Version}}{{end}}' ./moondropnative \
  | sed '/^$/d' | sort -u > "$android_dir/app/libs/moondrop-engine.packages.txt"
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.12
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.12
export PATH="$GOBIN:$PATH"
gomobile bind -v -target="${MOONDROP_ANDROID_TARGETS:-android/arm64,android/arm,android/amd64}" \
  -androidapi=26 -javapkg=xyz.moondrop -libname=moondrop \
  -trimpath -buildvcs=false \
  -ldflags='-s -w -checklinkname=0 -X github.com/sagernet/sing-box/constant.Version=1.14.3-moondrop-security.1' \
  -tags=with_gvisor,with_quic,with_utls,badlinkname,tfogo_checklinkname0 \
  -o "$android_dir/app/libs/moondrop-engine.aar" ./moondropnative
