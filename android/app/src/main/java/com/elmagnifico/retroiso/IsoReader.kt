package com.elmagnifico.retroiso

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Lecteur ISO9660 (+ Joliet pour les noms longs). Pas de montage, pas de root. */
class IsoReader(private val ch: FileChannel) {

    data class Entry(val path: String, val extent: Long, val size: Long, val isDir: Boolean)

    private var joliet = false
    private var rootExtent = 0L
    private var rootSize = 0L

    fun open() {
        var primary: ByteArray? = null
        var jol: ByteArray? = null
        var sector = 16L
        while (sector < 64) {
            val b = readBytes(sector * SECTOR, SECTOR)
            if (String(b, 1, 5, Charsets.US_ASCII) != "CD001") break
            val type = b[0].toInt() and 0xff
            if (type == 255) break
            if (type == 1 && primary == null) primary = b
            if (type == 2) {
                val esc = String(b, 88, 3, Charsets.US_ASCII)
                if (esc == "%/@" || esc == "%/C" || esc == "%/E") jol = b
            }
            sector++
        }
        val pvd = jol ?: primary ?: throw IllegalStateException("Fichier ISO non reconnu")
        joliet = jol != null
        rootExtent = le32(pvd, 156 + 2)
        rootSize = le32(pvd, 156 + 10)
    }

    fun list(): List<Entry> {
        val out = mutableListOf<Entry>()
        walk(rootExtent, rootSize, "", out, 0)
        return out
    }

    fun copyTo(entry: Entry, sink: java.io.OutputStream, onBytes: (Long) -> Unit) {
        val buf = ByteBuffer.allocate(64 * 1024)
        var pos = entry.extent * SECTOR
        var remaining = entry.size
        while (remaining > 0) {
            buf.clear()
            buf.limit(minOf(buf.capacity().toLong(), remaining).toInt())
            val n = ch.read(buf, pos)
            if (n <= 0) throw java.io.IOException("Lecture ISO interrompue")
            sink.write(buf.array(), 0, n)
            pos += n
            remaining -= n
            onBytes(n.toLong())
        }
    }

    private fun walk(extent: Long, size: Long, prefix: String, out: MutableList<Entry>, depth: Int) {
        if (depth > 32) return
        val data = readBytes(extent * SECTOR, size.toInt())
        var pos = 0
        while (pos < data.size) {
            val len = data[pos].toInt() and 0xff
            if (len == 0) { pos = (pos / SECTOR + 1) * SECTOR; continue }
            val nameLen = data[pos + 32].toInt() and 0xff
            val flags = data[pos + 25].toInt()
            val first = data[pos + 33].toInt()
            if (!(nameLen == 1 && (first == 0 || first == 1))) {
                val charset = if (joliet) Charsets.UTF_16BE else Charsets.US_ASCII
                var name = String(data, pos + 33, nameLen, charset).substringBefore(';')
                if (!joliet) name = name.trimEnd('.')
                name = name.replace('/', '_').replace('\\', '_')
                if (name.isNotEmpty() && name != "." && name != "..") {
                    val isDir = (flags and 2) != 0
                    val ext = le32(data, pos + 2)
                    val sz = le32(data, pos + 10)
                    val path = prefix + name
                    out.add(Entry(path, ext, sz, isDir))
                    if (isDir) walk(ext, sz, "$path/", out, depth + 1)
                }
            }
            pos += len
        }
    }

    private fun readBytes(offset: Long, length: Int): ByteArray {
        val buf = ByteBuffer.allocate(length)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = ch.read(buf, pos)
            if (n <= 0) break
            pos += n
        }
        return buf.array()
    }

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xff) or
            ((b[o + 1].toLong() and 0xff) shl 8) or
            ((b[o + 2].toLong() and 0xff) shl 16) or
            ((b[o + 3].toLong() and 0xff) shl 24)

    companion object { const val SECTOR = 2048 }
}
