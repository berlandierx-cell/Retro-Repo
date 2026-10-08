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

# Retro ISO extension: allow the embedded runtime to launch an already-resolved
# Windows path such as X:\Autorun.exe without going through Winlator's file UI.
python3 - "$RUNTIME_DIR/app/src/main/java/com/winlator/XServerDisplayActivity.java" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()
old = '''    private String getWineStartCommand() {
        String cmdArgs = "";
        String execPath = null;
        String execArgs = "";
'''
new = '''    private String getWineStartCommand() {
        String directDosPath = getIntent().getStringExtra("retroiso_dos_path");
        if (directDosPath != null && !directDosPath.isEmpty()) {
            String execDir = FileUtils.getDirname(directDosPath);
            String filename = FileUtils.getName(directDosPath);
            return "C:\\windows\\winhandler.exe /dir " +
                StringUtils.escapeDOSPath(execDir) + " \"" + filename + "\"";
        }

        String cmdArgs = "";
        String execPath = null;
        String execArgs = "";
'''
if old not in s:
    raise SystemExit("Retro ISO patch point not found in XServerDisplayActivity.java")
p.write_text(s.replace(old, new, 1))
PY

echo "Winlator sources prepared at $RUNTIME_DIR ($PIN)"
