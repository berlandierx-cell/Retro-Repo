package com.elmagnifico.retroiso

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileInputStream
import java.util.UUID

/**
 * dir = nom du dossier dans Téléchargements/RetroIso.
 * iso = nom de l'image disque conservée localement.
 * installer = point d'entrée détecté sur le CD extrait.
 * shortcutPath = raccourci .desktop Winlator, copié dans le dossier du jeu.
 */
data class Game(
    val id: String,
    val name: String,
    val dir: String,
    val iso: String? = null,
    val installer: String? = null,
    val shortcutPath: String? = null,
    val sourceUri: String? = null
)

class GameStore(private val ctx: Context) {

    private val index = File(ctx.filesDir, "games.json")
    private val root = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "RetroIso"
    )

    fun gameDir(g: Game) = File(root, g.dir)
    fun isoFile(g: Game) = g.iso?.let { File(gameDir(g), it) }

    fun load(): List<Game> {
        if (!index.exists()) return emptyList()
        val arr = JSONArray(index.readText())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Game(
                id = o.getString("id"),
                name = o.getString("name"),
                dir = o.getString("dir"),
                iso = o.optString("iso").takeIf { it.isNotBlank() },
                installer = o.optString("installer").takeIf { it.isNotBlank() },
                shortcutPath = o.optString("shortcutPath").takeIf { it.isNotBlank() },
                sourceUri = o.optString("sourceUri").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun save(list: List<Game>) {
        val arr = JSONArray()
        list.forEach { g ->
            arr.put(
                JSONObject()
                    .put("id", g.id)
                    .put("name", g.name)
                    .put("dir", g.dir)
                    .put("iso", g.iso ?: "")
                    .put("installer", g.installer ?: "")
                    .put("shortcutPath", g.shortcutPath ?: "")
                    .put("sourceUri", g.sourceUri ?: "")
            )
        }
        index.parentFile?.mkdirs()
        index.writeText(arr.toString())
    }

    fun delete(game: Game) {
        gameDir(game).deleteRecursively()
        save(load().filterNot { it.id == game.id })
    }

    private fun displayName(uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return "jeu.iso"
    }

    private fun uniqueDir(name: String): String {
        val base = name.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().ifEmpty { "Jeu" }
        var d = base
        var n = 2
        while (File(root, d).exists()) d = "$base-${n++}"
        return d
    }

    /**
     * Ajoute un correctif dans <jeu>/Patch.
     * Les packs .7z sont extraits directement : Retro ISO peut donc recevoir
     * le pack Gangsters 2 complet qui a déjà été validé sous Wine/Linux.
     */
    suspend fun copyPatch(game: Game, uri: Uri) = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val dest = File(gameDir(game), "Patch")

        if (name.endsWith(".7z", ignoreCase = true)) {
            // Un nouveau pack complet remplace tous les anciens essais afin
            // d'éviter de mélanger patch EN, DLL et marqueurs précédents.
            dest.deleteRecursively()
            dest.mkdirs()

            val archive = File(dest, "_pack.7z")
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                archive.outputStream().buffered().use { output -> input.copyTo(output) }
            }

            SevenZFile(archive).use { sevenZ ->
                var entry = sevenZ.nextEntry
                val rootPath = dest.canonicalFile.toPath()

                while (entry != null) {
                    val target = File(dest, entry.name).canonicalFile
                    if (!target.toPath().startsWith(rootPath)) {
                        throw IllegalStateException("Entrée 7z invalide : " + entry.name)
                    }

                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var remaining = entry.size
                            while (remaining > 0) {
                                val read = sevenZ.read(
                                    buffer,
                                    0,
                                    minOf(buffer.size.toLong(), remaining).toInt()
                                )
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                                remaining -= read
                            }
                        }
                    }
                    entry = sevenZ.nextEntry
                }
            }

            archive.delete()
        } else {
            dest.mkdirs()
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                File(dest, name).outputStream().use { output -> input.copyTo(output) }
            }
        }

        // Tout nouveau correctif doit pouvoir être relancé.
        File(dest, ".patch107-launched").delete()
    }

    /**
     * Copie un raccourci Winlator .desktop dans un emplacement public et mémorise
     * son chemin absolu. Winlator reçoit ensuite ce chemin via shortcut_path.
     */
    suspend fun attachWinlatorShortcut(game: Game, uri: Uri): Game = withContext(Dispatchers.IO) {
        val shortcuts = File(gameDir(game), "Winlator").apply { mkdirs() }
        val target = File(shortcuts, "launch.desktop")
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val updated = game.copy(shortcutPath = target.absolutePath)
        save(load().map { if (it.id == game.id) updated else it })
        updated
    }

    private fun detectInstaller(dest: File): String? {
        val preferred = listOf("autorun.exe", "setup.exe", "install.exe")
        val allExe = dest.walkTopDown()
            .filter { it.isFile && it.extension.equals("exe", ignoreCase = true) }
            .toList()
        for (name in preferred) {
            allExe.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let {
                return it.relativeTo(dest).invariantSeparatorsPath
            }
        }
        return allExe.firstOrNull()?.relativeTo(dest)?.invariantSeparatorsPath
    }

    fun hasLocalCache(game: Game): Boolean {
        val base = gameDir(game)
        val iso = game.iso?.let { File(base, it) }
        val cd = File(base, "CD")
        return iso?.isFile == true && cd.isDirectory
    }

    /**
     * Supprime uniquement les médias/cache recréables. La fiche du jeu et
     * l'URI Drive persistante sont conservées.
     */
    fun releaseCache(game: Game) {
        game.iso?.let { File(gameDir(game), it).delete() }
        File(gameDir(game), "CD").deleteRecursively()
    }

    /**
     * Restaure automatiquement le cache local depuis la source Drive/content://
     * mémorisée lors de l'import.
     */
    suspend fun ensureCached(game: Game, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        if (hasLocalCache(game)) return@withContext

        val source = game.sourceUri?.let(Uri::parse)
            ?: throw IllegalStateException("Source Drive indisponible pour " + game.name)

        val base = gameDir(game).apply { mkdirs() }
        val isoFile = File(base, game.iso ?: "disc.iso")
        val cd = File(base, "CD").apply { deleteRecursively(); mkdirs() }

        try {
            ctx.contentResolver.openInputStream(source)?.use { input ->
                isoFile.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Impossible d'ouvrir la source Drive.")

            FileInputStream(isoFile).channel.use { ch ->
                val iso = IsoReader(ch).apply { open() }
                val entries = iso.list()
                val total = entries.filter { !it.isDir }.sumOf { it.size }.coerceAtLeast(1)
                var done = 0L
                var lastPct = -1
                for (e in entries) {
                    val target = File(cd, e.path)
                    if (!target.canonicalPath.startsWith(cd.canonicalPath)) continue
                    if (e.isDir) {
                        target.mkdirs()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { out ->
                        iso.copyTo(e, out) { n ->
                            done += n
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(done.toFloat() / total)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            isoFile.delete()
            cd.deleteRecursively()
            throw t
        }
    }

    /**
     * Conserve l'ISO original dans Téléchargements/RetroIso/<jeu>/disc.iso,
     * puis l'extrait dans <jeu>/CD pour inspection et installation.
     * Le fichier disc.iso peut ensuite être monté dans le lecteur CD de Winlator.
     */
    suspend fun importIso(uri: Uri, onProgress: (Float) -> Unit): Game = withContext(Dispatchers.IO) {
        val sourceName = displayName(uri)
        val name = sourceName.substringBeforeLast('.')
        val provisional = Game(
            id = UUID.randomUUID().toString(),
            name = name,
            dir = uniqueDir(name),
            iso = "disc.iso",
            sourceUri = uri.toString()
        )
        val base = gameDir(provisional).apply { mkdirs() }
        val isoFile = File(base, "disc.iso")
        val cd = File(base, "CD").apply { mkdirs() }

        try {
            // Une seule copie locale : fonctionne aussi avec Google Drive/content://.
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                isoFile.outputStream().buffered().use { output -> input.copyTo(output) }
            }

            FileInputStream(isoFile).channel.use { ch ->
                val iso = IsoReader(ch).apply { open() }
                val entries = iso.list()
                val total = entries.filter { !it.isDir }.sumOf { it.size }.coerceAtLeast(1)
                var done = 0L
                var lastPct = -1
                for (e in entries) {
                    val target = File(cd, e.path)
                    if (!target.canonicalPath.startsWith(cd.canonicalPath)) continue
                    if (e.isDir) {
                        target.mkdirs()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { out ->
                        iso.copyTo(e, out) { n ->
                            done += n
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(done.toFloat() / total)
                            }
                        }
                    }
                }
            }

            val game = provisional.copy(installer = detectInstaller(cd))
            save(load() + game)
            game
        } catch (t: Throwable) {
            base.deleteRecursively()
            throw t
        }
    }
}
