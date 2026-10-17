#!/usr/bin/env bash
set -euo pipefail
core="${1:?Pass the pinned host sing-box binary}"
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
fixture_dir="$(mktemp -d)"
python3 "$repo_dir/android/scripts/vpn-fixture.py" --core "$core" --directory "$fixture_dir" &
fixture_pid=$!
port=""
trap 'if [[ -n "$port" ]]; then adb reverse --remove "tcp:$port" 2>/dev/null || true; fi; kill "$fixture_pid" 2>/dev/null || true; wait "$fixture_pid" 2>/dev/null || true; rm -rf -- "$fixture_dir"' EXIT
for attempt in {1..100}; do
  [[ -f "$fixture_dir/ports.json" ]] && break
  kill -0 "$fixture_pid"
  sleep 0.1
done
port="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["ssPort"])' "$fixture_dir/ports.json")"
adb reverse "tcp:$port" "tcp:$port"
adb shell input keyevent 224
adb shell wm dismiss-keyguard
cd "$repo_dir/android"
./gradlew --no-daemon connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.fixtureSsPort=$port"
