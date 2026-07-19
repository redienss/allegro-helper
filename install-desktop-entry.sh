#!/usr/bin/env bash
# Install an "Allegro Helper" desktop launcher (application menu + Desktop
# shortcut) that uses the app icon. Safe to re-run. Uninstall by deleting the
# two .desktop files and the installed icon (paths printed at the end).
set -euo pipefail

cd "$(dirname "$0")"
ROOT_DIR="$(pwd)"
RUN="$ROOT_DIR/run.sh"
ICON_SRC="$ROOT_DIR/icons/AllegroHelper-icon-full-logo-1024.png"

if [[ ! -f "$ICON_SRC" ]]; then
  echo "error: icon not found: $ICON_SRC" >&2
  exit 1
fi

# Build once if needed so the launcher works on first click.
if [[ ! -f build/allegro-helper.jar && ! -d build/classes ]]; then
  ./build.sh
fi

# Install the icon into the hicolor theme (also fine to reference by absolute path).
ICON_NAME="allegro-helper"
ICON_DIR="$HOME/.local/share/icons/hicolor/512x512/apps"
mkdir -p "$ICON_DIR"
cp "$ICON_SRC" "$ICON_DIR/$ICON_NAME.png"

# Write the .desktop entry.
#
# Deliberately no base-directory argument: an explicit argument outranks the
# base directory saved in File > Settings > Photos, so pinning the repo path
# here (as this script used to) made that setting impossible to apply from the
# launcher — it saved, and nothing happened. Without one, run.sh's own cd makes
# the repo the fallback anyway, so a user who never touches the setting sees no
# difference.
APPS_DIR="$HOME/.local/share/applications"
mkdir -p "$APPS_DIR"
DESKTOP_FILE="$APPS_DIR/allegro-helper.desktop"
cat > "$DESKTOP_FILE" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=Allegro Helper
Comment=Prepare Allegro Lokalnie offers (import, match, retouch, describe)
Exec="$RUN"
Icon=$ICON_DIR/$ICON_NAME.png
Terminal=false
Categories=Utility;
StartupNotify=true
StartupWMClass=AllegroHelper
EOF
chmod +x "$DESKTOP_FILE"

# Refresh caches (best-effort).
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$APPS_DIR" 2>/dev/null || true
command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -f "$HOME/.local/share/icons/hicolor" 2>/dev/null || true

# Also drop a shortcut on the Desktop, marked trusted for GNOME.
DESKTOP_COPY=""
for d in "$HOME/Desktop" "$HOME/Pulpit"; do
  if [[ -d "$d" ]]; then
    DESKTOP_COPY="$d/allegro-helper.desktop"
    cp "$DESKTOP_FILE" "$DESKTOP_COPY"
    chmod +x "$DESKTOP_COPY"
    command -v gio >/dev/null 2>&1 && gio set "$DESKTOP_COPY" metadata::trusted true 2>/dev/null || true
    break
  fi
done

echo "Installed:"
echo "  app menu : $DESKTOP_FILE"
[[ -n "$DESKTOP_COPY" ]] && echo "  desktop  : $DESKTOP_COPY"
echo "  icon     : $ICON_DIR/$ICON_NAME.png"
echo "Log out/in (or restart the shell) if the menu entry doesn't appear immediately."
