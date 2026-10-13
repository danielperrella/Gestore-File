package it.liberofiles.core

import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

class LocalStorage(root: Path, override val key: String = "local") : Storage {
    val root: Path = root.toAbsolutePath().normalize()
    val rootNode get() = Node(key, root.toString())
    private val noFollow = arrayOf(LinkOption.NOFOLLOW_LINKS)

    fun path(node: Node): Path {
        require(node.store == key)
        val path = Paths.get(node.id).toAbsolutePath().normalize()
        require(path.startsWith(root)) { "Percorso esterno alla memoria autorizzata" }
        var current: Path? = path
        while (current != null && current.startsWith(root)) {
            require(!Files.isSymbolicLink(current)) { "Collegamento simbolico non supportato: $current" }
            current = current.parent
        }
        return path
    }
    override fun stat(node: Node): Entry {
        val p = path(node)
        val a = Files.readAttributes(p, BasicFileAttributes::class.java, *noFollow)
        require(a.isDirectory || a.isRegularFile) { "Tipo di file non supportato" }
        return Entry(node, p.fileName?.toString() ?: p.toString(), a.isDirectory,
            if (a.isDirectory) null else a.size(), a.lastModifiedTime().toMillis(), a.fileKey()?.toString())
    }
    override fun list(directory: Node): List<Entry> {
        val result = ArrayList<Entry>()
        Files.newDirectoryStream(path(directory)).use { ds ->
            for (p in ds) {
                result.add(stat(Node(key, p.toString())))
            }
        }
        return result
    }
    /** Browsing may display inaccessible entries; transfers always use strict list(). */
    fun browse(directory: Node): List<Entry> {
        val result = ArrayList<Entry>()
        Files.newDirectoryStream(path(directory)).use { ds -> for (p in ds) {
            if (Files.isSymbolicLink(p)) continue
            val node = Node(key, p.toString())
            result.add(try { stat(node) } catch (_: java.io.IOException) { Entry(node, p.fileName.toString(), Files.isDirectory(p, *noFollow), null, null) })
        } }
        return result
    }
    override fun parent(node: Node): Node? {
        require(node.store == key)
        val p = Paths.get(node.id).toAbsolutePath().normalize()
        require(p.startsWith(root))
        return p.parent?.takeIf { it.startsWith(root) }?.let { Node(key, it.toString()) }
    }
    override fun child(directory: Node, name: String): Node? {
        val p = path(directory).resolve(validName(name))
        return if (Files.exists(p, *noFollow)) Node(key, p.toString()) else null
    }
    override fun create(directory: Node, name: String, isDirectory: Boolean): Node {
        val p = path(directory).resolve(validName(name))
        if (isDirectory) Files.createDirectory(p) else Files.createFile(p)
        syncDirectory(p.parent)
        return Node(key, p.toString())
    }
    override fun read(node: Node): InputStream = Files.newInputStream(path(node), LinkOption.NOFOLLOW_LINKS)
    override fun writeNew(node: Node): DurableOutput {
        val p = path(node)
        require(Files.size(p) == 0L) { "Rifiutata la scrittura su un file non vuoto" }
        val channel = java.nio.channels.FileChannel.open(p, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        val output = java.nio.channels.Channels.newOutputStream(channel)
        return object : DurableOutput {
            override val stream = output
            override fun sync() { output.flush(); channel.force(true) }
            override fun close() = output.close()
        }
    }
    override fun rename(node: Node, name: String): Node {
        val p = path(node)
        return moveAtomic(node, Node(key, p.parent.toString()), name)
            ?: error("La memoria non supporta la rinomina atomica")
    }
    override fun moveAtomic(node: Node, destination: Node, name: String): Node? {
        val source = path(node)
        val parent = path(destination)
        val dest = parent.resolve(validName(name))
        require(!Files.exists(dest, *noFollow)) { "La destinazione esiste già: $name" }
        try { Files.move(source, dest, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { return null }
        syncDirectory(parent)
        if (source.parent != parent) syncDirectory(source.parent)
        return Node(key, dest.toString())
    }
    override fun deleteEmpty(node: Node) { val p = path(node); Files.delete(p); syncDirectory(p.parent) }
    override fun contains(ancestor: Node, node: Node): Boolean = node.store == key && path(node).startsWith(path(ancestor))
    override fun availableBytes(directory: Node): Long? = path(directory).toFile().let { if (it.totalSpace > 0) it.usableSpace else null }

    private fun syncDirectory(path: Path) {
        // Some Android/FUSE filesystems reject directory fsync; file fsync stays mandatory.
        try { java.nio.channels.FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) } }
        catch (_: java.io.IOException) { }
        catch (_: UnsupportedOperationException) { }
    }
}
