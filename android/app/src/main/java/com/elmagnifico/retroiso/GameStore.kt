package com.elmagnifico.retroiso

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
    val shortcutPath: String? = null
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
                shortcutPath = o.optString("shortcutPath").takeIf { it.isNotBlank() }
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

    /** Copie un patch dans <jeu>/Patch. */
    suspend fun copyPatch(game: Game, uri: Uri) = withContext(Dispatchers.IO) {
        val dest = File(gameDir(game), "Patch").apply { mkdirs() }
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            File(dest, displayName(uri)).outputStream().use { output -> input.copyTo(output) }
        }
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
            iso = "disc.iso"
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
