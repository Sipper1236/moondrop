#!/bin/sh
set -eu

source_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
data_home=${XDG_DATA_HOME:-"$HOME/.local/share"}
app_dir="$data_home/moondrop"
bin_dir="$HOME/.local/bin"
icon_dir="$data_home/icons/hicolor/scalable/apps"
menu_dir="$data_home/applications"

fail() { printf '%s\n' "$1" >&2; exit 1; }
[ "$(uname -s)" = Linux ] || fail 'This installer is for Linux. See INSTALL.md for your operating system.'
case "$data_home" in /*) ;; *) fail 'XDG_DATA_HOME must be an absolute path.' ;; esac
case "$data_home$HOME" in *'
'*|*'
'*) fail 'Installation paths cannot contain line breaks.' ;; esac
for file in moondrop.jar launch-linux.sh moondrop.desktop moondrop.svg; do
  [ -f "$source_dir/$file" ] || fail "Missing $file. Extract the complete Moondrop.zip and keep its files together."
done

mkdir -p "$app_dir" "$bin_dir" "$icon_dir" "$menu_dir"
temporary=""
trap '[ -z "$temporary" ] || rm -f -- "$temporary"' EXIT HUP INT TERM
copy_file() {
  source_file=$1 destination=$2 mode=$3
  if [ "$source_file" = "$destination" ]; then return; fi
  temporary=$(mktemp "$destination.XXXXXX")
  cp -- "$source_file" "$temporary"
  chmod "$mode" "$temporary"
  mv -f -- "$temporary" "$destination"
  temporary=""
}
copy_file "$source_dir/moondrop.jar" "$app_dir/moondrop.jar" 644
copy_file "$source_dir/launch-linux.sh" "$app_dir/launch-linux.sh" 755
for file in README.md INSTALL.md LICENSE THIRD_PARTY.md; do
  if [ -f "$source_dir/$file" ]; then copy_file "$source_dir/$file" "$app_dir/$file" 644; fi
done
copy_file "$source_dir/moondrop.svg" "$icon_dir/moondrop.svg" 644

# Desktop entries have two escaping layers; field-code '%' must also be literal.
desktop_path=$(printf '%s' "$app_dir/launch-linux.sh" | sed -e 's/\\/\\\\\\\\/g' -e 's/"/\\\\"/g' -e 's/[$`]/\\\\&/g' -e 's/%/%%/g')
temporary=$(mktemp "$menu_dir/moondrop.desktop.XXXXXX")
while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in Exec=*) printf 'Exec=/bin/sh "%s"\n' "$desktop_path" ;; *) printf '%s\n' "$line" ;; esac
done < "$source_dir/moondrop.desktop" > "$temporary"
chmod 644 "$temporary"
mv -f -- "$temporary" "$menu_dir/moondrop.desktop"
temporary=""

# Keep a terminal command even when XDG_DATA_HOME is outside ~/.local/share.
shell_path=$(printf '%s' "$app_dir/launch-linux.sh" | sed "s/'/'\\\\''/g")
temporary=$(mktemp "$bin_dir/moondrop.XXXXXX")
printf '#!/bin/sh\nexec '\''%s'\'' "$@"\n' "$shell_path" > "$temporary"
chmod 755 "$temporary"
mv -f -- "$temporary" "$bin_dir/moondrop"
temporary=""
if command -v update-desktop-database >/dev/null 2>&1; then update-desktop-database "$menu_dir" >/dev/null 2>&1 || true; fi
if command -v gtk-update-icon-cache >/dev/null 2>&1; then gtk-update-icon-cache -f -t "$data_home/icons/hicolor" >/dev/null 2>&1 || true; fi
printf 'Installed Moondrop. Open your application menu and search for Moondrop.\nApp: %s\nShortcut: %s\n' "$app_dir" "$menu_dir/moondrop.desktop"
if ! command -v java >/dev/null 2>&1; then printf 'Install desktop Java 17+ before opening Moondrop; see INSTALL.md.\n'; fi
