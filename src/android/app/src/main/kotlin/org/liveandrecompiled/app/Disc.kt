package org.liveandrecompiled.app

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

// The releases the game config was made from (README.md, CMakeLists.txt in the repository) and whatever else this build
// was made from (assets/accepted_xbe.txt): a build recompiled from another default.xbe holds that one's addresses, so a
// file with any other hash is refused here. Reading is all the launcher does with the picked copy.
object Releases {
    const val USA_DISC = "2232a1b3874d67b4bb4cf53a940420d2dab59535"
    const val STORE = "68a37f0d03d73dabc86a848df5ec1820dd551254"
    const val TITLE_ID = 0x4D530051
}

class DiscError(message: String) : Exception(message)

// An original Xbox disc image (XDVDFS, not ISO 9660) read through a file descriptor from the document picker; the same
// layout as scripts/xdvdfs.py. It lives here rather than behind libmain.so so that a picked copy is checked and unpacked
// before any game code has to load on this phone.
class Disc(private val channel: FileChannel) {
    class Entry(val name: String, val sector: Long, val size: Long, val dir: Boolean) {
        val children = mutableListOf<Entry>()
    }

    private val base: Long = OFFSETS.firstOrNull { off -> magicAt(off + 0x10000) }
        ?: throw DiscError("Este archivo no es una imagen de disco de Xbox.")
    val root: Entry

    init {
        val d = bytes(base + 0x10000 + MAGIC.size, 8)
        root = Entry("", d.int.toLong() and 0xFFFFFFFFL, d.int.toLong() and 0xFFFFFFFFL, true)
        load(root, HashSet())
    }

    private fun magicAt(at: Long): Boolean {
        if (at + MAGIC.size > channel.size()) return false
        val b = ByteArray(MAGIC.size)
        bytes(at, MAGIC.size).get(b)
        return b.contentEquals(MAGIC)
    }

    private fun bytes(at: Long, n: Int): ByteBuffer {
        val b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        var pos = at
        while (b.hasRemaining()) {
            val got = channel.read(b, pos)
            if (got <= 0) throw DiscError("La imagen de disco está incompleta.")
            pos += got
        }
        b.flip()
        return b
    }

    private fun load(dir: Entry, seen: MutableSet<Long>) {
        if (dir.size == 0L) return
        if (dir.size > MAX_TABLE || !seen.add(dir.sector)) throw DiscError("La imagen de disco está dañada.")
        val table = bytes(base + dir.sector * SECTOR, dir.size.toInt())
        walk(table, 0, dir, seen, HashSet())
        dir.children.sortBy { it.name.lowercase() }
    }

    private fun walk(t: ByteBuffer, dwords: Int, dir: Entry, seen: MutableSet<Long>, visited: MutableSet<Int>) {
        val off = dwords * 4
        if (!visited.add(dwords) || off + 14 > t.limit()) return
        val left = t.getShort(off).toInt() and 0xFFFF
        val right = t.getShort(off + 2).toInt() and 0xFFFF
        if (left == 0xFFFF && right == 0xFFFF) return
        val sector = t.getInt(off + 4).toLong() and 0xFFFFFFFFL
        val size = t.getInt(off + 8).toLong() and 0xFFFFFFFFL
        val attrs = t.get(off + 12).toInt()
        val nameLen = t.get(off + 13).toInt() and 0xFF
        if (off + 14 + nameLen > t.limit()) throw DiscError("La imagen de disco está dañada.")
        val raw = ByteArray(nameLen)
        for (i in 0 until nameLen) raw[i] = t.get(off + 14 + i)
        val name = String(raw, Charsets.ISO_8859_1)
        // a name from the file goes into a path below: nothing that leaves the folder
        if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name) {
            throw DiscError("La imagen de disco tiene un nombre de archivo no válido.")
        }
        val entry = Entry(name, sector, size, attrs and 0x10 != 0)
        if (left != 0) walk(t, left, dir, seen, visited)
        dir.children += entry
        if (entry.dir) load(entry, seen)
        if (right != 0) walk(t, right, dir, seen, visited)
    }

    fun find(name: String): Entry? = root.children.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun totalSize(entry: Entry = root): Long = entry.children.sumOf { if (it.dir) totalSize(it) else it.size }

    // the file's bytes, in pieces
    fun read(entry: Entry, chunk: Int = 4 shl 20, sink: (ByteBuffer) -> Unit) {
        var pos = base + entry.sector * SECTOR
        var left = entry.size
        val b = ByteBuffer.allocate(chunk)
        while (left > 0) {
            b.clear()
            b.limit(minOf(chunk.toLong(), left).toInt())
            val got = channel.read(b, pos)
            if (got <= 0) throw DiscError("La imagen de disco termina dentro de ${entry.name}.")
            b.flip()
            sink(b)
            pos += got
            left -= got
        }
    }

    fun sha1(entry: Entry): String {
        val md = MessageDigest.getInstance("SHA-1")
        read(entry) { md.update(it) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // every file below into out; done tells the bytes so far of the total
    fun extract(out: File, done: (Long, Long) -> Unit) {
        val total = totalSize()
        var written = 0L
        fun go(dir: Entry, to: File) {
            to.mkdirs()
            for (e in dir.children) {
                val file = File(to, e.name)
                if (e.dir) {
                    go(e, file)
                    continue
                }
                file.outputStream().channel.use { o ->
                    read(e) { b ->
                        while (b.hasRemaining()) written += o.write(b)
                        done(written, total)
                    }
                }
            }
        }
        go(root, out)
    }

    companion object {
        private const val SECTOR = 2048L
        private const val MAX_TABLE = 16L shl 20
        private val MAGIC = "MICROSOFT*XBOX*MEDIA".toByteArray()
        // xiso, XGD1, XGD2, XGD3
        private val OFFSETS = longArrayOf(0, 0x18300000, 0xFD90000, 0x2080000)

        fun open(pfd: ParcelFileDescriptor): Disc = Disc(FileInputStream(pfd.fileDescriptor).channel)
    }
}
