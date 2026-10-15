#!/usr/bin/env sh
cd -- "$(dirname -- "$0")" || exit 1
if ! command -v java >/dev/null 2>&1; then
  printf '%s\n' 'Moondrop needs Java 17 or newer. Install Java 21 using the Linux steps in INSTALL.md.' >&2
  exit 1
fi
java -jar "$(pwd)/moondrop.jar" "$@"
result=$?
if [ "$result" -ne 0 ]; then
  printf '\n%s\n' 'Moondrop could not start. Check java -version (17+ required) and see INSTALL.md.' >&2
fi
exit "$result"
