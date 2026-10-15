#!/bin/bash
# Share Java discovery and setup with the first-run installer.
script_dir="$(cd -- "$(dirname -- "$0")" && pwd)" || exit 1
exec /bin/bash "$script_dir/installmacos.command" "$@"
