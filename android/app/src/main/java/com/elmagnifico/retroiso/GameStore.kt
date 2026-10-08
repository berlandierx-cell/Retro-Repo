package com.elmagnifico.retroiso

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

/** dir = nom du dossier dans Téléchargements/RetroIso (visible depuis Winlator sur le lecteur D:). */
data class Game(val id: String, val name: String, val dir: String)

class GameStore(private val ctx: Context) {

    private val index = File(ctx.filesDir, "games.json")
    private val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RetroIso")

    fun gameDir(g: Game) = File(root, g.dir)

    fun load(): List<Game> {
        if (!index.exists()) return emptyList()
        val arr = JSONArray(index.readText())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Game(o.getString("id"), o.getString("name"), o.getString("dir"))
        }
    }

    private fun save(list: List<Game>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("dir", it.dir)) }
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
        return "fichier"
    }

    private fun uniqueDir(name: String): String {
        val base = name.replace(Regex("[^A-Za-z0-9 ._-]"), "_").trim().ifEmpty { "Jeu" }
        var d = base
        var n = 2
        while (File(root, d).exists()) d = "$base-${n++}"
        return d
    }

    /** Copie un patch (.exe) dans <jeu>/Patch. */
    suspend fun copyPatch(game: Game, uri: Uri) = withContext(Dispatchers.IO) {
        val dest = File(gameDir(game), "Patch").apply { mkdirs() }
        ctx.contentResolver.openInputStream(uri)!!.use { i ->
            File(dest, displayName(uri)).outputStream().use { o -> i.copyTo(o) }
        }
    }

    /** Extrait l'ISO (choisi dans le sélecteur Android, Drive compris) dans Téléchargements/RetroIso/<jeu>. */
    suspend fun importIso(uri: Uri, onProgress: (Float) -> Unit): Game = withContext(Dispatchers.IO) {
        val name = displayName(uri).substringBeforeLast('.')
        val game = Game(UUID.randomUUID().toString(), name, uniqueDir(name))
        val dest = gameDir(game).apply { mkdirs() }
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Impossible d'ouvrir le fichier")
        var input: FileInputStream = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        var tmp: File? = null
        try {
            var ch = input.channel
            val seekable = try { ch.read(ByteBuffer.allocate(1), 0); true } catch (e: IOException) { false }
            if (!seekable) { // certains fournisseurs (Drive) donnent un flux : on copie d'abord en cache
                input.close()
                val t = File(ctx.cacheDir, "iso.tmp")
                tmp = t
                ctx.contentResolver.openInputStream(uri)!!.use { i -> t.outputStream().use { o -> i.copyTo(o) } }
                input = FileInputStream(t)
                ch = input.channel
            }
            val iso = IsoReader(ch).apply { open() }
            val entries = iso.list()
            val total = entries.filter { !it.isDir }.sumOf { it.size }.coerceAtLeast(1)
            var done = 0L
            var lastPct = -1
            for (e in entries) {
                val target = File(dest, e.path)
                if (!target.canonicalPath.startsWith(dest.canonicalPath)) continue
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
            save(load() + game)
            game
        } catch (t: Throwable) {
            dest.deleteRecursively()
            throw t
        } finally {
            input.close()
            tmp?.delete()
        }
    }
}
