#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
RUNTIME_DIR="$ROOT/.runtime/winlator"
UPSTREAM="https://github.com/brunodev85/winlator-app.git"
PIN="c2f4ad4534f4637b543a9a3b085e28f50cf6d01c"

rm -rf "$RUNTIME_DIR"
mkdir -p "$(dirname "$RUNTIME_DIR")"

git init -q "$RUNTIME_DIR"
git -C "$RUNTIME_DIR" remote add origin "$UPSTREAM"
git -C "$RUNTIME_DIR" fetch -q --depth 1 origin "$PIN"
git -C "$RUNTIME_DIR" checkout -q --detach FETCH_HEAD

echo "Winlator sources prepared at $RUNTIME_DIR ($PIN)"
