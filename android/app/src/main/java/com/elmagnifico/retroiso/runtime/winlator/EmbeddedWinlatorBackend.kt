package com.elmagnifico.retroiso.runtime.winlator

import android.content.Context
import android.content.Intent
import com.elmagnifico.retroiso.Game
import com.elmagnifico.retroiso.runtime.GameProfile
import com.winlator.XServerDisplayActivity
import com.winlator.container.Container
import com.winlator.container.ContainerManager
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class EmbeddedWinlatorBackend(private val context: Context) {

    data class PreparedContainer(
        val containerId: Int,
        val containerName: String,
        val isoFile: File,
        val installerDosPath: String,
        val bootstrapFile: File
    )

    suspend fun prepare(game: Game, gameDir: File, profile: GameProfile): PreparedContainer {
        val iso = File(gameDir, profile.cdRom.image)
        require(iso.isFile) { "ISO introuvable : " + iso.absolutePath }

        ensureRootFs()

        val container = getOrCreateContainer(gameDir, profile)
        configureContainer(container, gameDir, profile)
        val bootstrap = createInstallBootstrap(gameDir, profile)

        return PreparedContainer(
            containerId = container.id,
            containerName = container.name,
            isoFile = iso,
            installerDosPath = profile.cdRom.drive + "\\" + profile.installer,
            bootstrapFile = bootstrap
        )
    }

    fun launchInstaller(prepared: PreparedContainer) {
        val intent = Intent(context, XServerDisplayActivity::class.java).apply {
            putExtra("container_id", prepared.containerId)
            putExtra("exec_path", prepared.bootstrapFile.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun launchIso(prepared: PreparedContainer) {
        val intent = Intent(context, XServerDisplayActivity::class.java).apply {
            putExtra("container_id", prepared.containerId)
            putExtra("exec_path", prepared.isoFile.absolutePath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun launchDosPath(prepared: PreparedContainer, dosPath: String) {
        val intent = Intent(context, XServerDisplayActivity::class.java).apply {
            putExtra("container_id", prepared.containerId)
            putExtra("retroiso_dos_path", dosPath)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private suspend fun ensureRootFs() = withContext(Dispatchers.IO) {
        val rootFs = RootFS.find(context)
        if (rootFs.isValid() && rootFs.version >= 23) return@withContext

        val root = rootFs.rootDir
        if (!root.isDirectory) root.mkdirs()

        val ok = TarCompressorUtils.extract(
            TarCompressorUtils.Type.ZSTD,
            context,
            "rootfs.tzst",
            root
        )
        check(ok) { "Impossible d'installer le moteur Wine/Box64 intégré." }
        rootFs.createRFSVersionFile(23)
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
        profile: GameProfile
    ) = withContext(Dispatchers.IO) {
        container.drives = "D:" + gameDir.absolutePath
        WineUtils.createDosdevicesSymlinks(container, true)

        val systemReg = File(container.rootDir, ".wine/system.reg")
        WineRegistryEditor(systemReg).use {
            it.setStringValue("Software\\Wine\\Drives", "x:", "cdrom")
        }

        val winVersions = WinVersions.getWinVersions()
        val index = winVersions.indexOfFirst { it.version == profile.windows }
        if (index >= 0) WineUtils.setWinVersion(container, index)

        container.saveData()
    }

    private suspend fun createInstallBootstrap(
        gameDir: File,
        profile: GameProfile
    ): File = withContext(Dispatchers.IO) {
        val bootstrap = File(gameDir, "_retroiso_install.bat")
        val isoDosPath = "D:\\" + profile.cdRom.image
        val installer = profile.cdRom.drive + "\\" + profile.installer

        bootstrap.writeText(
            """
            @echo off
            echo Retro ISO - montage du CD...
            start "" "$isoDosPath"
            ping 127.0.0.1 -n 4 >nul
            if exist "$installer" (
              start "" "$installer"
            ) else (
              echo Le CD n'est pas encore disponible dans ${profile.cdRom.drive}
              explorer ${profile.cdRom.drive}\
            )
            """.trimIndent(),
            Charsets.ISO_8859_1
        )
        bootstrap
    }
}
