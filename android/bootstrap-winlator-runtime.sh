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

# Winlator embeds its original package name in native components and in the
# prebuilt Linux rootfs. com.winlator and com.retroiso have the same byte
# length, so this replacement is safe even inside ELF binaries.
python3 - "$RUNTIME_DIR" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
old = b"com.winlator"
new = b"com.retroiso"
assert len(old) == len(new)

targets = [
    root / "app/src/main/cpp",
    root / "app/src/main/assets/rootfs.tzst",
]

# Patch hard-coded native source paths before CMake compilation.
for base in [root / "app/src/main/cpp"]:
    for p in base.rglob("*"):
        if not p.is_file():
            continue
        data = p.read_bytes()
        if b"/data/data/com.winlator" in data:
            p.write_bytes(data.replace(old, new))

# Patch Java hard-coded storage path without touching Java package declarations.
app_utils = root / "app/src/main/java/com/winlator/core/AppUtils.java"
data = app_utils.read_bytes()
app_utils.write_bytes(
    data.replace(b"/data/data/com.winlator/storage", b"/data/data/com.retroiso/storage")
)
PY

# Patch every embedded com.winlator occurrence inside the Linux rootfs itself.
ROOTFS_ARCHIVE="$RUNTIME_DIR/app/src/main/assets/rootfs.tzst"
ROOTFS_PATCH="$RUNTIME_DIR/.rootfs-package-patch"
rm -rf "$ROOTFS_PATCH"
mkdir -p "$ROOTFS_PATCH"
tar --zstd -xf "$ROOTFS_ARCHIVE" -C "$ROOTFS_PATCH"

python3 - "$ROOTFS_PATCH" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
old = b"com.winlator"
new = b"com.retroiso"
assert len(old) == len(new)

patched = []
for p in root.rglob("*"):
    if not p.is_file() or p.is_symlink():
        continue
    try:
        data = p.read_bytes()
    except OSError:
        continue
    if old in data:
        count = data.count(old)
        p.write_bytes(data.replace(old, new))
        patched.append((str(p.relative_to(root)), count))

print("=== RETROISO ROOTFS PACKAGE PATCH ===")
for name, count in patched:
    print(f"{count}x {name}")
print(f"Patched files: {len(patched)}")
print("=====================================")
PY

rm -f "$ROOTFS_ARCHIVE"
tar --zstd -cf "$ROOTFS_ARCHIVE" -C "$ROOTFS_PATCH" .

# Patch the absolute ELF interpreter embedded in Winlator's Box64 binary.
BOX64_ARCHIVE="$RUNTIME_DIR/app/src/main/assets/box64/box64-0.4.0.tzst"
BOX64_PATCH="$RUNTIME_DIR/.box64-patch"
rm -rf "$BOX64_PATCH"
mkdir -p "$BOX64_PATCH"
tar --zstd -xf "$BOX64_ARCHIVE" -C "$BOX64_PATCH"
BOX64_BIN="$(find "$BOX64_PATCH" -type f -name box64 | head -n 1)"

OLD_INTERP="$(patchelf --print-interpreter "$BOX64_BIN")"
NEW_INTERP="/data/data/com.retroiso/files/rootfs/lib/ld-linux-aarch64.so.1"

echo "=== RETROISO BOX64 ELF ==="
echo "Old interpreter: $OLD_INTERP"
patchelf --set-interpreter "$NEW_INTERP" "$BOX64_BIN"
echo "New interpreter: $(patchelf --print-interpreter "$BOX64_BIN")"
echo "=========================="

rm -f "$BOX64_ARCHIVE"
tar --zstd -cf "$BOX64_ARCHIVE" -C "$BOX64_PATCH" .

python3 - \
  "$RUNTIME_DIR/app/src/main/java/com/winlator/XServerDisplayActivity.java" \
  "$RUNTIME_DIR/app/src/main/java/com/winlator/core/ProcessHelper.java" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()

if "import android.app.AlertDialog;" not in s:
    s = s.replace(
        "package com.winlator;\n",
        "package com.winlator;\n\nimport android.app.AlertDialog;\nimport android.os.Handler;\nimport android.os.Looper;\n"
    )

# Persistent log buffer accessible both from onCreate() and the launcher callback.
field_needle = "    private DebugDialog debugDialog;\n"
field_repl = (
    "    private DebugDialog debugDialog;\n"
    "    private final StringBuilder retroIsoBootLog = new StringBuilder();\n"
)
if field_needle not in s:
    raise SystemExit("Retro ISO log field patch point not found")
s = s.replace(field_needle, field_repl, 1)

needle = '''        ProcessHelper.removeAllDebugCallbacks();
        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false) || preferences.getInt("box64_logs", 0) >= 1;
'''
replacement = '''        ProcessHelper.removeAllDebugCallbacks();
        retroIsoBootLog.setLength(0);
        ProcessHelper.addDebugCallback((line) -> {
            synchronized (retroIsoBootLog) {
                retroIsoBootLog.append(line).append("\\n");
                if (retroIsoBootLog.length() > 16000) {
                    retroIsoBootLog.delete(0, retroIsoBootLog.length() - 16000);
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

        // Diagnostic snapshot for InstallShield hangs: even when a Windows
        // window is mapped, dump recent Wine/COM logs instead of waiting for
        // the guest process to terminate.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing()) {
                String rawLog;
                synchronized (retroIsoBootLog) {
                    rawLog = retroIsoBootLog.toString();
                }

                StringBuilder useful = new StringBuilder();
                for (String line : rawLog.split("\\n")) {
                    String low = line.toLowerCase(java.util.Locale.ROOT);
                    boolean interesting =
                        low.contains("err:ole:") ||
                        low.contains("fixme:ole:") ||
                        low.contains("warn:ole:") ||
                        low.contains("err:rpc:") ||
                        low.contains("fixme:rpc:") ||
                        low.contains("err:seh:") ||
                        low.contains("err:module:") ||
                        low.contains("err:loaddll:") ||
                        low.contains("wininet:") ||
                        low.contains("ws2_32:") ||
                        low.contains("internetgetconnectedstate") ||
                        low.contains("internetconnect") ||
                        low.contains("ftp") ||
                        low.contains("http") ||
                        low.contains("getaddrinfo") ||
                        low.contains("queryinterface") ||
                        low.contains("cocreate") ||
                        low.contains("classfactory") ||
                        low.contains("80004002") ||
                        low.contains("nointerface");
                    if (interesting && !low.contains("trace:")) {
                        useful.append(line).append("\\n");
                    }
                }

                String logText = useful.toString().trim();
                if (logText.isEmpty()) {
                    logText = "Aucune ligne COM/erreur ciblée trouvée.\\n\\nDernières lignes brutes :\\n" + rawLog.trim();
                }
                if (logText.length() > 10000) {
                    logText = logText.substring(logText.length() - 10000);
                }

                new AlertDialog.Builder(this)
                    .setTitle("Retro ISO - diagnostic InstallShield")
                    .setMessage("Logs COM/erreurs ciblés :\\n\\n" + logText)
                    .setPositiveButton("Continuer", null)
                    .setCancelable(true)
                    .show();
            }
        }, 15000);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!flags[0] && !isFinishing()) {
                preloaderDialog.closeOnUiThread();
                String logText;
                synchronized (retroIsoBootLog) {
                    logText = retroIsoBootLog.toString().trim();
                }
                if (logText.isEmpty()) {
                    logText = "Aucune sortie Wine/Box64 reçue.";
                }
                if (logText.length() > 7000) {
                    logText = logText.substring(logText.length() - 7000);
                }

                new AlertDialog.Builder(this)
                    .setTitle("Retro ISO - démarrage bloqué")
                    .setMessage(
                        "Aucune fenêtre Windows n'est apparue après 30 secondes.\\n\\n" +
                        "Dernières lignes du moteur :\\n\\n" + logText
                    )
                    .setPositiveButton("Fermer", (dialog, which) -> finish())
                    .setCancelable(false)
                    .show();
            }
        }, 30000);

        Executors.newSingleThreadExecutor().execute(() -> {
'''
if needle2 not in s:
    raise SystemExit("Retro ISO watchdog patch point not found")
s = s.replace(needle2, replacement2, 1)

# Winlator normally closes the whole activity as soon as the guest process ends.
# During Retro ISO bring-up we keep it open and show the exact status + logs.
term_old = '''        guestProgramLauncherComponent.setTerminationCallback((status) -> exit());
'''
term_new = '''        guestProgramLauncherComponent.setTerminationCallback((status) -> runOnUiThread(() -> {
            String logText;
            synchronized (retroIsoBootLog) {
                logText = retroIsoBootLog.toString().trim();
            }
            if (logText.isEmpty()) logText = "Aucune sortie Wine/Box64 capturée.";
            if (logText.length() > 7000) {
                logText = logText.substring(logText.length() - 7000);
            }

            new AlertDialog.Builder(this)
                .setTitle("Retro ISO - processus terminé")
                .setMessage(
                    "Le processus invité s'est arrêté. Code de sortie : " + status +
                    "\\n\\nDernières lignes du moteur :\\n\\n" + logText
                )
                .setPositiveButton("Fermer", (dialog, which) -> exit())
                .setCancelable(false)
                .show();
        }));
'''
if term_old not in s:
    raise SystemExit("Retro ISO termination callback patch point not found")
s = s.replace(term_old, term_new, 1)

wine_debug_old = '''        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        String wineDebugChannels = preferences.getString("wine_debug_channels", SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS);
        envVars.put("WINEDEBUG", enableWineDebug && !wineDebugChannels.isEmpty() ? "+"+wineDebugChannels.replace(",", ",+") : "-all");
'''
wine_debug_new = '''        boolean enableWineDebug = preferences.getBoolean("enable_wine_debug", false);
        if (enableWineDebug) {
            envVars.put("WINEDEBUG", "err+ole,fixme+ole,warn+ole,err+rpc,fixme+rpc,err+seh,err+module,err+loaddll,trace+wininet,warn+wininet,err+wininet,trace+ws2_32,warn+ws2_32,err+ws2_32");
        }
        else {
            envVars.put("WINEDEBUG", "-all");
        }
'''
if wine_debug_old not in s:
    raise SystemExit("Retro ISO WINEDEBUG patch point not found")
s = s.replace(wine_debug_old, wine_debug_new, 1)

p.write_text(s)

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
