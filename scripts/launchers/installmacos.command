#!/bin/bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "$0")" && pwd)"
download_dir=""
cleanup() { if [[ -n "$download_dir" ]]; then /bin/rm -rf -- "$download_dir"; fi; }
trap cleanup EXIT
fail() { printf '\n%s\n' "$1" >&2; if [[ -t 0 ]]; then read -r -p 'Press Return to close… ' ignored; fi; exit 1; }
[[ "$(/usr/bin/uname -s)" == Darwin ]] || fail 'installmacos.command is for macOS. Follow INSTALL.md for your operating system.'

java_program=""
compatible_java() {
  local candidate="$1" output version major
  [[ -x "$candidate" ]] || return 1
  output="$("$candidate" -version 2>&1)" || return 1
  version="$(printf '%s\n' "$output" | /usr/bin/sed -n 's/.*version "\([^"]*\)".*/\1/p' | /usr/bin/head -n 1)"
  major="${version%%[.+-]*}"
  [[ "$major" =~ ^[0-9]+$ && "$major" -ge 17 ]] || return 1
  "$candidate" --list-modules 2>/dev/null | /usr/bin/grep '^java.desktop@' >/dev/null || return 1
  java_program="$candidate"
  printf 'Java %s found: %s\n' "$version" "$java_program"
}
find_java() {
  local java_home candidate
  java_home="$(/usr/libexec/java_home -v '17+' 2>/dev/null || true)"
  if [[ -n "$java_home" ]] && compatible_java "$java_home/bin/java"; then return 0; fi
  candidate="$(command -v java || true)"
  # Avoid invoking Apple's placeholder, which displays a Java installation prompt.
  if [[ -n "$candidate" && "$candidate" != /usr/bin/java ]] && compatible_java "$candidate"; then return 0; fi
  return 1
}

printf 'Moondrop by rayyan — macOS setup\n\n'
if ! find_java; then
  if [[ "${1:-}" == --check-java ]]; then fail 'Java 17 or newer with desktop support was not found.'; fi
  [[ -f "$script_dir/moondrop.jar" ]] || fail 'Extract the complete Moondrop.zip first. Keep this installer beside moondrop.jar.'
  architecture="$(/usr/bin/uname -m)"
  # Detect Apple Silicon even when Terminal is running under Rosetta.
  if [[ "$(/usr/sbin/sysctl -in hw.optional.arm64 2>/dev/null || true)" == 1 ]]; then architecture=arm64; fi
  case "$architecture" in arm64) architecture=aarch64 ;; x86_64) architecture=x64 ;; *) fail "Unsupported Mac architecture: $architecture" ;; esac
  download_dir="$(/usr/bin/mktemp -d "${TMPDIR:-/tmp}/moondrop-java.XXXXXX")"
  metadata="$download_dir/temurin.json"
  package="$download_dir/Temurin21.pkg"
  printf 'Java 17+ is missing. Downloading Eclipse Temurin 21 for %s…\n' "$architecture"
  /usr/bin/curl --fail --location --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 45 \
    "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=$architecture&image_type=jdk&os=mac&vendor=eclipse" -o "$metadata" \
    || fail 'Could not fetch Java installer information. Check your internet connection and try again.'
  package_url="$(/usr/bin/plutil -extract 0.binary.installer.link raw -o - "$metadata")" || fail 'The Java installer response was invalid.'
  expected="$(/usr/bin/plutil -extract 0.binary.installer.checksum raw -o - "$metadata")" || fail 'The Java installer checksum is missing.'
  [[ "$package_url" == https://github.com/adoptium/temurin21-binaries/releases/download/*/*.pkg ]] || fail 'The Java installer URL is not an official Temurin release.'
  [[ "$expected" =~ ^[0-9a-fA-F]{64}$ ]] || fail 'The Java installer checksum is invalid.'
  /usr/bin/curl --fail --location --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 600 \
    "$package_url" -o "$package" || fail 'Java could not be downloaded. Check your connection and try again.'
  actual="$(/usr/bin/shasum -a 256 "$package" | /usr/bin/awk '{print $1}')"
  [[ "$actual" == "$expected" ]] || fail 'The Java checksum did not match. Installation was stopped.'
  /usr/sbin/pkgutil --check-signature "$package" || fail 'macOS could not verify the Java installer signature.'
  printf '\nJava will be installed separately on this Mac. macOS will ask for administrator authorization.\n'
  /usr/bin/osascript - "$package" <<'APPLESCRIPT' || fail 'Java installation was cancelled or failed. You can run this setup again.'
on run arguments
  do shell script "/usr/sbin/installer -pkg " & quoted form of (item 1 of arguments) & " -target /" with administrator privileges
end run
APPLESCRIPT
  find_java || fail 'Installation finished, but Java 17+ could not be found. Open a new Terminal and run this setup again.'
fi

if [[ "${1:-}" == --check-java ]]; then printf 'Java check passed.\n'; exit 0; fi
[[ -f "$script_dir/moondrop.jar" ]] || fail 'moondrop.jar is missing. Extract the complete Moondrop.zip and keep its files together.'
printf '\nStarting Moondrop…\n'
"$java_program" -jar "$script_dir/moondrop.jar" "$@" || fail 'Moondrop could not start. See INSTALL.md for troubleshooting.'
