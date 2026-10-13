package it.liberofiles.core

import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Local-only trash outside app-private storage: uninstalling the app does not erase trashed files. */
class Trash(private val local: LocalStorage) {
    val directory: Node get() = local.child(local.rootNode, ".LiberoFilesTrash")
        ?: local.create(local.rootNode, ".LiberoFilesTrash", true)
    private fun records(): Journal = Journal(File(local.path(directory).toFile(), ".records"))
    data class Item(val record: Journal.Record, val original: String, val stored: Node, val time: Long)
    fun put(source: Node): Item {
        require(source != local.rootNode && !local.contains(directory, source)) { "Elemento protetto" }
        val name = "${UUID.randomUUID()}-data"
        val record = records().begin(mapOf("operation" to "trash", "original" to source.id, "stored" to name,
            "time" to System.currentTimeMillis().toString()))
        try {
            val result = local.moveAtomic(source, directory, name) ?: error("Questa memoria non supporta il cestino rapido")
            return Item(record, source.id, result, record.values.getValue("time").toLong())
        } catch (e: Exception) {
            if (local.child(directory, name) == null) records().finish(record)
            throw e
        }
    }
    fun items(): List<Item> = records().pending().mapNotNull { record ->
        val node = local.child(directory, record.values.getValue("stored")) ?: return@mapNotNull null
        Item(record, record.values.getValue("original"), node, record.values.getValue("time").toLong())
    }.sortedByDescending { it.time }
    fun restore(item: Item): Node {
        val original = Node(local.key, item.original)
        val parent = local.parent(original) ?: error("Percorso originale non disponibile")
        require(Files.isDirectory(local.path(parent))) { "La cartella originale non esiste più" }
        val name = uniqueName(local, parent, local.path(original).fileName.toString())
        val restored = local.moveAtomic(item.stored, parent, name) ?: error("Ripristino atomico non disponibile")
        records().finish(item.record)
        return restored
    }
    fun delete(item: Item, engine: FileEngine, cancellation: Cancellation) {
        engine.removeTree(item.stored, cancellation)
        records().finish(item.record)
    }
}
