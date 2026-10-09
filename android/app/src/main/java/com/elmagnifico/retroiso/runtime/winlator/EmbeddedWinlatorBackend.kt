package com.elmagnifico.retroiso.runtime.winlator

import android.content.Context
import android.content.Intent
import com.elmagnifico.retroiso.Game
import com.elmagnifico.retroiso.runtime.GameProfile
import com.winlator.XServerDisplayActivity
import com.winlator.container.Container
import com.winlator.container.ContainerManager
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
        val installerDosPath: String
    )

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

        return PreparedContainer(
            containerId = container.id,
            containerName = container.name,
            isoFile = iso,
            cdDir = cdDir,
            installerFile = installerFile,
            installerDosPath = profile.cdRom.drive + "\\" + profile.installer
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
            it.saveData()
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
                    .put("startupSelection", Container.STARTUP_SELECTION_ESSENTIAL.toInt())

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

        val winVersions = WinVersions.getWinVersions()
        val index = winVersions.indexOfFirst { it.version == profile.windows }
        if (index >= 0) WineUtils.setWinVersion(container, index)

        container.saveData()
    }
}
