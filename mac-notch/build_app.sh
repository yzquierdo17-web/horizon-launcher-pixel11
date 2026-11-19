#!/bin/bash
# Builds "Horizon Notch.app" next to this script. Needs Xcode or the Command Line Tools (xcode-select --install).
set -euo pipefail
cd "$(dirname "$0")"

swift build -c release
BIN="$(swift build -c release --show-bin-path)/HorizonNotch"

APP="Horizon Notch.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/HorizonNotch"
cp Info.plist "$APP/Contents/Info.plist"

# Ad-hoc signature so macOS will run it and remember its permissions
codesign --force --deep --sign - "$APP"

echo "Built $APP"
echo "Move it to Applications, then open it. Right-click → Open the first time if macOS warns."
