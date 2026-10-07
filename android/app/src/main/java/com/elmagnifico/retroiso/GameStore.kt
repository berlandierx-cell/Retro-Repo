package com.elmagnifico.retroiso

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.UUID

data class Game(val id: String, val name: String)

class GameStore(private val ctx: Context) {

    private val index = File(ctx.filesDir, "games.json")

    fun gameDir(id: String) = File(ctx.filesDir, "games/$id")
    fun cdDir(id: String) = File(gameDir(id), "cd")      // futur lecteur D: (CD-ROM)
    fun prefixDir(id: String) = File(gameDir(id), "prefix") // futur préfixe Wine (saves)

    fun load(): List<Game> {
        if (!index.exists()) return emptyList()
        val arr = JSONArray(index.readText())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Game(o.getString("id"), o.getString("name"))
        }
    }

    private fun save(list: List<Game>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        index.writeText(arr.toString())
    }

    fun delete(game: Game) {
        gameDir(game.id).deleteRecursively()
        save(load().filterNot { it.id == game.id })
    }

    private fun displayName(uri: Uri): String {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0).substringBeforeLast('.')
        }
        return "Jeu"
    }

    /** Extrait l'ISO dans le dossier du jeu et l'ajoute à la liste. */
    suspend fun importIso(uri: Uri, onProgress: (Float) -> Unit): Game = withContext(Dispatchers.IO) {
        val game = Game(UUID.randomUUID().toString(), displayName(uri))
        val cd = cdDir(game.id).apply { mkdirs() }
        prefixDir(game.id).mkdirs()
        try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Impossible d'ouvrir le fichier")
            pfd.use {
                FileInputStream(it.fileDescriptor).channel.use { ch ->
                    val iso = IsoReader(ch).apply { open() }
                    val entries = iso.list()
                    val total = entries.filter { !it.isDir }.sumOf { it.size }.coerceAtLeast(1)
                    var done = 0L
                    var lastPct = -1
                    for (e in entries) {
                        val target = File(cd, e.path)
                        if (!target.canonicalPath.startsWith(cd.canonicalPath)) continue // sécurité
                        if (e.isDir) { target.mkdirs(); continue }
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { out ->
                            iso.copyTo(e, out) { n ->
                                done += n
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(done.toFloat() / total) }
                            }
                        }
                    }
                }
            }
            save(load() + game)
            game
        } catch (t: Throwable) {
            gameDir(game.id).deleteRecursively()
            throw t
        }
    }
}
