package com.elmagnifico.retroiso.runtime.winlator

import android.content.Context
import android.content.Intent
import com.elmagnifico.retroiso.Game
import com.elmagnifico.retroiso.runtime.GameProfile
import com.winlator.XServerDisplayActivity
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.container.DXWrappers
import com.winlator.core.DefaultVersion
import com.winlator.core.FileUtils
import com.winlator.core.GeneralComponents
import com.winlator.core.TarCompressorUtils
import com.winlator.core.WineRegistryEditor
import com.winlator.core.WineUtils
import com.winlator.win32.WinVersions
import com.winlator.xenvironment.RootFS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Backend Winlator embarqué dans Retro ISO.
 *
 * Stratégie actuelle:
 * - D: = dossier du jeu Retro ISO
 * - X: = contenu extrait du CD (<jeu>/CD)
 * - X: déclaré "cdrom" dans le registre Wine
 * - lancement direct de l'installeur extrait, sans .bat intermédiaire
 *
 * L'ISO original reste conservé dans disc.iso pour le futur montage raw/libcdio.
 */
class EmbeddedWinlatorBackend(private val context: Context) {

    data class PreparedContainer(
        val containerId: Int,
        val containerName: String,
        val isoFile: File,
        val cdDir: File,
        val installerFile: File,
        val installerDosPath: String,
        val gameExecutable: File?,
        val patchExecutable: File?,
        val compatibilityDlls: List<File>,
        val rootDir: File
    )

    suspend fun deleteInstalledGame(profile: GameProfile) = withContext(Dispatchers.IO) {
        ensureRootFs()

        val manager = ContainerManager(context)
        val wantedName = "retroiso-" + profile.id
        val container = manager.containers.firstOrNull { it.name == wantedName } ?: return@withContext

        val wineDir = File(container.rootDir, ".wine")
        val driveC = File(wineDir, "drive_c")

        // Remove only this game's installed files, never the shared Wine runtime.
        profile.installedExecutablePath?.let { rel ->
            val exe = File(driveC, rel)
            exe.parentFile?.let { installDir ->
                if (installDir.isDirectory) installDir.deleteRecursively()
            }
        }

        // Remove legacy installer/updater registry state so the next install is clean.
        val systemReg = File(wineDir, "system.reg")
        if (systemReg.isFile) {
            WineRegistryEditor(systemReg).use { registry ->
                registry.removeKey("Software\\Gangsters2g", true)
                registry.removeKey("Software\\Wow6432Node\\Gangsters2g", true)
                registry.removeKey("Software\\WOW6432Node\\Gangsters2g", true)
            }
        }

        // Also remove per-user keys if an installer created them there.
        val userReg = File(wineDir, "user.reg")
        if (userReg.isFile) {
            WineRegistryEditor(userReg).use { registry ->
                registry.removeKey("Software\\Gangsters2g", true)
            }
        }
    }

    suspend fun prepare(game: Game, gameDir: File, profile: GameProfile): PreparedContainer {
        val iso = File(gameDir, profile.cdRom.image)
        require(iso.isFile) { "ISO introuvable : " + iso.absolutePath }

        val cdDir = File(gameDir, "CD")
        require(cdDir.isDirectory) { "Contenu du CD introuvable : " + cdDir.absolutePath }

        val installerFile = File(cdDir, profile.installer)
        require(installerFile.isFile) {
            "Installeur introuvable : " + installerFile.absolutePath
        }

        ensureRootFs()

        val container = getOrCreateContainer(gameDir, profile)
        configureContainer(container, gameDir, cdDir, profile)

        val gameExecutable = findInstalledExecutable(container, profile)
        val patchDir = File(gameDir, "Patch")
        val patchExecutable = patchDir.walkTopDown()
            .firstOrNull {
                it.isFile && it.extension.equals("exe", true) &&
                    (it.name.contains("107", true) || it.name.contains("patch", true))
            }
        val compatibilityDlls = patchDir.walkTopDown()
            .filter { it.isFile && it.extension.equals("dll", true) }
            .toList()

        return PreparedContainer(
            containerId = container.id,
            containerName = container.name,
            isoFile = iso,
            cdDir = cdDir,
            installerFile = installerFile,
            installerDosPath = profile.cdRom.drive + "\\" + profile.installer,
            gameExecutable = gameExecutable,
            patchExecutable = patchExecutable,
            compatibilityDlls = compatibilityDlls,
            rootDir = container.rootDir
        )
    }

    /**
     * Lance directement le vrai Autorun.exe extrait.
     * Comme X: pointe vers le même CD extrait et est typé CD-ROM,
     * Wine voit immédiatement le média au démarrage de l'installeur.
     */
    fun launchInstaller(prepared: PreparedContainer) {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("enable_wine_debug", true)
            .putString("wine_debug_channels", "ole,seh,loaddll")
            .putInt("box64_logs", 1)
            .apply()

        val intent = Intent(context, XServerDisplayActivity::class.java).apply {
            putExtra("container_id", prepared.containerId)
            putExtra("exec_path", prepared.installerFile.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun patchStateDir(prepared: PreparedContainer): File =
        File(prepared.patchExecutable?.parentFile ?: prepared.rootDir, ".retroiso").apply { mkdirs() }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun launchPatch(prepared: PreparedContainer) {
        val patch = prepared.patchExecutable
            ?: throw IllegalStateException("Patch 1.0.7 introuvable dans le pack ajouté.")
        val gameExe = prepared.gameExecutable
            ?: throw IllegalStateException("Gangsters2.exe introuvable avant application du patch.")

        // The original Hothouse updater does not discover the install path by
        // scanning Program Files. Its own code reads:
        // HKLM\\Software\\Gangsters2g -> PATH / VERSION.
        // Old InstallShield/Wine setups may omit that legacy key, producing the
        // misleading "Impossible de trouver Internet" updater error.
        val driveC = File(prepared.rootDir, ".wine/drive_c")
        val relativeGameDir = gameExe.parentFile.relativeTo(driveC).invariantSeparatorsPath
        val windowsInstallDir = ("C:\\" + relativeGameDir.replace("/", "\\")).let {
            if (it.endsWith("\\")) it else it + "\\"
        }
        val systemReg = File(prepared.rootDir, ".wine/system.reg")
        WineRegistryEditor(systemReg).use { registry ->
            registry.setStringValue("Software\\Gangsters2g", "PATH", windowsInstallDir)
            // Preserve VERSION if the official updater already wrote it.
        }

        val stateDir = patchStateDir(prepared)
        File(stateDir, "prepatch.sha256").writeText(sha256(gameExe))

        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("enable_wine_debug", false)
            .putInt("box64_logs", 0)
            .apply()

        val intent = Intent(context, XServerDisplayActivity::class.java).apply {
            putExtra("container_id", prepared.containerId)
            putExtra("exec_path", patch.absolutePath)
            putExtra("retroiso_auto_exit", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun isPatchApplied(prepared: PreparedContainer): Boolean {
        val exe = prepared.gameExecutable ?: return false
        val stateDir = patchStateDir(prepared)
        val appliedMarker = File(stateDir, "patch107.applied")
        if (appliedMarker.isFile) return true

        // The Hothouse updater writes its release notes / patch language data
        // only after a successful update. This also lets an already-patched
        // installation created by an older Retro ISO build be recognized.
        val gameDir = exe.parentFile
        val updaterArtifacts = listOf(
            File(gameDir, "Update.txt"),
            File(gameDir, "language/patch1.lan"),
            File(gameDir, "Language/patch1.lan")
        )
        if (updaterArtifacts.any { it.isFile }) {
            appliedMarker.writeText("updater-artifact")
            return true
        }

        // Most robust signal: the official updater modifies Gangsters2.exe.
        // Compare it with the executable hash captured immediately before
        // launching the updater. This avoids WoW64 registry redirection quirks.
        val preHashFile = File(stateDir, "prepatch.sha256")
        if (preHashFile.isFile) {
            val before = preHashFile.readText().trim()
            val after = runCatching { sha256(exe) }.getOrNull()
            if (!after.isNullOrBlank() && before.isNotBlank() && after != before) {
                appliedMarker.writeText(after)
                return true
            }
        }

        val systemReg = File(prepared.rootDir, ".wine/system.reg")
        if (!systemReg.isFile) return false

        val candidateKeys = listOf(
            "Software\\Gangsters2g",
            "Software\\Wow6432Node\\Gangsters2g",
            "Software\\WOW6432Node\\Gangsters2g"
        )

        WineRegistryEditor(systemReg).use { registry ->
            for (key in candidateKeys) {
                try {
                    if (registry.getDwordValue(key, "VERSION") != null) {
                        appliedMarker.writeText("registry-dword")
                        return true
                    }
                } catch (_: Exception) {}
                try {
                    val value = registry.getStringValue(key, "VERSION")
                    if (!value.isNullOrBlank()) {
                        appliedMarker.writeText("registry-string")
                        return true
                    }
                } catch (_: Exception) {}
            }
        }

        val text = systemReg.readText()
        val keyPresent = text.contains("Gangsters2g", ignoreCase = true)
        val versionPresent = Regex("""(?im)^"VERSION"=(?:dword:[0-9a-f]+|".*")""").containsMatchIn(text)
        if (keyPresent && versionPresent) {
            appliedMarker.writeText("registry-text")
            return true
        }
        return false
    }

    private fun installCompatibilityDlls(prepared: PreparedContainer) {
        val exe = prepared.gameExecutable ?: return
        prepared.compatibilityDlls.forEach { dll ->
            if (dll.name.equals("DDraw.dll", ignoreCase = true)) return@forEach
            dll.copyTo(File(exe.parentFile, dll.name), overwrite = true)
        }
        // Remove any previously copied pack DDraw.dll so Winlator's CNC DDraw
        // wrapper can take precedence for this classic DirectDraw title.
        File(exe.parentFile, "DDraw.dll").delete()
    }

    /**
     * Lance directement le jeu installé dans C:, avec le répertoire de travail
     * automatiquement dérivé par Winlator à partir du chemin de l'exécutable.
     */
    fun launchGame(prepared: PreparedContainer) {
        val exe = prepared.gameExecutable
            ?: throw IllegalStateException("Jeu installé introuvable dans le container.")

        if (prepared.patchExecutable != null && isPatchApplied(prepared)) {
            installCompatibilityDlls(prepared)
        }

        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("enable_wine_debug", false)
            .putInt("box64_logs", 0)
            .apply()

        val intent = Intent(context, XServerDisplayActivity::class.java).apply {
            putExtra("container_id", prepared.containerId)
            putExtra("exec_path", exe.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun findInstalledExecutable(container: Container, profile: GameProfile): File? {
        val driveC = File(container.rootDir, ".wine/drive_c")
        if (!driveC.isDirectory) return null

        profile.installedExecutablePath?.let { rel ->
            val exact = File(driveC, rel)
            return exact.takeIf { it.isFile }
        }

        return driveC.walkTopDown()
            .onEnter { dir ->
                !dir.name.equals("windows", ignoreCase = true) &&
                !dir.name.equals("system32", ignoreCase = true) &&
                !dir.name.equals("syswow64", ignoreCase = true)
            }
            .firstOrNull {
                it.isFile && it.name.equals(profile.executable, ignoreCase = true)
            }
    }

    fun describeInstalledExecutables(prepared: PreparedContainer): String {
        val exe = prepared.gameExecutable
            ?: return "Gangsters2.exe introuvable : impossible d'identifier le dossier d'installation."

        val root = exe.parentFile
        val candidates = root.walkTopDown()
            .maxDepth(3)
            .filter {
                it.isFile && (
                    it.extension.equals("exe", ignoreCase = true) ||
                    it.extension.equals("dll", ignoreCase = true)
                )
            }
            .sortedWith(compareBy<File>({ it.extension.lowercase() }, { it.name.lowercase() }))
            .toList()

        val lines = mutableListOf<String>()
        lines += "Dossier : " + root.absolutePath
        lines += "EXE/DLL trouvés : " + candidates.size
        lines += ""

        candidates.forEach { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
                .format(java.util.Date(file.lastModified()))
            lines += "[" + file.extension.uppercase() + "] " + rel +
                " | " + file.length() + " octets | " + stamp
        }
        return lines.joinToString("\n")
    }

    fun describeInstalledGameFiles(prepared: PreparedContainer): String {
        val exe = prepared.gameExecutable
            ?: return "Gangsters2.exe introuvable : impossible d'identifier le dossier d'installation."

        val root = exe.parentFile
        val lines = mutableListOf<String>()
        lines += "Dossier : " + root.absolutePath
        lines += ""
        root.walkTopDown()
            .maxDepth(2)
            .sortedBy { it.absolutePath.lowercase() }
            .forEach { entry ->
                if (entry == root) return@forEach
                val rel = entry.relativeTo(root).invariantSeparatorsPath
                if (entry.isDirectory) {
                    lines += "[DIR] " + rel
                } else {
                    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
                        .format(java.util.Date(entry.lastModified()))
                    lines += "[FILE] " + rel + " | " + entry.length() + " octets | " + stamp
                }
            }
        return lines.joinToString("\n")
    }

    /**
     * Test diagnostic : lance directement Setup.exe depuis le CD extrait.
     */
    fun launchCdSetup(prepared: PreparedContainer) {
        val setup = File(prepared.cdDir, "Setup.exe")
        if (!setup.isFile) {
            throw IllegalStateException("Setup.exe introuvable dans le CD.")
        }

        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("enable_wine_debug", false)
            .putInt("box64_logs", 0)
            .apply()

        val intent = Intent(context, XServerDisplayActivity::class.java).apply {
            putExtra("container_id", prepared.containerId)
            putExtra("exec_path", setup.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Relance l'Autorun du CD virtuel après installation.
     * Cela reproduit le comportement "insérer le CD puis Jouer" du jeu original.
     */
    fun launchAutorun(prepared: PreparedContainer) {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean("enable_wine_debug", false)
            .putInt("box64_logs", 0)
            .apply()

        val intent = Intent(context, XServerDisplayActivity::class.java).apply {
            putExtra("container_id", prepared.containerId)
            putExtra("exec_path", prepared.installerFile.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Diagnostic uniquement : ouvre l'ISO via le mécanisme natif Winlator.
     */
    fun launchIso(prepared: PreparedContainer) {
        val intent = Intent(context, XServerDisplayActivity::class.java).apply {
            putExtra("container_id", prepared.containerId)
            putExtra("exec_path", prepared.isoFile.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private suspend fun ensureRootFs() = withContext(Dispatchers.IO) {
        val rootFs = RootFS.find(context)

        // Winlator 11.2 app submodule utilise actuellement RFS version 19.
        if (!rootFs.isValid() || rootFs.version < 19) {
            val root = rootFs.rootDir
            if (!root.isDirectory) root.mkdirs()

            val ok = TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD,
                context,
                "rootfs.tzst",
                root
            )
            check(ok) { "Impossible d'installer le moteur Wine/Box64 intégré." }
            rootFs.createRFSVersionFile(19)
        }

        // Toujours vérifier le runtime, même si le rootfs existait déjà.
        installAndVerifyRuntime(rootFs)
    }

    private fun installAndVerifyRuntime(rootFs: RootFS) {
        GeneralComponents.extractFile(
            GeneralComponents.Type.BOX64,
            context,
            DefaultVersion.BOX64,
            DefaultVersion.BOX64
        )

        val root = rootFs.rootDir
        val box64 = File(root, "usr/local/bin/box64")
        val wine = File(root, "opt/wine/bin/wine")
        val wine64 = File(root, "opt/wine/bin/wine64")

        check(box64.isFile) {
            "Box64 introuvable après installation : " + box64.absolutePath
        }
        check(wine.isFile || wine64.isFile) {
            "Wine introuvable après installation dans " + File(root, "opt/wine/bin").absolutePath
        }

        FileUtils.chmod(box64, 0x1ED) // 0755
        if (wine.isFile) FileUtils.chmod(wine, 0x1ED)
        if (wine64.isFile) FileUtils.chmod(wine64, 0x1ED)

        val targetSdk = context.applicationInfo.targetSdkVersion
        val selinux = try {
            File("/proc/self/attr/current").readText().trim()
        } catch (_: Exception) {
            "inconnu"
        }

        val fileContext = try {
            val p = ProcessBuilder("/system/bin/ls", "-lZ", box64.absolutePath)
                .redirectErrorStream(true)
                .start()
            p.inputStream.bufferedReader().use { it.readText() }.trim()
        } catch (e: Exception) {
            "ls -lZ indisponible: " + (e.message ?: "erreur inconnue")
        }

        val directTrue = try {
            val p = ProcessBuilder("/system/bin/true").start()
            "systemTrue=" + p.waitFor()
        } catch (e: Exception) {
            "systemTrue=" + e.javaClass.simpleName + ":" + (e.message ?: "")
        }

        val copiedTrue = try {
            val testFile = File(root, "tmp/retroiso_true")
            testFile.parentFile?.mkdirs()
            File("/system/bin/true").inputStream().use { input ->
                testFile.outputStream().use { output -> input.copyTo(output) }
            }
            FileUtils.chmod(testFile, 0x1ED)
            val p = ProcessBuilder(testFile.absolutePath).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            "copiedTrue=" + p.waitFor() + (if (out.isNotEmpty()) ":$out" else "")
        } catch (e: Exception) {
            "copiedTrue=" + e.javaClass.simpleName + ":" + (e.message ?: "")
        }

        val securityInfo =
            "Android=" + android.os.Build.VERSION.SDK_INT +
            ", targetSdk=" + targetSdk +
            ", SELinux=" + selinux +
            ", canExecute=" + box64.canExecute() +
            ", canRead=" + box64.canRead() +
            "\n" + directTrue +
            "\n" + copiedTrue +
            "\nBox64: " + fileContext

        val probe = try {
            val pb = ProcessBuilder(box64.absolutePath, "--version")
                .redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = rootFs.libDir.absolutePath
            pb.environment()["HOME"] = root.absolutePath + RootFS.HOME_PATH
            pb.environment()["TMPDIR"] = root.absolutePath + "/tmp"
            pb.start()
        } catch (e: Exception) {
            throw IllegalStateException(
                "Box64 bloqué par Android. " + securityInfo + "\n\n" +
                e.javaClass.simpleName + ": " + (e.message ?: "erreur inconnue")
            )
        }

        val output = probe.inputStream.bufferedReader().use { it.readText() }.trim()
        val status = probe.waitFor()
        check(status == 0) {
            "Box64 démarre mais retourne code " + status + ". " + securityInfo + "\n" + output
        }
    }

    private suspend fun getOrCreateContainer(
        gameDir: File,
        profile: GameProfile
    ): Container {
        val manager = withContext(Dispatchers.IO) { ContainerManager(context) }
        val wantedName = "retroiso-" + profile.id

        manager.containers.firstOrNull { it.name == wantedName }?.let {
            it.drives = "D:" + gameDir.absolutePath
            it.startupSelection = Container.STARTUP_SELECTION_NORMAL
            it.saveData()
            WineUtils.changeServicesStatus(it, Container.STARTUP_SELECTION_NORMAL)
            return it
        }

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val data = JSONObject()
                    .put("name", wantedName)
                    .put("screenSize", "1280x720")
                    .put("envVars", Container.DEFAULT_ENV_VARS)
                    .put("wincomponents", Container.DEFAULT_WINCOMPONENTS)
                    .put("drives", "D:" + gameDir.absolutePath)
                    .put("startupSelection", Container.STARTUP_SELECTION_NORMAL.toInt())

                manager.createContainerAsync(data) { created ->
                    if (created == null) {
                        continuation.resumeWithException(
                            IllegalStateException("Impossible de créer le container " + wantedName)
                        )
                    } else {
                        continuation.resume(created)
                    }
                }
            }
        }
    }

    private suspend fun configureContainer(
        container: Container,
        gameDir: File,
        cdDir: File,
        profile: GameProfile
    ) = withContext(Dispatchers.IO) {
        container.drives = "D:" + gameDir.absolutePath

        // Laisse Winlator créer les dosdevices standards.
        WineUtils.createDosdevicesSymlinks(container, true)

        val wineDir = File(container.rootDir, ".wine")
        val driveX = File(wineDir, "drive_x")

        // Remplace le drive_x vide par un lien vers le vrai contenu extrait du CD.
        FileUtils.delete(driveX)
        FileUtils.symlink(cdDir.absolutePath, driveX.absolutePath)

        // Le serial est utilisé par Wine pour les lecteurs optiques.
        val serial = String.format(Locale.ENGLISH, "%-8x", 'X'.code).replace(' ', '0')
        FileUtils.writeString(File(cdDir, ".windows-serial"), serial + "\n")

        // Réassure le lien DOS x: -> ../drive_x après remplacement.
        val dosdevices = File(wineDir, "dosdevices")
        FileUtils.delete(File(dosdevices, "x:"))
        FileUtils.symlink("../drive_x", File(dosdevices, "x:").absolutePath)

        // Important pour les vieux jeux / InstallShield :
        // x: expose le contenu lisible du CD, tandis que x:: représente le
        // périphérique CD-ROM brut. Winlator 10.1+ utilise libcdio et sait
        // traiter une image ISO/BIN/CUE comme source du lecteur virtuel X:.
        // On pointe donc x:: directement vers l'ISO original conservé par
        // Retro ISO au lieu de simuler uniquement un dossier CD.
        val discImage = File(gameDir, profile.cdRom.image)
        require(discImage.isFile) { "Image CD introuvable : " + discImage.absolutePath }
        FileUtils.delete(File(dosdevices, "x::"))
        FileUtils.symlink(discImage.absolutePath, File(dosdevices, "x::").absolutePath)

        val systemReg = File(wineDir, "system.reg")
        WineRegistryEditor(systemReg).use {
            it.setStringValue("Software\\Wine\\Drives", "x:", "cdrom")
        }

        // Compatibility is profile-driven: old InstallShield packages may ship
        // private Windows DLLs that must take precedence over Wine built-ins.
        if (profile.dllOverrides.isNotEmpty()) {
            val userReg = File(wineDir, "user.reg")
            WineRegistryEditor(userReg).use { registry ->
                profile.dllOverrides.forEach { (name, mode) ->
                    registry.setStringValue("Software\\Wine\\DllOverrides", name, mode)
                }
            }
        }

        // Full service stack is required by legacy InstallShield COM.
        // In particular RpcSs must not be disabled.
        container.startupSelection = Container.STARTUP_SELECTION_NORMAL
        WineUtils.changeServicesStatus(container, Container.STARTUP_SELECTION_NORMAL)

        val winVersions = WinVersions.getWinVersions()
        val index = winVersions.indexOfFirst { it.version == profile.windows }
        if (index >= 0) WineUtils.setWinVersion(container, index)

        if (profile.id == "gangsters-2") {
            container.setDXWrapper(DXWrappers.DXVK)
            container.setDXWrapperConfig("ddrawWrapper=" + DXWrappers.CNC_DDRAW)
        }

        container.saveData()
    }
}
