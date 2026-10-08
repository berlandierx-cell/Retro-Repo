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

# Android 10+ may refuse exec() from app-private writable storage.
# Package Box64 as a native library so Android installs it in nativeLibraryDir,
# which is an executable code location.
BOX64_ARCHIVE="$RUNTIME_DIR/app/src/main/assets/box64/box64-0.4.0.tzst"
BOX64_TMP="$RUNTIME_DIR/.retroiso-box64"
BOX64_JNI="$RUNTIME_DIR/app/src/main/jniLibs/arm64-v8a"
rm -rf "$BOX64_TMP"
mkdir -p "$BOX64_TMP" "$BOX64_JNI"

tar --zstd -xf "$BOX64_ARCHIVE" -C "$BOX64_TMP"
BOX64_BIN="$(find "$BOX64_TMP" -type f -name box64 | head -n 1)"
if [ -z "$BOX64_BIN" ]; then
  echo "Unable to locate Box64 binary inside $BOX64_ARCHIVE" >&2
  exit 1
fi
cp "$BOX64_BIN" "$BOX64_JNI/libbox64.so"
chmod 0755 "$BOX64_JNI/libbox64.so"

python3 - \
  "$RUNTIME_DIR/app/src/main/java/com/winlator/XServerDisplayActivity.java" \
  "$RUNTIME_DIR/app/src/main/java/com/winlator/core/ProcessHelper.java" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()

# Imports for the Retro ISO watchdog dialog.
if "import android.app.AlertDialog;" not in s:
    s = s.replace("package com.winlator;\n", "package com.winlator;\n\nimport android.app.AlertDialog;\nimport android.os.Handler;\nimport android.os.Looper;\n")

needle = '''        ProcessHelper.removeAllDebugCallbacks();
        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false) || preferences.getInt("box64_logs", 0) >= 1;
'''
replacement = '''        ProcessHelper.removeAllDebugCallbacks();

        final StringBuilder retroIsoBootLog = new StringBuilder();
        ProcessHelper.addDebugCallback((line) -> {
            synchronized (retroIsoBootLog) {
                retroIsoBootLog.append(line).append("\\n");
                if (retroIsoBootLog.length() > 12000) {
                    retroIsoBootLog.delete(0, retroIsoBootLog.length() - 12000);
                }
            }
        });

        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false) || preferences.getInt("box64_logs", 0) >= 1;
'''
if needle not in s:
    raise SystemExit("Retro ISO debug callback patch point not found")
s = s.replace(needle, replacement, 1)

needle2 = '''        setupUI();

        Executors.newSingleThreadExecutor().execute(() -> {
'''
replacement2 = '''        setupUI();

        // Retro ISO watchdog: never leave the user on an endless "Starting up...".
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!flags[0] && !isFinishing()) {
                preloaderDialog.closeOnUiThread();
                String logText;
                synchronized (retroIsoBootLog) {
                    logText = retroIsoBootLog.toString().trim();
                }
                if (logText.isEmpty()) {
                    logText = "Aucune sortie Wine/Box64 reçue. Le moteur n'a probablement pas lancé le processus invité.";
                }
                if (logText.length() > 5000) {
                    logText = logText.substring(logText.length() - 5000);
                }

                new AlertDialog.Builder(this)
                    .setTitle("Retro ISO - démarrage bloqué")
                    .setMessage(
                        "Aucune fenêtre Windows n'est apparue après 20 secondes.\\n\\n" +
                        "Dernières lignes du moteur :\\n\\n" + logText
                    )
                    .setPositiveButton("Fermer", (dialog, which) -> finish())
                    .setCancelable(false)
                    .show();
            }
        }, 20000);

        Executors.newSingleThreadExecutor().execute(() -> {
'''
if needle2 not in s:
    raise SystemExit("Retro ISO watchdog patch point not found")
s = s.replace(needle2, replacement2, 1)

p.write_text(s)

# Route Box64 through Android's executable system linker. Direct exec() of
# app-packaged Box64 is denied on some recent Android builds even from nativeLibraryDir.
gpl = Path(sys.argv[1]).parent / "xenvironment/components/GuestProgramLauncherComponent.java"
gs = gpl.read_text()
old_cmd = '''        String command = rootDir+"/usr/local/bin/box64 "+guestExecutable;
'''
new_cmd = '''        Context launchContext = environment.getContext();
        String box64Path = launchContext.getApplicationInfo().nativeLibraryDir+"/libbox64.so";
        String command = "/system/bin/linker64 \\"" + box64Path + "\\" " + guestExecutable;
'''
if old_cmd not in gs:
    raise SystemExit("Retro ISO GuestProgramLauncher patch point not found")
gpl.write_text(gs.replace(old_cmd, new_cmd, 1))

# Expose native ProcessBuilder/exec failures instead of swallowing them.
ph = Path(sys.argv[2])
ps = ph.read_text()
old = '''        catch (Exception e) {}
        return pid;
'''
new = '''        catch (Exception e) {
            String msg = "RETROISO_EXEC_EXCEPTION: " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            synchronized (debugCallbacks) {
                if (!debugCallbacks.isEmpty()) {
                    for (Callback<String> callback : debugCallbacks) callback.call(msg);
                }
                else System.err.println(msg);
            }
        }
        return pid;
'''
if old not in ps:
    raise SystemExit("Retro ISO ProcessHelper patch point not found")
ph.write_text(ps.replace(old, new, 1))
PY

echo "Winlator sources prepared at $RUNTIME_DIR ($PIN) with Retro ISO diagnostics"
