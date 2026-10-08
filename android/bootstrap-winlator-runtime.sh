#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
RUNTIME_DIR="$ROOT/.runtime/winlator"
UPSTREAM="https://github.com/brunodev85/winlator-app.git"
PIN="3981d86efa4f333b2a34a7da8b6521476cd8c8b9"

rm -rf "$RUNTIME_DIR"
mkdir -p "$(dirname "$RUNTIME_DIR")"
git init -q "$RUNTIME_DIR"
git -C "$RUNTIME_DIR" remote add origin "$UPSTREAM"
git -C "$RUNTIME_DIR" fetch -q --depth 1 origin "$PIN"
git -C "$RUNTIME_DIR" checkout -q --detach FETCH_HEAD

python3 - "$RUNTIME_DIR/app/build.gradle" "$RUNTIME_DIR/app/src/main/AndroidManifest.xml" <<'PY'
from pathlib import Path
import re, sys

gradle = Path(sys.argv[1])
s = gradle.read_text()
s = s.replace("apply plugin: 'com.android.application'", "apply plugin: 'com.android.library'")
s = re.sub(r"\n\s*applicationId\s+'com\.winlator'\s*", "\n", s)
gradle.write_text(s)

manifest = Path(sys.argv[2])
m = manifest.read_text()
# The runtime is embedded in Retro ISO: keep Winlator activities/services, but no second launcher.
m = re.sub(
    r'(<activity android:name="com\.winlator\.MainActivity"[\s\S]*?>)[\s\S]*?<intent-filter>[\s\S]*?<action android:name="android\.intent\.action\.MAIN"/>[\s\S]*?<category android:name="android\.intent\.category\.LAUNCHER"/>[\s\S]*?</intent-filter>',
    r'\1',
    m,
    count=1
)
# Avoid application-level manifest merge conflicts with Retro ISO.
m = re.sub(r'<application[\s\S]*?android:label="@string/app_name">', '<application android:extractNativeLibs="true">', m, count=1)
m = m.replace('android:authorities="com.winlator.FileProvider"', 'android:authorities="${applicationId}.winlator.FileProvider"')
manifest.write_text(m)
PY

echo "Winlator runtime prepared at $RUNTIME_DIR"
