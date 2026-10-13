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
  "$RUNTIME_DIR/app/src/main/java/com/winlator/core/ProcessHelper.java" \
  "$RUNTIME_DIR/app/src/main/java/com/winlator/core/NetworkHelper.java" <<'PY'
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
    "    private com.elmagnifico.retroiso.runtime.winlator.RetroIsoDiagnostics retroIsoDiagnostics;\n"
)
if field_needle not in s:
    raise SystemExit("Retro ISO log field patch point not found")
s = s.replace(field_needle, field_repl, 1)

needle = '''        ProcessHelper.removeAllDebugCallbacks();
        boolean enableLogs = preferences.getBoolean("enable_wine_debug", false) || preferences.getInt("box64_logs", 0) >= 1;
'''
replacement = '''        ProcessHelper.removeAllDebugCallbacks();
        retroIsoBootLog.setLength(0);
        retroIsoDiagnostics = new com.elmagnifico.retroiso.runtime.winlator.RetroIsoDiagnostics(
            this, "EXE: " + getIntent().getStringExtra("exec_path") +
            "\\nChemin Windows: " + getIntent().getStringExtra("retroiso_dos_path") +
            "\\nContainer: " + getIntent().getIntExtra("container_id", 0) +
            "\\nPreparation: " + getIntent().getStringExtra("retroiso_preflight"));
        ProcessHelper.addDebugCallback((line) -> {
            retroIsoDiagnostics.append(line);
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
            if (preferences.getBoolean("enable_wine_debug", false) && !isFinishing()) {
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
            if (preferences.getBoolean("enable_wine_debug", false) && !flags[0] && !isFinishing()) {
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
            retroIsoDiagnostics.append("RETROISO_PROCESS_EXIT: " + status);
            retroIsoDiagnostics.flush();
            if (getIntent().getBooleanExtra("retroiso_auto_exit", false)) {
                exit();
                return;
            }

            if (!preferences.getBoolean("enable_wine_debug", false)) {
                exit();
                return;
            }

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
            envVars.put("WINEDEBUG", "-all,err+all,warn+module,trace+process,trace+loaddll,trace+msgbox");
        }
'''
if wine_debug_old not in s:
    raise SystemExit("Retro ISO WINEDEBUG patch point not found")
s = s.replace(wine_debug_old, wine_debug_new, 1)


# Preserve the exact drive selected in Retro ISO instead of remapping CD files to D:\\CD.
dos_old = '                execPath = WineUtils.unixToDOSPath(intent.getStringExtra("exec_path"), container);'
dos_new = '''                execPath = intent.getStringExtra("retroiso_dos_path");
                if (execPath == null || execPath.isEmpty())
                    execPath = WineUtils.unixToDOSPath(intent.getStringExtra("exec_path"), container);'''
if dos_old not in s:
    raise SystemExit("Retro ISO DOS launch patch point not found")
s = s.replace(dos_old, dos_new, 1)

command_old = '            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);'
command_new = '''            retroIsoDiagnostics.append("RETROISO_COMMAND: " + guestExecutable);
            retroIsoDiagnostics.append("RETROISO_GRAPHICS: " + java.util.Arrays.toString(graphicsDriver));
            guestProgramLauncherComponent.setGuestExecutable(guestExecutable);'''
if command_old not in s:
    raise SystemExit("Retro ISO command log patch point not found")
s = s.replace(command_old, command_new, 1)

destroy_old = '        super.onDestroy();'
destroy_new = '''        if (retroIsoDiagnostics != null) retroIsoDiagnostics.close();
        super.onDestroy();'''
if destroy_old not in s:
    raise SystemExit("Retro ISO diagnostic lifecycle patch point not found")
s = s.replace(destroy_old, destroy_new, 1)

p.write_text(s)

# Winlator 11.2 only formats a few IPv4 prefix lengths (/8,/16,/24,/32).
# Mobile networks commonly expose /30,/29,/28... which produced an empty
# netmask in tmp/ifaddrs and can make legacy WinInet report "offline".
nh = Path(sys.argv[3])
ns = nh.read_text()
old_mask = '''    public static String formatNetmask(int prefixLength) {
        switch (prefixLength) {
            case 8: return "255.0.0.0";
            case 16: return "255.255.0.0";
            case 24: return "255.255.255.0";
            case 32: return "255.255.255.255";
            case 64: return "ffff:ffff:ffff:ffff::";
            default: return "";
        }
    }
'''
new_mask = '''    public static String formatNetmask(int prefixLength) {
        if (prefixLength >= 0 && prefixLength <= 32) {
            long mask = prefixLength == 0 ? 0L : (0xffffffffL << (32 - prefixLength)) & 0xffffffffL;
            return ((mask >>> 24) & 255) + "." +
                   ((mask >>> 16) & 255) + "." +
                   ((mask >>> 8) & 255) + "." +
                   (mask & 255);
        }
        if (prefixLength == 64) return "ffff:ffff:ffff:ffff::";
        return "";
    }
'''
if old_mask not in ns:
    raise SystemExit("Retro ISO NetworkHelper netmask patch point not found")
nh.write_text(ns.replace(old_mask, new_mask, 1))

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

# Attach the optional Android on-screen controls after the existing Winlator UI
# is assembled. Their Java implementation lives in Retro ISO source and is
# compiled together with the pinned upstream runtime.
python3 - "$RUNTIME_DIR/app/src/main/java/com/winlator/XServerDisplayActivity.java" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()
old = '''        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));
        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);'''
new = '''        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));
        // Retro ISO's optional game-specific overlay must be on top of
        // TouchpadView and InputControlsView to receive accurate tap positions.
        if (getIntent().getBooleanExtra("retroiso_touch_controls", false)) {
            retroIsoControlsOverlay = new com.elmagnifico.retroiso.runtime.winlator.RetroIsoControlsOverlay(
                this, rootView, xServer, xServerView, touchpadView);
            // Foreground service keeps the Wine process less likely to be
            // reclaimed if Android sends the user to another app briefly.
            try {
                android.content.Intent session = new android.content.Intent(this,
                    com.elmagnifico.retroiso.runtime.winlator.RetroIsoGameSessionService.class);
                if (getIntent().getExtras() != null)
                    session.putExtras(getIntent().getExtras());
                startForegroundService(session);
                retroIsoDiagnostics.append("RETROISO_SESSION: foreground requested");
            } catch (RuntimeException ex) {
                retroIsoDiagnostics.append("RETROISO_SESSION: foreground unavailable: " + ex);
            }
        }
        AppUtils.observeSoftKeyboardVisibility(drawerLayout, renderer::setScreenOffsetYRelativeToCursor);'''
if s.count(old) != 1:
    raise SystemExit("Retro ISO touch controls patch point not found")
s = s.replace(old, new, 1)

field_anchor = '''    private String screenEffectProfile;'''
field = '''    private String screenEffectProfile;
    // Keep the overlay alive so held keyboard modifiers can be released on exit.
    private com.elmagnifico.retroiso.runtime.winlator.RetroIsoControlsOverlay retroIsoControlsOverlay;'''
if s.count(field_anchor) != 1:
    raise SystemExit("Retro ISO overlay lifecycle field patch point not found")
s = s.replace(field_anchor, field, 1)

destroy_anchor = '''    protected void onDestroy() {
        winHandler.stop();'''
destroy = '''    protected void onDestroy() {
        if (retroIsoControlsOverlay != null) {
            retroIsoControlsOverlay.releaseHeldKeys();
            stopService(new android.content.Intent(this,
                com.elmagnifico.retroiso.runtime.winlator.RetroIsoGameSessionService.class));
        }
        winHandler.stop();'''
if s.count(destroy_anchor) != 1:
    raise SystemExit("Retro ISO overlay lifecycle destroy patch point not found")
s = s.replace(destroy_anchor, destroy, 1)

pause_anchor = '''    public void onPause() {
        super.onPause();'''
pause_patch = '''    public void onPause() {
        if (retroIsoDiagnostics != null) retroIsoDiagnostics.append("RETROISO_LIFECYCLE: onPause");
        super.onPause();'''
if s.count(pause_anchor) != 1:
    raise SystemExit("Retro ISO pause diagnostic patch point not found")
s = s.replace(pause_anchor, pause_patch, 1)

resume_anchor = '''    public void onResume() {
        super.onResume();'''
resume_patch = '''    public void onResume() {
        super.onResume();
        if (retroIsoDiagnostics != null) retroIsoDiagnostics.append("RETROISO_LIFECYCLE: onResume");'''
if s.count(resume_anchor) != 1:
    raise SystemExit("Retro ISO resume diagnostic patch point not found")
s = s.replace(resume_anchor, resume_patch, 1)

memory_anchor = '''    @Override
    protected void onDestroy() {'''
memory_patch = '''    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (retroIsoDiagnostics != null)
            retroIsoDiagnostics.append("RETROISO_LIFECYCLE: onTrimMemory " + level);
    }

    @Override
    protected void onDestroy() {'''
if s.count(memory_anchor) != 1:
    raise SystemExit("Retro ISO low-memory diagnostic patch point not found")
s = s.replace(memory_anchor, memory_patch, 1)
p.write_text(s)
PY

# Correct the color-channel ordering of the emulated DirectDraw framebuffer
# only when the player opts in via Gangsters 2 overlay. Rendering remains VirGL.
python3 - \
  "$RUNTIME_DIR/app/src/main/java/com/winlator/renderer/GLRenderer.java" \
  "$RUNTIME_DIR/app/src/main/java/com/winlator/renderer/material/WindowMaterial.java" <<'PY'
from pathlib import Path
import sys

renderer_path = Path(sys.argv[1])
renderer = renderer_path.read_text()
frag_field = '    private boolean forceWindowsFullscreen;'
new_field = '''    private volatile boolean retroIsoSwapRedBlue = false;
    private boolean forceWindowsFullscreen;'''
if renderer.count(frag_field) != 1:
    raise SystemExit("Color renderer field anchor missing")
renderer = renderer.replace(frag_field, new_field, 1)

draw_anchor = '''        windowMaterial.use();
        windowMaterial.setUniformVec2(windowMaterial.uniforms.viewSize, xServer.screenInfo.width, xServer.screenInfo.height);'''
draw_patch = '''        windowMaterial.use();
        windowMaterial.setUniformBool(windowMaterial.uniforms.retroIsoSwapRedBlue, retroIsoSwapRedBlue);
        windowMaterial.setUniformVec2(windowMaterial.uniforms.viewSize, xServer.screenInfo.width, xServer.screenInfo.height);'''
if renderer.count(draw_anchor) != 1:
    raise SystemExit("Color renderer draw anchor missing")
renderer = renderer.replace(draw_anchor, draw_patch, 1)

method_anchor = '''    private void renderWindows() {'''
method_patch = '''    // Toggle available in Gangsters 2 only; default renderer is unmodified.
    public void setRetroIsoSwapRedBlue(boolean enabled) {
        retroIsoSwapRedBlue = enabled;
        xServerView.requestRender();
    }

    private void renderWindows() {'''
if renderer.count(method_anchor) != 1:
    raise SystemExit("Color renderer method anchor missing")
renderer = renderer.replace(method_anchor, method_patch, 1)
renderer_path.write_text(renderer)

material_path = Path(sys.argv[2])
material = material_path.read_text()
uniform_anchor = '        public final Uniform noAlpha = new Uniform("noAlpha");'
uniform_patch = '''        public final Uniform noAlpha = new Uniform("noAlpha");
        public final Uniform retroIsoSwapRedBlue = new Uniform("retroIsoSwapRedBlue");'''
if material.count(uniform_anchor) != 1:
    raise SystemExit("Color uniform anchor missing")
material = material.replace(uniform_anchor, uniform_patch, 1)

shader_anchor = '            "uniform float noAlpha;",'
shader_patch = '''            "uniform float noAlpha;",
            "uniform bool retroIsoSwapRedBlue;",'''
if material.count(shader_anchor) != 1:
    raise SystemExit("Color shader uniform anchor missing")
material = material.replace(shader_anchor, shader_patch, 1)

color_anchor = '"gl_FragColor = vec4(texelColor.rgb, max(texelColor.a, noAlpha));",'
color_patch = '"gl_FragColor = vec4(retroIsoSwapRedBlue ? texelColor.bgr : texelColor.rgb, max(texelColor.a, noAlpha));",'
if material.count(color_anchor) != 1:
    raise SystemExit("Color fragment output anchor missing")
material = material.replace(color_anchor, color_patch, 1)
material_path.write_text(material)
PY

echo "Winlator sources prepared at $RUNTIME_DIR ($PIN) with Retro ISO diagnostics"
