#!/usr/bin/env bash
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_DIR="$HOME/.local/share/applications"

mkdir -p "$APP_DIR"

cat << EOF_INNER > "$APP_DIR/yamsync.desktop"
[Desktop Entry]
Name=YamSync
Comment=YamSync Music Player
Exec=$PROJECT_DIR/yamsync %u
Icon=$PROJECT_DIR/desktopApp/icon.png
Terminal=false
Type=Application
Categories=Audio;AudioVideo;
MimeType=x-scheme-handler/yamsync;
EOF_INNER

chmod +x "$APP_DIR/yamsync.desktop"

if command -v xdg-mime >/dev/null 2>&1; then
    xdg-mime default yamsync.desktop x-scheme-handler/yamsync
fi

if command -v gio >/dev/null 2>&1; then
    gio mime x-scheme-handler/yamsync yamsync.desktop >/dev/null 2>&1 || true
fi

if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$APP_DIR" >/dev/null 2>&1 || true
fi

echo "✅ YamSync scheme (yamsync://) successfully registered!"
